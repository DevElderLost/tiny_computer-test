/* ReShade FX effect for the screen-effect chain - see reshade_fx.h.
 * The pass/texture/sampler handling follows vkBasalt's ReshadeEffect (zlib), re-done on the
 * compositor's own Vulkan table (g_vk) and chain targets instead of a swapchain layer. */
#include "vk_membudget.h"
#include "reshade_fx.h"

#include <algorithm>
#include <climits>
#include <cmath>
#include <cstdarg>
#include <cstdio>
#include <cstring>
#include <ctime>
#include <map>
#include <memory>
#include <set>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#include "reshadefx/effect_codegen.hpp"
#include "reshadefx/effect_parser.hpp"
#include "reshadefx/effect_preprocessor.hpp"

#define STB_IMAGE_IMPLEMENTATION
#define STBI_ONLY_PNG
#define STBI_ONLY_JPEG
#define STBI_ONLY_BMP
#define STBI_ONLY_TGA
#define STBI_NO_STDIO_WRITE
#include "reshadefx/stb_image.h"

extern "C" {
#include "vk_loader.h"
void droiddeck_log(const char *tag, const char *fmt, ...) __attribute__((format(printf, 2, 3)));
void vkp_effects_poke(void); /* effects_chain.c: ask for a redraw even on a static scene */
}

namespace {

/* ------------------------------------------------------------------ request / status (any thread) */

struct Job {
    int gen = 0;
    std::string path, inc, tex;
    int w = 0, h = 0;
    VkFormat fmt = VK_FORMAT_UNDEFINED;
};

struct Compiled {
    Job job;
    reshadefx::module mod;
    bool ok = false;
    std::string err;
};

std::mutex g_mu;
Job g_req;                         /* the latest request (gen bumps on every change) */
std::shared_ptr<Compiled> g_done;  /* newest finished compile, waiting for the compositor thread */
bool g_compiling = false;
int g_compiling_gen = 0, g_compiling_w = 0, g_compiling_h = 0;
VkFormat g_compiling_fmt = VK_FORMAT_UNDEFINED;
char g_status[300] = "off";
int g_failed_gen = -1, g_failed_w = 0, g_failed_h = 0; /* the last request that failed to compile/build at this size */

void set_status(const char *fmt, ...) __attribute__((format(printf, 1, 2)));
void set_status(const char *fmt, ...) {
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(g_status, sizeof(g_status), fmt, ap); /* callers hold g_mu or are single-writer */
    va_end(ap);
}

std::string base_name(const std::string &p) {
    size_t s = p.find_last_of('/');
    return s == std::string::npos ? p : p.substr(s + 1);
}
std::string dir_name(const std::string &p) {
    size_t s = p.find_last_of('/');
    return s == std::string::npos ? std::string(".") : p.substr(0, s);
}

/* ------------------------------------------------------------------ compile (worker thread) */

std::shared_ptr<Compiled> compile(const Job &job) {
    auto c = std::make_shared<Compiled>();
    c->job = job;
    reshadefx::preprocessor pp;
    pp.add_macro_definition("__RESHADE__", "50100");
    pp.add_macro_definition("__RESHADE_PERFORMANCE_MODE__", "1");
    pp.add_macro_definition("__RENDERER__", "0x20000");
    pp.add_macro_definition("__VENDOR__", "0");
    pp.add_macro_definition("__DEVICE__", "0");
    pp.add_macro_definition("__APPLICATION__", "0");
    pp.add_macro_definition("BUFFER_WIDTH", std::to_string(job.w));
    pp.add_macro_definition("BUFFER_HEIGHT", std::to_string(job.h));
    pp.add_macro_definition("BUFFER_RCP_WIDTH", "(1.0 / BUFFER_WIDTH)");
    pp.add_macro_definition("BUFFER_RCP_HEIGHT", "(1.0 / BUFFER_HEIGHT)");
    pp.add_macro_definition("BUFFER_COLOR_BIT_DEPTH", job.fmt == VK_FORMAT_A2B10G10R10_UNORM_PACK32 ? "10" : "8");
    pp.add_macro_definition("BUFFER_COLOR_DEPTH", job.fmt == VK_FORMAT_A2B10G10R10_UNORM_PACK32 ? "10" : "8");
    if (!job.inc.empty()) pp.add_include_path(job.inc);
    pp.add_include_path(dir_name(job.path));
    if (!pp.append_file(job.path)) {
        c->err = "cannot read " + base_name(job.path) + (pp.errors().empty() ? "" : ": " + pp.errors());
        return c;
    }
    if (pp.output().empty()) { c->err = "preprocessor: " + pp.errors(); return c; }
    reshadefx::parser parser;
    std::unique_ptr<reshadefx::codegen> cg(reshadefx::create_codegen_spirv(
        true /* vulkan semantics */, false /* debug info */, true /* uniforms -> spec constants */, true /* flip y */));
    if (!parser.parse(std::move(pp.output()), cg.get())) {
        std::string e = parser.errors();
        if (e.size() > 240) e.resize(240);
        c->err = e.empty() ? "parse failed" : e;
        return c;
    }
    cg->write_result(c->mod);
    if (c->mod.spirv.empty() || c->mod.techniques.empty()) { c->err = "no technique in " + base_name(job.path); return c; }
    c->ok = true;
    return c;
}

void worker(Job job) {
    std::shared_ptr<Compiled> c = compile(job);
    {
        std::lock_guard<std::mutex> lk(g_mu);
        g_compiling = false;
        if (job.gen == g_req.gen) { /* a newer request already replaced this one: drop it */
            g_done = c;
            if (!c->ok) set_status("error: %s", c->err.c_str());
            else set_status("compiled %s, building", base_name(job.path).c_str());
        }
    }
    if (!c->ok) droiddeck_log("effects", "reshade: %s: %s", base_name(job.path).c_str(), c->err.c_str());
    vkp_effects_poke();
}

/* ------------------------------------------------------------------ Vulkan objects (compositor thread) */

VkDevice g_dev;
VkPhysicalDeviceMemoryProperties g_mp;

int memtype(uint32_t bits, VkMemoryPropertyFlags want) {
    for (uint32_t i = 0; i < g_mp.memoryTypeCount; i++)
        if ((bits & (1u << i)) && (g_mp.memoryTypes[i].propertyFlags & want) == want) return (int)i;
    return -1;
}

struct Tex {
    std::string name;
    VkImage img = VK_NULL_HANDLE;
    VkDeviceMemory mem = VK_NULL_HANDLE;
    VkImageView view = VK_NULL_HANDLE, rview = VK_NULL_HANDLE, sview = VK_NULL_HANDLE;
    VkFormat fmt = VK_FORMAT_UNDEFINED;
    uint32_t w = 1, h = 1, levels = 1;
    bool source = false; /* uploaded from a file; otherwise a render target */
    bool semantic = false; /* COLOR / DEPTH: stands for the chain's image */
};

struct Staging { VkBuffer buf = VK_NULL_HANDLE; VkDeviceMemory mem = VK_NULL_HANDLE; int tex = -1; uint32_t w = 0, h = 0; };

struct PassState {
    const reshadefx::pass_info *info = nullptr;
    VkRenderPass rp = VK_NULL_HANDLE;
    VkPipeline pipe = VK_NULL_HANDLE;
    bool bb = false;                 /* writes the back buffer */
    std::vector<int> rts;            /* render-target texture indices */
    VkFramebuffer fb = VK_NULL_HANDLE; /* render-target passes: fixed */
    uint32_t w = 0, h = 0;
    std::map<VkImageView, VkFramebuffer> bbfb; /* back-buffer passes: per target view */
};

struct Fx {
    int gen = 0, w = 0, h = 0;
    VkFormat fmt = VK_FORMAT_UNDEFINED;
    std::shared_ptr<Compiled> c;
    std::vector<const reshadefx::pass_info *> list;
    VkShaderModule sm = VK_NULL_HANDLE;
    std::vector<Tex> tex;
    std::vector<int> samp_tex; /* sampler -> texture index, -1 = the chain image */
    std::vector<VkSampler> samplers;
    VkDescriptorSetLayout dslU = VK_NULL_HANDLE, dslS = VK_NULL_HANDLE;
    VkPipelineLayout pl = VK_NULL_HANDLE;
    VkDescriptorPool pool = VK_NULL_HANDLE;
    VkBuffer ubo = VK_NULL_HANDLE;
    VkDeviceMemory ubomem = VK_NULL_HANDLE;
    void *ubomap = nullptr;
    uint32_t ubosize = 0;
    VkDescriptorSet uboSet = VK_NULL_HANDLE;
    VkImage bbimg[2] = {VK_NULL_HANDLE, VK_NULL_HANDLE};
    VkDeviceMemory bbmem[2] = {VK_NULL_HANDLE, VK_NULL_HANDLE};
    VkImageView bbview[2] = {VK_NULL_HANDLE, VK_NULL_HANDLE};
    VkDescriptorSet bbset[2] = {VK_NULL_HANDLE, VK_NULL_HANDLE};
    struct SrcSet { VkImageView view = VK_NULL_HANDLE; VkDescriptorSet set = VK_NULL_HANDLE; } srcsets[4];
    int nsrc = 0, rr = 0;
    std::vector<PassState> ps;
    std::vector<Staging> staging;
    int nbb = 0;
    bool first = true;
    uint32_t frame = 0;
    double t0 = 0.0;
};

Fx *g_fx = nullptr;

double now_ms() {
    timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return ts.tv_sec * 1000.0 + ts.tv_nsec / 1e6;
}

VkFormat conv_format(reshadefx::texture_format f) {
    using F = reshadefx::texture_format;
    switch (f) {
    case F::r8: return VK_FORMAT_R8_UNORM;
    case F::r16f: return VK_FORMAT_R16_SFLOAT;
    case F::r32f: return VK_FORMAT_R32_SFLOAT;
    case F::rg8: return VK_FORMAT_R8G8_UNORM;
    case F::rg16: return VK_FORMAT_R16G16_UNORM;
    case F::rg16f: return VK_FORMAT_R16G16_SFLOAT;
    case F::rg32f: return VK_FORMAT_R32G32_SFLOAT;
    case F::rgba8: return VK_FORMAT_R8G8B8A8_UNORM;
    case F::rgba16: return VK_FORMAT_R16G16B16A16_UNORM;
    case F::rgba16f: return VK_FORMAT_R16G16B16A16_SFLOAT;
    case F::rgba32f: return VK_FORMAT_R32G32B32A32_SFLOAT;
    case F::rgb10a2: return VK_FORMAT_A2B10G10R10_UNORM_PACK32;
    default: return VK_FORMAT_UNDEFINED;
    }
}

VkBlendOp conv_op(reshadefx::pass_blend_op o) {
    using O = reshadefx::pass_blend_op;
    switch (o) {
    case O::subtract: return VK_BLEND_OP_SUBTRACT;
    case O::rev_subtract: return VK_BLEND_OP_REVERSE_SUBTRACT;
    case O::min: return VK_BLEND_OP_MIN;
    case O::max: return VK_BLEND_OP_MAX;
    default: return VK_BLEND_OP_ADD;
    }
}
VkBlendFactor conv_factor(reshadefx::pass_blend_func f) {
    using B = reshadefx::pass_blend_func;
    switch (f) {
    case B::one: return VK_BLEND_FACTOR_ONE;
    case B::src_color: return VK_BLEND_FACTOR_SRC_COLOR;
    case B::src_alpha: return VK_BLEND_FACTOR_SRC_ALPHA;
    case B::inv_src_color: return VK_BLEND_FACTOR_ONE_MINUS_SRC_COLOR;
    case B::inv_src_alpha: return VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA;
    case B::dst_alpha: return VK_BLEND_FACTOR_DST_ALPHA;
    case B::inv_dst_alpha: return VK_BLEND_FACTOR_ONE_MINUS_DST_ALPHA;
    case B::dst_color: return VK_BLEND_FACTOR_DST_COLOR;
    case B::inv_dst_color: return VK_BLEND_FACTOR_ONE_MINUS_DST_COLOR;
    default: return VK_BLEND_FACTOR_ZERO;
    }
}
VkPrimitiveTopology conv_topology(reshadefx::primitive_topology t) {
    using T = reshadefx::primitive_topology;
    switch (t) {
    case T::point_list: return VK_PRIMITIVE_TOPOLOGY_POINT_LIST;
    case T::line_list: return VK_PRIMITIVE_TOPOLOGY_LINE_LIST;
    case T::line_strip: return VK_PRIMITIVE_TOPOLOGY_LINE_STRIP;
    case T::triangle_strip: return VK_PRIMITIVE_TOPOLOGY_TRIANGLE_STRIP;
    default: return VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;
    }
}
VkSamplerAddressMode conv_addr(reshadefx::texture_address_mode m) {
    using A = reshadefx::texture_address_mode;
    switch (m) {
    case A::wrap: return VK_SAMPLER_ADDRESS_MODE_REPEAT;
    case A::mirror: return VK_SAMPLER_ADDRESS_MODE_MIRRORED_REPEAT;
    case A::border: return VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_BORDER;
    default: return VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    }
}

bool make_image(Tex &t, VkImageUsageFlags usage, bool mut) {
    VkImageCreateInfo ii = {};
    ii.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
    ii.flags = mut ? VK_IMAGE_CREATE_MUTABLE_FORMAT_BIT : 0;
    ii.imageType = VK_IMAGE_TYPE_2D;
    ii.format = t.fmt;
    ii.extent = {t.w, t.h, 1};
    ii.mipLevels = t.levels;
    ii.arrayLayers = 1;
    ii.samples = VK_SAMPLE_COUNT_1_BIT;
    ii.tiling = VK_IMAGE_TILING_OPTIMAL;
    ii.usage = usage;
    ii.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    ii.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    if (g_vk.CreateImage(g_dev, &ii, nullptr, &t.img) != VK_SUCCESS) return false;
    VkMemoryRequirements rq;
    g_vk.GetImageMemoryRequirements(g_dev, t.img, &rq);
    int idx = memtype(rq.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
    if (idx < 0) idx = memtype(rq.memoryTypeBits, 0);
    VkMemoryAllocateInfo mi = {};
    mi.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    mi.allocationSize = rq.size;
    mi.memoryTypeIndex = idx < 0 ? 0 : (uint32_t)idx;
    if (vkmb_allocate(g_dev, &mi, &t.mem) != VK_SUCCESS) return false;
    g_vk.BindImageMemory(g_dev, t.img, t.mem, 0);
    return true;
}

VkImageView make_view(VkImage img, VkFormat f, uint32_t levels) {
    VkImageView v = VK_NULL_HANDLE;
    VkImageViewCreateInfo ci = {};
    ci.sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
    ci.image = img;
    ci.viewType = VK_IMAGE_VIEW_TYPE_2D;
    ci.format = f;
    ci.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, levels, 0, 1};
    if (g_vk.CreateImageView(g_dev, &ci, nullptr, &v) != VK_SUCCESS) return VK_NULL_HANDLE;
    return v;
}

bool make_buffer(VkDeviceSize size, VkBufferUsageFlags usage, VkBuffer &b, VkDeviceMemory &m) {
    VkBufferCreateInfo bi = {};
    bi.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
    bi.size = size;
    bi.usage = usage;
    bi.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    if (g_vk.CreateBuffer(g_dev, &bi, nullptr, &b) != VK_SUCCESS) return false;
    VkMemoryRequirements rq;
    g_vk.GetBufferMemoryRequirements(g_dev, b, &rq);
    int idx = memtype(rq.memoryTypeBits, VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
    if (idx < 0) return false;
    VkMemoryAllocateInfo mi = {};
    mi.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    mi.allocationSize = rq.size;
    mi.memoryTypeIndex = (uint32_t)idx;
    if (vkmb_allocate(g_dev, &mi, &m) != VK_SUCCESS) return false;
    g_vk.BindBufferMemory(g_dev, b, m, 0);
    return true;
}

void destroy_fx(Fx *f) {
    if (!f) return;
    if (g_dev && g_vk.DeviceWaitIdle) g_vk.DeviceWaitIdle(g_dev);
    std::set<VkRenderPass> rps; /* the back-buffer passes share one render pass */
    for (auto &p : f->ps) {
        if (p.pipe) g_vk.DestroyPipeline(g_dev, p.pipe, nullptr);
        if (p.fb) g_vk.DestroyFramebuffer(g_dev, p.fb, nullptr);
        for (auto &kv : p.bbfb) if (kv.second) g_vk.DestroyFramebuffer(g_dev, kv.second, nullptr);
        if (p.rp) rps.insert(p.rp);
    }
    for (VkRenderPass rp : rps) g_vk.DestroyRenderPass(g_dev, rp, nullptr);
    if (f->pool) g_vk.DestroyDescriptorPool(g_dev, f->pool, nullptr);
    if (f->pl) g_vk.DestroyPipelineLayout(g_dev, f->pl, nullptr);
    if (f->dslU) g_vk.DestroyDescriptorSetLayout(g_dev, f->dslU, nullptr);
    if (f->dslS) g_vk.DestroyDescriptorSetLayout(g_dev, f->dslS, nullptr);
    for (VkSampler s : f->samplers) g_vk.DestroySampler(g_dev, s, nullptr);
    if (f->ubomap) g_vk.UnmapMemory(g_dev, f->ubomem);
    if (f->ubo) g_vk.DestroyBuffer(g_dev, f->ubo, nullptr);
    if (f->ubomem) vkmb_free(g_dev, f->ubomem);
    for (auto &t : f->tex) {
        if (t.sview) g_vk.DestroyImageView(g_dev, t.sview, nullptr);
        if (t.rview && t.rview != t.view) g_vk.DestroyImageView(g_dev, t.rview, nullptr);
        if (t.view) g_vk.DestroyImageView(g_dev, t.view, nullptr);
        if (t.img) g_vk.DestroyImage(g_dev, t.img, nullptr);
        if (t.mem) vkmb_free(g_dev, t.mem);
    }
    for (int i = 0; i < 2; i++) {
        if (f->bbview[i]) g_vk.DestroyImageView(g_dev, f->bbview[i], nullptr);
        if (f->bbimg[i]) g_vk.DestroyImage(g_dev, f->bbimg[i], nullptr);
        if (f->bbmem[i]) vkmb_free(g_dev, f->bbmem[i]);
    }
    for (auto &s : f->staging) {
        if (s.buf) g_vk.DestroyBuffer(g_dev, s.buf, nullptr);
        if (s.mem) vkmb_free(g_dev, s.mem);
    }
    if (f->sm) g_vk.DestroyShaderModule(g_dev, f->sm, nullptr);
    delete f;
}

const reshadefx::annotation *find_ann(const std::vector<reshadefx::annotation> &a, const char *name) {
    for (const auto &x : a) if (x.name == name) return &x;
    return nullptr;
}

/* Source texture pixels for `fmt`: 1/2/4 channels of 8 bits, resized to w x h (bilinear). */
bool load_pixels(const Job &job, const std::string &file, VkFormat fmt, uint32_t w, uint32_t h, std::vector<uint8_t> &out) {
    int ch = fmt == VK_FORMAT_R8_UNORM ? 1 : fmt == VK_FORMAT_R8G8_UNORM ? 2 : 4;
    if (fmt != VK_FORMAT_R8_UNORM && fmt != VK_FORMAT_R8G8_UNORM && fmt != VK_FORMAT_R8G8B8A8_UNORM) return false;
    std::string path = job.tex.empty() ? file : job.tex + "/" + file;
    int iw = 0, ih = 0, n = 0;
    stbi_uc *px = stbi_load(path.c_str(), &iw, &ih, &n, 4);
    if (!px) px = stbi_load((dir_name(job.path) + "/" + file).c_str(), &iw, &ih, &n, 4);
    if (!px) return false;
    out.assign((size_t)w * h * ch, 0);
    for (uint32_t y = 0; y < h; y++) {
        float fy = h > 1 ? (float)y * (ih - 1) / (float)(h - 1) : 0.0f;
        int y0 = (int)fy, y1 = std::min(y0 + 1, ih - 1);
        float ty = fy - y0;
        for (uint32_t x = 0; x < w; x++) {
            float fx = w > 1 ? (float)x * (iw - 1) / (float)(w - 1) : 0.0f;
            int x0 = (int)fx, x1 = std::min(x0 + 1, iw - 1);
            float tx = fx - x0;
            for (int k = 0; k < ch; k++) {
                float a = px[((size_t)y0 * iw + x0) * 4 + k], b = px[((size_t)y0 * iw + x1) * 4 + k];
                float c = px[((size_t)y1 * iw + x0) * 4 + k], d = px[((size_t)y1 * iw + x1) * 4 + k];
                float v = (a * (1 - tx) + b * tx) * (1 - ty) + (c * (1 - tx) + d * tx) * ty;
                out[((size_t)y * w + x) * ch + k] = (uint8_t)(v + 0.5f);
            }
        }
    }
    stbi_image_free(px);
    return true;
}

VkDescriptorSet alloc_set(Fx *f, VkDescriptorSetLayout l) {
    VkDescriptorSet s = VK_NULL_HANDLE;
    VkDescriptorSetAllocateInfo ai = {};
    ai.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
    ai.descriptorPool = f->pool;
    ai.descriptorSetCount = 1;
    ai.pSetLayouts = &l;
    if (g_vk.AllocateDescriptorSets(g_dev, &ai, &s) != VK_SUCCESS) return VK_NULL_HANDLE;
    return s;
}

/* All samplers of one descriptor set; the chain-image samplers look at `bb`. */
void write_samplers(Fx *f, VkDescriptorSet set, VkImageView bb) {
    size_t n = f->samplers.size();
    std::vector<VkDescriptorImageInfo> ii(n);
    std::vector<VkWriteDescriptorSet> wr(n);
    for (size_t i = 0; i < n; i++) {
        VkImageView v = bb;
        if (f->samp_tex[i] >= 0) {
            const Tex &t = f->tex[f->samp_tex[i]];
            v = f->c->mod.samplers[i].srgb && t.sview ? t.sview : t.view;
        }
        ii[i] = {f->samplers[i], v, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL};
        wr[i] = {};
        wr[i].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
        wr[i].dstSet = set;
        wr[i].dstBinding = (uint32_t)i;
        wr[i].descriptorCount = 1;
        wr[i].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
        wr[i].pImageInfo = &ii[i];
    }
    if (n) g_vk.UpdateDescriptorSets(g_dev, (uint32_t)n, wr.data(), 0, nullptr);
}

VkRenderPass make_pass(const std::vector<VkFormat> &fmts, VkAttachmentLoadOp load, VkImageLayout initial) {
    std::vector<VkAttachmentDescription> att(fmts.size());
    std::vector<VkAttachmentReference> ref(fmts.size());
    for (size_t i = 0; i < fmts.size(); i++) {
        att[i] = {};
        att[i].format = fmts[i];
        att[i].samples = VK_SAMPLE_COUNT_1_BIT;
        att[i].loadOp = load;
        att[i].storeOp = VK_ATTACHMENT_STORE_OP_STORE;
        att[i].stencilLoadOp = VK_ATTACHMENT_LOAD_OP_DONT_CARE;
        att[i].stencilStoreOp = VK_ATTACHMENT_STORE_OP_DONT_CARE;
        att[i].initialLayout = initial;
        att[i].finalLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
        ref[i] = {(uint32_t)i, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL};
    }
    VkSubpassDescription sub = {};
    sub.pipelineBindPoint = VK_PIPELINE_BIND_POINT_GRAPHICS;
    sub.colorAttachmentCount = (uint32_t)ref.size();
    sub.pColorAttachments = ref.data();
    VkSubpassDependency deps[2] = {};
    deps[0].srcSubpass = VK_SUBPASS_EXTERNAL;
    deps[0].dstSubpass = 0;
    deps[0].srcStageMask = VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT | VK_PIPELINE_STAGE_TRANSFER_BIT | VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
    deps[0].dstStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
    deps[0].srcAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_TRANSFER_READ_BIT | VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
    deps[0].dstAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT | VK_ACCESS_COLOR_ATTACHMENT_READ_BIT;
    deps[1].srcSubpass = 0;
    deps[1].dstSubpass = VK_SUBPASS_EXTERNAL;
    deps[1].srcStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
    deps[1].dstStageMask = VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT | VK_PIPELINE_STAGE_TRANSFER_BIT;
    deps[1].srcAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
    deps[1].dstAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_TRANSFER_READ_BIT;
    VkRenderPassCreateInfo ci = {};
    ci.sType = VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO;
    ci.attachmentCount = (uint32_t)att.size();
    ci.pAttachments = att.data();
    ci.subpassCount = 1;
    ci.pSubpasses = &sub;
    ci.dependencyCount = 2;
    ci.pDependencies = deps;
    VkRenderPass rp = VK_NULL_HANDLE;
    if (g_vk.CreateRenderPass(g_dev, &ci, nullptr, &rp) != VK_SUCCESS) return VK_NULL_HANDLE;
    return rp;
}

VkFramebuffer make_fb(VkRenderPass rp, const std::vector<VkImageView> &views, uint32_t w, uint32_t h) {
    VkFramebufferCreateInfo fi = {};
    fi.sType = VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO;
    fi.renderPass = rp;
    fi.attachmentCount = (uint32_t)views.size();
    fi.pAttachments = views.data();
    fi.width = w;
    fi.height = h;
    fi.layers = 1;
    VkFramebuffer fb = VK_NULL_HANDLE;
    if (g_vk.CreateFramebuffer(g_dev, &fi, nullptr, &fb) != VK_SUCCESS) return VK_NULL_HANDLE;
    return fb;
}

/* Builds every Vulkan object for the compiled module. nullptr + `why` on failure. */
Fx *build_fx(const std::shared_ptr<Compiled> &c, std::string &why) {
    Fx *f = new Fx();
    f->c = c;
    f->gen = c->job.gen;
    f->w = c->job.w;
    f->h = c->job.h;
    f->fmt = c->job.fmt;
    f->t0 = now_ms();
    const reshadefx::module &m = c->mod;
#define FAIL(msg) do { why = msg; destroy_fx(f); return nullptr; } while (0)

    /* techniques: those not marked enabled=false, else the first */
    for (const auto &t : m.techniques) {
        const reshadefx::annotation *en = find_ann(t.annotations, "enabled");
        if (en && en->value.as_int[0] == 0) continue;
        for (const auto &p : t.passes) f->list.push_back(&p);
    }
    if (f->list.empty()) for (const auto &p : m.techniques[0].passes) f->list.push_back(&p);
    for (auto *p : f->list) if (p->render_target_names[0].empty()) f->nbb++;
    if (f->nbb == 0) FAIL("the shader never writes the screen");

    VkShaderModuleCreateInfo smi = {};
    smi.sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO;
    smi.codeSize = m.spirv.size() * sizeof(uint32_t);
    smi.pCode = m.spirv.data();
    if (g_vk.CreateShaderModule(g_dev, &smi, nullptr, &f->sm) != VK_SUCCESS) FAIL("vkCreateShaderModule failed");

    /* textures */
    f->tex.resize(m.textures.size());
    std::map<std::string, int> tindex;
    for (size_t i = 0; i < m.textures.size(); i++) {
        const auto &ti = m.textures[i];
        Tex &t = f->tex[i];
        t.name = ti.unique_name;
        tindex[ti.unique_name] = (int)i;
        if (ti.semantic == "COLOR" || ti.semantic == "DEPTH") { t.semantic = true; continue; }
        t.fmt = conv_format(ti.format);
        t.w = std::max(1u, ti.width);
        t.h = std::max(1u, ti.height);
        t.levels = std::max(1u, ti.levels);
        if (t.fmt == VK_FORMAT_UNDEFINED) FAIL("texture " + ti.unique_name + ": unsupported format");
        const reshadefx::annotation *src = find_ann(ti.annotations, "source");
        t.source = src != nullptr;
        if (t.source) t.levels = 1;
        VkImageUsageFlags use = t.source ? (VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT)
                                         : (VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT |
                                            VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT);
        if (!make_image(t, use, t.fmt == VK_FORMAT_R8G8B8A8_UNORM)) FAIL("texture " + ti.unique_name + ": out of memory");
        t.view = make_view(t.img, t.fmt, t.levels);
        t.rview = t.levels > 1 ? make_view(t.img, t.fmt, 1) : t.view;
        if (t.fmt == VK_FORMAT_R8G8B8A8_UNORM) t.sview = make_view(t.img, VK_FORMAT_R8G8B8A8_SRGB, t.levels);
        if (!t.view || !t.rview) FAIL("texture " + ti.unique_name + ": vkCreateImageView failed");
        if (t.source) {
            std::vector<uint8_t> px;
            if (!load_pixels(c->job, src->value.string_data, t.fmt, t.w, t.h, px)) {
                droiddeck_log("effects", "reshade: texture file %s not loaded (format %d); the texture stays black",
                              src->value.string_data.c_str(), (int)t.fmt);
                px.assign((size_t)t.w * t.h * (t.fmt == VK_FORMAT_R8_UNORM ? 1 : t.fmt == VK_FORMAT_R8G8_UNORM ? 2 : 4), 0);
                if (t.fmt != VK_FORMAT_R8_UNORM && t.fmt != VK_FORMAT_R8G8_UNORM && t.fmt != VK_FORMAT_R8G8B8A8_UNORM)
                    px.assign((size_t)t.w * t.h * 16, 0); /* enough zeros for any other format */
            }
            Staging st;
            st.tex = (int)i; st.w = t.w; st.h = t.h;
            if (!make_buffer(px.size(), VK_BUFFER_USAGE_TRANSFER_SRC_BIT, st.buf, st.mem)) { f->staging.push_back(st); FAIL("staging buffer failed"); }
            void *map = nullptr;
            g_vk.MapMemory(g_dev, st.mem, 0, px.size(), 0, &map);
            memcpy(map, px.data(), px.size());
            g_vk.UnmapMemory(g_dev, st.mem);
            f->staging.push_back(st);
        }
    }

    /* samplers */
    for (size_t i = 0; i < m.samplers.size(); i++) {
        const auto &si = m.samplers[i];
        VkSamplerCreateInfo sc = {};
        sc.sType = VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO;
        using Fl = reshadefx::texture_filter;
        uint32_t fl = (uint32_t)si.filter;
        sc.minFilter = (fl & 0x10) ? VK_FILTER_LINEAR : VK_FILTER_NEAREST;
        sc.magFilter = (fl & 0x04) ? VK_FILTER_LINEAR : VK_FILTER_NEAREST;
        sc.mipmapMode = (fl & 0x01) ? VK_SAMPLER_MIPMAP_MODE_LINEAR : VK_SAMPLER_MIPMAP_MODE_NEAREST;
        (void)sizeof(Fl);
        sc.addressModeU = conv_addr(si.address_u);
        sc.addressModeV = conv_addr(si.address_v);
        sc.addressModeW = conv_addr(si.address_w);
        sc.mipLodBias = si.lod_bias;
        sc.minLod = si.min_lod;
        sc.maxLod = si.max_lod;
        sc.maxAnisotropy = 1.0f;
        sc.compareOp = VK_COMPARE_OP_ALWAYS;
        sc.borderColor = VK_BORDER_COLOR_FLOAT_TRANSPARENT_BLACK;
        VkSampler s = VK_NULL_HANDLE;
        if (g_vk.CreateSampler(g_dev, &sc, nullptr, &s) != VK_SUCCESS) FAIL("vkCreateSampler failed");
        f->samplers.push_back(s);
        auto it = tindex.find(si.texture_name);
        f->samp_tex.push_back(it == tindex.end() || f->tex[it->second].semantic ? -1 : it->second);
    }
    const uint32_t S = (uint32_t)f->samplers.size();

    /* descriptor layouts: set 0 = uniform buffer, set 1 = samplers (binding i = sampler i) */
    VkDescriptorSetLayoutBinding ub = {0, VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, 1, VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT, nullptr};
    VkDescriptorSetLayoutCreateInfo dl = {};
    dl.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO;
    dl.bindingCount = 1;
    dl.pBindings = &ub;
    if (g_vk.CreateDescriptorSetLayout(g_dev, &dl, nullptr, &f->dslU) != VK_SUCCESS) FAIL("descriptor layout failed");
    std::vector<VkDescriptorSetLayoutBinding> sb(S);
    for (uint32_t i = 0; i < S; i++) sb[i] = {i, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 1, VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT, nullptr};
    dl.bindingCount = S;
    dl.pBindings = S ? sb.data() : nullptr;
    if (g_vk.CreateDescriptorSetLayout(g_dev, &dl, nullptr, &f->dslS) != VK_SUCCESS) FAIL("descriptor layout failed");
    VkDescriptorSetLayout layouts[2] = {f->dslU, f->dslS};
    VkPipelineLayoutCreateInfo pli = {};
    pli.sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO;
    pli.setLayoutCount = 2;
    pli.pSetLayouts = layouts;
    if (g_vk.CreatePipelineLayout(g_dev, &pli, nullptr, &f->pl) != VK_SUCCESS) FAIL("pipeline layout failed");

    VkDescriptorPoolSize psz[2] = {{VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, 1}, {VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, std::max(1u, S * 6)}};
    VkDescriptorPoolCreateInfo dp = {};
    dp.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO;
    dp.flags = VK_DESCRIPTOR_POOL_CREATE_FREE_DESCRIPTOR_SET_BIT;
    dp.maxSets = 7;
    dp.poolSizeCount = 2;
    dp.pPoolSizes = psz;
    if (g_vk.CreateDescriptorPool(g_dev, &dp, nullptr, &f->pool) != VK_SUCCESS) FAIL("descriptor pool failed");

    /* uniform buffer for the `source` uniforms (min size so the set is always valid) */
    f->ubosize = std::max(16u, (m.total_uniform_size + 15u) & ~15u);
    if (!make_buffer(f->ubosize, VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT, f->ubo, f->ubomem)) FAIL("uniform buffer failed");
    g_vk.MapMemory(g_dev, f->ubomem, 0, f->ubosize, 0, &f->ubomap);
    memset(f->ubomap, 0, f->ubosize);
    for (const auto &u : m.uniforms) { /* uniforms that stayed in the buffer: declared defaults */
        if (find_ann(u.annotations, "source") || !u.has_initializer_value || u.offset + 4 > f->ubosize) continue;
        size_t bytes = std::min<size_t>(std::min<size_t>(u.size, 64), f->ubosize - u.offset);
        memcpy((char *)f->ubomap + u.offset, u.initializer_value.as_float, bytes);
    }
    f->uboSet = alloc_set(f, f->dslU);
    if (!f->uboSet) FAIL("descriptor set failed");
    VkDescriptorBufferInfo bi = {f->ubo, 0, f->ubosize};
    VkWriteDescriptorSet uw = {};
    uw.sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    uw.dstSet = f->uboSet;
    uw.descriptorCount = 1;
    uw.descriptorType = VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER;
    uw.pBufferInfo = &bi;
    g_vk.UpdateDescriptorSets(g_dev, 1, &uw, 0, nullptr);

    /* intermediate back buffers (only when the shader writes the screen more than once) */
    for (int i = 0; i < 2 && f->nbb > i + 1; i++) {
        Tex t;
        t.fmt = f->fmt; t.w = (uint32_t)f->w; t.h = (uint32_t)f->h;
        if (!make_image(t, VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT, false)) FAIL("back buffer out of memory");
        f->bbimg[i] = t.img; f->bbmem[i] = t.mem;
        f->bbview[i] = make_view(t.img, f->fmt, 1);
        if (!f->bbview[i]) FAIL("back buffer view failed");
        f->bbset[i] = alloc_set(f, f->dslS);
        if (S && !f->bbset[i]) FAIL("descriptor set failed");
        if (S) write_samplers(f, f->bbset[i], f->bbview[i]);
    }

    /* passes */
    f->ps.resize(f->list.size());
    VkRenderPass bb_rp = VK_NULL_HANDLE;
    for (size_t i = 0; i < f->list.size(); i++) {
        PassState &ps = f->ps[i];
        const reshadefx::pass_info &pi = *f->list[i];
        ps.info = &pi;
        ps.bb = pi.render_target_names[0].empty();
        std::vector<VkFormat> fmts;
        std::vector<VkImageView> views;
        if (ps.bb) {
            if (!bb_rp) bb_rp = make_pass({f->fmt}, VK_ATTACHMENT_LOAD_OP_DONT_CARE, VK_IMAGE_LAYOUT_UNDEFINED);
            ps.rp = bb_rp;
            fmts = {f->fmt};
            ps.w = (uint32_t)f->w; ps.h = (uint32_t)f->h;
        } else {
            for (int k = 0; k < 8 && !pi.render_target_names[k].empty(); k++) {
                auto it = tindex.find(pi.render_target_names[k]);
                if (it == tindex.end() || f->tex[it->second].semantic || f->tex[it->second].source) FAIL("bad render target " + pi.render_target_names[k]);
                const Tex &t = f->tex[it->second];
                ps.rts.push_back(it->second);
                fmts.push_back(t.fmt);
                views.push_back(t.rview);
                ps.w = t.w; ps.h = t.h;
            }
            VkAttachmentLoadOp load = pi.clear_render_targets ? VK_ATTACHMENT_LOAD_OP_CLEAR
                                      : pi.blend_enable ? VK_ATTACHMENT_LOAD_OP_LOAD : VK_ATTACHMENT_LOAD_OP_DONT_CARE;
            ps.rp = make_pass(fmts, load, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
            if (ps.rp) ps.fb = make_fb(ps.rp, views, ps.w, ps.h);
            if (!ps.fb) FAIL("framebuffer failed");
        }
        if (!ps.rp) FAIL("render pass failed");
        if (pi.viewport_width && pi.viewport_height && !ps.bb) { ps.w = pi.viewport_width; ps.h = pi.viewport_height; }

        VkPipelineShaderStageCreateInfo st[2] = {};
        st[0].sType = st[1].sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
        st[0].stage = VK_SHADER_STAGE_VERTEX_BIT;
        st[0].module = f->sm;
        st[0].pName = pi.vs_entry_point.c_str();
        st[1].stage = VK_SHADER_STAGE_FRAGMENT_BIT;
        st[1].module = f->sm;
        st[1].pName = pi.ps_entry_point.c_str();
        VkPipelineVertexInputStateCreateInfo vi = {VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO};
        VkPipelineInputAssemblyStateCreateInfo ia = {VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO};
        ia.topology = conv_topology(pi.topology);
        VkPipelineViewportStateCreateInfo vp = {VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO};
        vp.viewportCount = vp.scissorCount = 1;
        VkPipelineRasterizationStateCreateInfo rs = {VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO};
        rs.polygonMode = VK_POLYGON_MODE_FILL;
        rs.cullMode = VK_CULL_MODE_NONE;
        rs.frontFace = VK_FRONT_FACE_COUNTER_CLOCKWISE;
        rs.lineWidth = 1.0f;
        VkPipelineMultisampleStateCreateInfo ms = {VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO};
        ms.rasterizationSamples = VK_SAMPLE_COUNT_1_BIT;
        std::vector<VkPipelineColorBlendAttachmentState> bl(fmts.size());
        for (auto &b : bl) {
            b = {};
            b.blendEnable = (!ps.bb && pi.blend_enable) ? VK_TRUE : VK_FALSE;
            b.srcColorBlendFactor = conv_factor(pi.src_blend);
            b.dstColorBlendFactor = conv_factor(pi.dest_blend);
            b.colorBlendOp = conv_op(pi.blend_op);
            b.srcAlphaBlendFactor = conv_factor(pi.src_blend_alpha);
            b.dstAlphaBlendFactor = conv_factor(pi.dest_blend_alpha);
            b.alphaBlendOp = conv_op(pi.blend_op_alpha);
            b.colorWriteMask = pi.color_write_mask;
        }
        VkPipelineColorBlendStateCreateInfo cb = {VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO};
        cb.attachmentCount = (uint32_t)bl.size();
        cb.pAttachments = bl.data();
        VkDynamicState dyn[2] = {VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR};
        VkPipelineDynamicStateCreateInfo dy = {VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO};
        dy.dynamicStateCount = 2;
        dy.pDynamicStates = dyn;
        VkGraphicsPipelineCreateInfo gp = {};
        gp.sType = VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO;
        gp.stageCount = 2;
        gp.pStages = st;
        gp.pVertexInputState = &vi;
        gp.pInputAssemblyState = &ia;
        gp.pViewportState = &vp;
        gp.pRasterizationState = &rs;
        gp.pMultisampleState = &ms;
        gp.pColorBlendState = &cb;
        gp.pDynamicState = &dy;
        gp.layout = f->pl;
        gp.renderPass = ps.rp;
        if (g_vk.CreateGraphicsPipelines(g_dev, VK_NULL_HANDLE, 1, &gp, nullptr, &ps.pipe) != VK_SUCCESS)
            FAIL("pipeline " + pi.ps_entry_point + " rejected by the driver");
    }
#undef FAIL
    return f;
}

/* ------------------------------------------------------------------ per frame */

void barrier(VkCommandBuffer cmd, VkImage img, uint32_t levels, VkImageLayout from, VkImageLayout to,
             VkPipelineStageFlags ss, VkAccessFlags sa, VkPipelineStageFlags ds, VkAccessFlags da) {
    VkImageMemoryBarrier b = {};
    b.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
    b.oldLayout = from; b.newLayout = to;
    b.srcQueueFamilyIndex = b.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    b.image = img;
    b.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, levels, 0, 1};
    b.srcAccessMask = sa; b.dstAccessMask = da;
    g_vk.CmdPipelineBarrier(cmd, ss, ds, 0, 0, nullptr, 0, nullptr, 1, &b);
}

void gen_mips(VkCommandBuffer cmd, const Tex &t) {
    if (t.levels < 2) return;
    int32_t mw = (int32_t)t.w, mh = (int32_t)t.h;
    auto sub = [&](uint32_t level, VkImageLayout from, VkImageLayout to, VkAccessFlags sa, VkAccessFlags da, VkPipelineStageFlags ss, VkPipelineStageFlags ds) {
        VkImageMemoryBarrier b = {};
        b.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
        b.oldLayout = from; b.newLayout = to;
        b.srcQueueFamilyIndex = b.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        b.image = t.img;
        b.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, level, 1, 0, 1};
        b.srcAccessMask = sa; b.dstAccessMask = da;
        g_vk.CmdPipelineBarrier(cmd, ss, ds, 0, 0, nullptr, 0, nullptr, 1, &b);
    };
    const VkPipelineStageFlags FS = VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT | VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT, TR = VK_PIPELINE_STAGE_TRANSFER_BIT;
    sub(0, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT, VK_ACCESS_TRANSFER_READ_BIT, FS, TR);
    for (uint32_t i = 1; i < t.levels; i++) {
        sub(i, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_ACCESS_SHADER_READ_BIT, VK_ACCESS_TRANSFER_WRITE_BIT, FS, TR);
        VkImageBlit bl = {};
        bl.srcSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, i - 1, 0, 1};
        bl.srcOffsets[1] = {mw, mh, 1};
        int32_t nw = std::max(1, mw / 2), nh = std::max(1, mh / 2);
        bl.dstSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, i, 0, 1};
        bl.dstOffsets[1] = {nw, nh, 1};
        g_vk.CmdBlitImage(cmd, t.img, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, t.img, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &bl, VK_FILTER_LINEAR);
        sub(i, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_TRANSFER_READ_BIT, TR, TR);
        mw = nw; mh = nh;
    }
    for (uint32_t i = 0; i < t.levels; i++)
        sub(i, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_ACCESS_TRANSFER_READ_BIT, VK_ACCESS_SHADER_READ_BIT, TR, FS);
}

void put_uniform(Fx *f, const reshadefx::uniform_info &u, const float *v, int n) {
    if (u.offset + 4 > f->ubosize) return;
    int comps = std::max(1, (int)u.type.rows * (int)u.type.cols);
    comps = std::min(comps, n > 0 ? 16 : 1);
    char *dst = (char *)f->ubomap + u.offset;
    for (int i = 0; i < comps && u.offset + (uint32_t)(i + 1) * 4 <= f->ubosize; i++) {
        float x = i < n ? v[i] : 0.0f;
        switch (u.type.base) {
        case reshadefx::type::t_int: { int32_t k = (int32_t)x; memcpy(dst + i * 4, &k, 4); break; }
        case reshadefx::type::t_uint: { uint32_t k = (uint32_t)x; memcpy(dst + i * 4, &k, 4); break; }
        case reshadefx::type::t_bool: { uint32_t k = x != 0.0f; memcpy(dst + i * 4, &k, 4); break; }
        default: memcpy(dst + i * 4, &x, 4); break;
        }
    }
}

void update_uniforms(Fx *f) {
    static double last = 0.0;
    double t = now_ms();
    float dt = last > 0.0 ? (float)(t - last) : 16.7f;
    last = t;
    float timer = (float)(t - f->t0);
    for (const auto &u : f->c->mod.uniforms) {
        const reshadefx::annotation *s = find_ann(u.annotations, "source");
        if (!s) continue;
        const std::string &k = s->value.string_data;
        float v[4] = {0, 0, 0, 0};
        int n = 1;
        if (k == "frametime") v[0] = dt;
        else if (k == "framecount") v[0] = (float)f->frame;
        else if (k == "timer") v[0] = timer;
        else if (k == "date") {
            time_t now = time(nullptr);
            tm lt;
            localtime_r(&now, &lt);
            v[0] = (float)(lt.tm_year + 1900); v[1] = (float)(lt.tm_mon + 1); v[2] = (float)lt.tm_mday;
            v[3] = (float)(lt.tm_hour * 3600 + lt.tm_min * 60 + lt.tm_sec);
            n = 4;
        } else if (k == "pingpong") {
            const reshadefx::annotation *mn = find_ann(u.annotations, "min"), *mx = find_ann(u.annotations, "max"), *sp = find_ann(u.annotations, "step");
            float lo = mn ? mn->value.as_float[0] : 0.0f, hi = mx ? mx->value.as_float[0] : 1.0f;
            float step = sp ? sp->value.as_float[0] : 1.0f;
            float range = std::max(hi - lo, 1e-6f), ph = std::fmod(timer / 1000.0f * std::max(step, 0.001f), 2.0f * range);
            v[0] = lo + (ph < range ? ph : 2.0f * range - ph);
            v[1] = ph < range ? 1.0f : -1.0f;
            n = 2;
        } else if (k == "random") {
            const reshadefx::annotation *mn = find_ann(u.annotations, "min"), *mx = find_ann(u.annotations, "max");
            float lo = mn ? mn->value.as_float[0] : 0.0f, hi = mx ? mx->value.as_float[0] : 1.0f;
            v[0] = lo + (hi - lo) * (float)(rand() & 0xFFFF) / 65535.0f;
        } else { n = 4; } /* mousepoint, mousebutton, key, overlay_open, bufready_depth ...: zero */
        put_uniform(f, u, v, n);
    }
}

VkDescriptorSet src_set(Fx *f, VkImageView src) {
    for (int i = 0; i < f->nsrc; i++) if (f->srcsets[i].view == src) return f->srcsets[i].set;
    int slot;
    if (f->nsrc < 4) {
        slot = f->nsrc++;
        f->srcsets[slot].set = alloc_set(f, f->dslS);
    } else {
        slot = f->rr++ & 3; /* evict: frames are fenced, so the old set is idle */
    }
    f->srcsets[slot].view = src;
    if (f->srcsets[slot].set) write_samplers(f, f->srcsets[slot].set, src);
    return f->srcsets[slot].set;
}

} // namespace

/* ------------------------------------------------------------------ C API */

extern "C" {

void vkp_reshade_set_shader(const char *fx_path, const char *include_dir, const char *texture_dir) {
    std::lock_guard<std::mutex> lk(g_mu);
    std::string p = fx_path ? fx_path : "";
    g_req.path = p;
    g_req.inc = include_dir ? include_dir : "";
    g_req.tex = texture_dir ? texture_dir : "";
    g_req.gen++;
    g_done.reset();
    if (p.empty()) set_status("off");
    else set_status("compiling %s", base_name(p).c_str());
    vkp_effects_poke();
}

void vkp_reshade_status(char *out, int cap) {
    if (!out || cap <= 0) return;
    std::lock_guard<std::mutex> lk(g_mu);
    snprintf(out, (size_t)cap, "%s", g_status);
}

int vkp_reshade_wanted(void) {
    std::lock_guard<std::mutex> lk(g_mu);
    return !g_req.path.empty();
}

void vkp_reshade_bind_device(VkDevice dev, VkPhysicalDevice pd, const VkPhysicalDeviceMemoryProperties *memprops) {
    (void)pd;
    g_dev = dev;
    g_mp = *memprops;
}

void vkp_reshade_destroy(void) {
    if (g_fx) { destroy_fx(g_fx); g_fx = nullptr; }
    std::lock_guard<std::mutex> lk(g_mu);
    g_done.reset();
}

int vkp_reshade_prepare(int w, int h, VkFormat fmt) {
    if (!g_dev) return 0;
    std::shared_ptr<Compiled> adopt;
    Job start;
    bool do_start = false;
    {
        std::lock_guard<std::mutex> lk(g_mu);
        if (g_req.path.empty()) {
            if (g_fx) { destroy_fx(g_fx); g_fx = nullptr; }
            return 0;
        }
        if (g_done && g_done->job.gen == g_req.gen && g_done->job.w == w && g_done->job.h == h && g_done->job.fmt == fmt) {
            adopt = g_done;
            g_done.reset();
        } else if (!g_compiling && !(g_fx && g_fx->gen == g_req.gen && g_fx->w == w && g_fx->h == h && g_fx->fmt == fmt) &&
                   !(g_failed_gen == g_req.gen && g_failed_w == w && g_failed_h == h)) {
            start = g_req;
            start.w = w; start.h = h; start.fmt = fmt;
            g_compiling = true;
            do_start = true;
        }
    }
    if (adopt) {
        if (!adopt->ok) { /* compile error: keep what runs, the status line says why */
            std::lock_guard<std::mutex> lk(g_mu);
            g_failed_gen = adopt->job.gen; g_failed_w = w; g_failed_h = h;
        } else {
            std::string why;
            Fx *nf = build_fx(adopt, why);
            std::lock_guard<std::mutex> lk(g_mu);
            if (nf && nf->gen == g_req.gen) {
                if (g_fx) destroy_fx(g_fx);
                g_fx = nf;
                set_status("ok: %zu pass(es), %s", nf->list.size(), base_name(g_req.path).c_str());
                droiddeck_log("effects", "reshade: %s ready (%zu passes, %zu textures, %zu samplers)", base_name(g_req.path).c_str(),
                              nf->list.size(), nf->tex.size(), nf->samplers.size());
            } else {
                if (nf) destroy_fx(nf);
                if (!nf) {
                    g_failed_gen = adopt->job.gen; g_failed_w = w; g_failed_h = h;
                    set_status("error: %s", why.c_str());
                    droiddeck_log("effects", "reshade: build failed: %s", why.c_str());
                }
            }
        }
    }
    if (do_start) std::thread(worker, start).detach();
    return g_fx && g_fx->w == w && g_fx->h == h && g_fx->fmt == fmt ? 1 : 0;
}

void vkp_reshade_record(VkCommandBuffer cmd, VkImageView src_view, VkImageView dst_view, int w, int h) {
    Fx *f = g_fx;
    if (!f) return;
    if (f->first) {
        f->first = false;
        for (auto &t : f->tex) {
            if (t.semantic || t.source) continue;
            barrier(cmd, t.img, t.levels, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                    VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, 0, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, VK_ACCESS_SHADER_READ_BIT);
        }
        for (auto &s : f->staging) {
            Tex &t = f->tex[s.tex];
            barrier(cmd, t.img, 1, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                    VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, 0, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_WRITE_BIT);
            VkBufferImageCopy bc = {};
            bc.imageSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
            bc.imageExtent = {s.w, s.h, 1};
            g_vk.CmdCopyBufferToImage(cmd, s.buf, t.img, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &bc);
            barrier(cmd, t.img, 1, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                    VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_WRITE_BIT, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, VK_ACCESS_SHADER_READ_BIT);
        }
    }
    update_uniforms(f);
    f->frame++;
    const bool hasS = !f->samplers.empty();
    VkDescriptorSet srcSet = hasS ? src_set(f, src_view) : VK_NULL_HANDLE;
    g_vk.CmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, f->pl, 0, 1, &f->uboSet, 0, nullptr);
    int k = 0; /* back-buffer writes done so far: B0 = the chain image, B(odd) = bb[0], B(even>0) = bb[1] */
    for (size_t i = 0; i < f->ps.size(); i++) {
        PassState &ps = f->ps[i];
        if (hasS) {
            VkDescriptorSet cur = k == 0 ? srcSet : f->bbset[(k - 1) & 1];
            g_vk.CmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, f->pl, 1, 1, &cur, 0, nullptr);
        }
        VkFramebuffer fb = ps.fb;
        if (ps.bb) {
            VkImageView out = (k + 1 == f->nbb) ? dst_view : f->bbview[k & 1];
            auto it = ps.bbfb.find(out);
            if (it == ps.bbfb.end()) it = ps.bbfb.emplace(out, make_fb(ps.rp, {out}, (uint32_t)w, (uint32_t)h)).first;
            fb = it->second;
        }
        if (!fb) continue;
        uint32_t pw = ps.bb ? (uint32_t)w : ps.w, ph = ps.bb ? (uint32_t)h : ps.h;
        VkClearValue clear[8] = {};
        VkRenderPassBeginInfo rb = {};
        rb.sType = VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO;
        rb.renderPass = ps.rp;
        rb.framebuffer = fb;
        rb.renderArea = {{0, 0}, {pw, ph}};
        rb.clearValueCount = ps.bb ? 1u : (uint32_t)ps.rts.size();
        rb.pClearValues = clear;
        VkViewport vp = {0, 0, (float)pw, (float)ph, 0.0f, 1.0f};
        VkRect2D sc = {{0, 0}, {pw, ph}};
        g_vk.CmdBeginRenderPass(cmd, &rb, VK_SUBPASS_CONTENTS_INLINE);
        g_vk.CmdSetViewport(cmd, 0, 1, &vp);
        g_vk.CmdSetScissor(cmd, 0, 1, &sc);
        g_vk.CmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, ps.pipe);
        g_vk.CmdDraw(cmd, ps.info->num_vertices, 1, 0, 0);
        g_vk.CmdEndRenderPass(cmd);
        if (ps.bb) k++;
        for (int ti : ps.rts) gen_mips(cmd, f->tex[ti]);
    }
}

} // extern "C"
