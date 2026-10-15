package com.example.tiny_computer.core

/**
 * Konfigurasi DXVK untuk Wine Hangover (apt): katalog opsi `dxvk.conf` (DXVK upstream, plus opsi build gplasync),
 * penyimpanan nilai buatan pengguna, dan pembuat variabel lingkungan `DXVK_CONFIG`.
 *
 * `DXVK_CONFIG="kunci = nilai; kunci = nilai"` (pemisah `;`) memakai sintaks yang sama dengan dxvk.conf dan berlaku untuk
 * DXVK 2.4 ke atas. [Launcher.guestSpec] memasangnya ke `EXTRA_OPT` proot, yang tidak diberi kutip: karena itu nilai
 * dibersihkan dari spasi dan karakter khusus ([sanitize]) dan ditulis tanpa spasi (`kunci=nilai`).
 *
 * Hanya nilai yang berbeda dari bawaan DXVK yang disimpan ([Cfg.dxvkConfig]); bila "Memory-saving defaults" ([Cfg.dxvkTune])
 * aktif, [TUNE] menjadi dasar di bawah nilai pengguna.
 */
object DxvkConfig {
    enum class Kind { SWITCH, CHOICE, TEXT, NUMBER }

    /**
     * Satu pengaturan. [keys] ditulis bersamaan (mis. `dxgi.*` dan `d3d9.*` yang kembar). [def] = nilai bawaan DXVK
     * (kosong = bergantung pada build). SWITCH memakai nilai `True`/`False`.
     */
    class Opt(
        val keys: List<String>,
        val kind: Kind,
        val def: String,
        val label: String,
        val hint: String,
        val choices: List<Pair<String, String>> = emptyList(),
    )

    class Section(val id: String, val title: String, val note: String?, val opts: List<Opt>)

    class Present(val id: String, val label: String, val settings: Map<String, String>)

    /**
     * Dasar hemat memori hasil uji di Adreno 610 (tanpanya DXVK di Turnip gagal membuat pipeline internal,
     * VK_ERROR_OUT_OF_HOST_MEMORY, dan layar hitam). Dipakai bila [Cfg.dxvkTune] aktif; nilai pengguna menimpanya.
     */
    val TUNE: Map<String, String> = linkedMapOf(
        "dxgi.maxDeviceMemory" to "512",
        "dxgi.maxSharedMemory" to "512",
        "dxvk.maxChunkSize" to "16",
        "dxvk.numCompilerThreads" to "1",
        "dxvk.enableGraphicsPipelineLibrary" to "False",
        "dxvk.enableStateCache" to "False",
    )

    // ------------------------------------------------------------------ katalog

    private fun sw(label: String, hint: String, keys: List<String>, def: Boolean = false) =
        Opt(keys, Kind.SWITCH, if (def) "True" else "False", label, hint)

    private fun pick(label: String, hint: String, keys: List<String>, def: String, choices: List<Pair<String, String>>) =
        Opt(keys, Kind.CHOICE, def, label, hint, choices)

    /** Auto / On / Off; [def] = nilai bawaan DXVK. */
    private fun tri(label: String, hint: String, keys: List<String>, def: String = "Auto") =
        pick(label, hint, keys, def, listOf("Auto" to "Auto", "True" to "On", "False" to "Off"))

    /** Default (bergantung build) / On / Off, untuk opsi khusus build gplasync. */
    private fun triBuild(label: String, hint: String, keys: List<String>) =
        pick(label, hint, keys, "", listOf("" to "Default (build)", "True" to "On", "False" to "Off"))

    private fun num(label: String, hint: String, keys: List<String>, def: String) = Opt(keys, Kind.NUMBER, def, label, hint)

    private fun txt(label: String, hint: String, keys: List<String>) = Opt(keys, Kind.TEXT, "", label, hint)

    /** Daftar nilai; nilai [def] diberi label "Default". */
    private fun values(def: String, vararg v: String): List<Pair<String, String>> =
        v.map { it to (if (it == def) "Default ($it)" else it) }

    private fun both(a: String) = listOf("dxgi.$a", "d3d9.$a")
    private fun d(a: String) = listOf("d3d11.$a", "d3d9.$a")

    private val MEM_MB = listOf("0" to "Default (no override)", "256" to "256 MB", "512" to "512 MB", "1024" to "1024 MB",
        "2048" to "2048 MB", "3072" to "3072 MB", "4096" to "4096 MB", "6144" to "6144 MB", "8192" to "8192 MB")

    val SECTIONS: List<Section> = listOf(
        Section("frame", "Frame pacing & display", "Frame rate limiter, latency, refresh rate, and DXGI window/surface behaviour.", listOf(
            pick("Max frame latency", "dxgi/d3d9.maxFrameLatency: stricter maximum frame latency; overrides the game's setting. 0 = no effect.",
                both("maxFrameLatency"), "0", values("0", "0", "1", "2", "3", "4", "5", "6", "8", "16")),
            pick("Frame rate limit", "dxgi/d3d9/dxvk.maxFrameRate: 0 = default (display refresh rate when VSync mismatches), -1 = always off. External limiters are better.",
                listOf("dxgi.maxFrameRate", "d3d9.maxFrameRate", "dxvk.maxFrameRate"), "0",
                listOf("0" to "Default (0)", "-1" to "Always off (-1)") + listOf("20", "24", "30", "40", "45", "60", "72", "90", "120", "144", "165", "240").map { it to "$it FPS" }),
            tri("Latency sleep", "dxvk.latencySleep: built-in latency reduction / Reflex support. Auto only enables it for D3D11 Reflex games on drivers with VK_NV_low_latency2.", listOf("dxvk.latencySleep")),
            num("Latency tolerance (µs)", "dxvk.latencyTolerance: higher values increase latency but may improve frame pacing. No effect with NV_low_latency2.", listOf("dxvk.latencyTolerance"), "1000"),
            tri("Disable NV low latency 2", "dxvk.disableNvLowLatency2: stop using VK_NV_low_latency2 (Reflex unavailable). Auto = off in 32-bit apps.", listOf("dxvk.disableNvLowLatency2")),
            pick("Force refresh rate", "dxgi/d3d9.forceRefreshRate: only expose display modes with this refresh rate. Many games break at high values. 0 = off.",
                both("forceRefreshRate"), "0", listOf("0" to "Default (0)") + listOf("30", "48", "50", "60", "75", "90", "100", "120", "144", "165", "240").map { it to "$it Hz" }),
            sw("Defer surface creation", "dxgi/d3d9.deferSurfaceCreation: create the VkSurface on the first Present. Fixes black windows in games that start with another API.", both("deferSurfaceCreation")),
            sw("Expose HDR10 colour space", "dxgi.enableHDR: tell the game that Windows HDR mode is on. Defaults to the DXVK_HDR environment variable.", listOf("dxgi.enableHDR")),
            sw("Dummy composition swapchain", "dxgi.enableDummyCompositionSwapchain: expose dcomp swap chain support with a dummy window. Some games need it, others need it to fail.", listOf("dxgi.enableDummyCompositionSwapchain")),
            sw("Unreal Engine 4 HDR workarounds", "dxgi.enableUe4Workarounds: for UE4 games not named -Win64-Shipping.exe; avoids crashes from statically linked AMDAGS.", listOf("dxgi.enableUe4Workarounds")),
            sw("Allow exclusive fullscreen", "dxvk.allowFse: Windows only, no effect on Linux/Wine.", listOf("dxvk.allowFse")),
        )),
        Section("gpu", "GPU reporting & device", "What the game is told about the GPU. Text values cannot contain spaces here.", listOf(
            txt("Custom vendor ID (DXGI)", "dxgi.customVendorId: four-digit hex PCI vendor ID reported to the game, e.g. 10de.", listOf("dxgi.customVendorId")),
            txt("Custom device ID (DXGI)", "dxgi.customDeviceId: four-digit hex PCI device ID.", listOf("dxgi.customDeviceId")),
            txt("Custom vendor ID (D3D9)", "d3d9.customVendorId: four-digit hex PCI vendor ID.", listOf("d3d9.customVendorId")),
            txt("Custom device ID (D3D9)", "d3d9.customDeviceId: four-digit hex PCI device ID.", listOf("d3d9.customDeviceId")),
            txt("Custom device description", "dxgi/d3d9.customDeviceDesc: reported adapter name (no spaces).", both("customDeviceDesc")),
            tri("Hide Nvidia GPU", "dxgi/d3d9.hideNvidiaGpu: report Nvidia GPUs as AMD to avoid Nvidia-specific code paths.", both("hideNvidiaGpu")),
            tri("Hide NVK GPU", "dxgi/d3d9.hideNvkGpu: report Nvidia GPUs running on NVK as AMD.", both("hideNvkGpu")),
            tri("Hide AMD GPU", "dxgi/d3d9.hideAmdGpu: report AMD GPUs as Nvidia (only for games with AMDAGS issues).", both("hideAmdGpu")),
            tri("Hide Intel GPU", "dxgi/d3d9.hideIntelGpu: report Intel GPUs as AMD. DXVK defaults to True for D3D9.", both("hideIntelGpu")),
            sw("Hide integrated graphics", "dxvk.hideIntegratedGraphics: only matters when a dedicated GPU is present.", listOf("dxvk.hideIntegratedGraphics")),
            txt("Device filter", "dxvk.deviceFilter: only expose Vulkan devices whose name contains this text (no spaces).", listOf("dxvk.deviceFilter")),
        )),
        Section("memory", "Memory", "Reported memory limits, chunk size and memory management. Low values help small-memory GPUs.", listOf(
            pick("Max device memory", "dxgi.maxDeviceMemory: device memory reported to the game. Not a hard cap.", listOf("dxgi.maxDeviceMemory"), "0", MEM_MB),
            pick("Max shared memory", "dxgi.maxSharedMemory: shared system memory reported to the game.", listOf("dxgi.maxSharedMemory"), "0", MEM_MB),
            num("D3D9 max available memory (MB)", "d3d9.maxAvailableMemory: initial value for GetAvailableTextureMem tracking.", listOf("d3d9.maxAvailableMemory"), "4096"),
            sw("D3D9 memory tracking test", "d3d9.memoryTrackTest: memory tracking testing mode.", listOf("d3d9.memoryTrackTest")),
            num("D3D9 locked texture memory (MB)", "d3d9.textureMemory: virtual memory for locked D3D9 textures. 0 = no limit. Does not change actual consumption.", listOf("d3d9.textureMemory"), "100"),
            pick("Max memory chunk size", "dxvk.maxChunkSize: limit for device memory chunks (MB). 0 = DXVK default.", listOf("dxvk.maxChunkSize"), "0",
                listOf("0" to "Default (0)") + listOf("4", "8", "16", "32", "64", "128").map { it to "$it MB" }),
            tri("Track pipeline lifetime", "dxvk.trackPipelineLifetime: free pipeline libraries aggressively to save memory. Auto = 32-bit apps only.", listOf("dxvk.trackPipelineLifetime")),
            tri("Memory defragmentation", "dxvk.enableMemoryDefrag: defragment video memory. Auto = on, except on blocked drivers. For debugging.", listOf("dxvk.enableMemoryDefrag")),
            sw("Zero mapped memory", "dxvk.zeroMappedMemory: clear freed mapped memory. Large CPU overhead; last resort for games that do not initialise buffers.", listOf("dxvk.zeroMappedMemory")),
            sw("State cache", "dxvk.enableStateCache: use the on-disk DXVK state cache (older DXVK versions).", listOf("dxvk.enableStateCache"), def = true),
        )),
        Section("pipeline", "Pipelines, shaders & Vulkan features", "Shader compilation and optional Vulkan extensions. Mostly for performance testing and debugging.", listOf(
            pick("Compiler threads", "dxvk.numCompilerThreads: pipeline compiler threads with graphics pipeline libraries. 0 = all CPU cores.", listOf("dxvk.numCompilerThreads"), "0",
                listOf("0" to "All cores (0)") + listOf("1", "2", "3", "4", "6", "8").map { it to "$it thread${if (it == "1") "" else "s"}" }),
            tri("Graphics pipeline library", "dxvk.enableGraphicsPipelineLibrary: Auto = enable if supported and compile optimised pipelines in the background; True = no optimised pipelines; False = disable. Off/True may cause stutter.", listOf("dxvk.enableGraphicsPipelineLibrary")),
            tri("Tiler mode", "dxvk.tilerMode: tiler (mobile GPU) optimisations that change submission heuristics. Intended for performance testing.", listOf("dxvk.tilerMode")),
            tri("Raw SSBO", "dxvk.useRawSsbo: use storage buffers for raw/structured buffer views. Unsafe on hardware with higher offset alignment.", listOf("dxvk.useRawSsbo")),
            tri("Descriptor heap", "dxvk.enableDescriptorHeap: VK_EXT_descriptor_heap. Auto = on known-good drivers.", listOf("dxvk.enableDescriptorHeap")),
            tri("Descriptor buffer", "dxvk.enableDescriptorBuffer: VK_EXT_descriptor_buffer. Descriptor heap takes precedence when available.", listOf("dxvk.enableDescriptorBuffer")),
            sw("Unified image layouts", "dxvk.enableUnifiedImageLayouts: VK_KHR_unified_image_layouts and related paths. Debugging only.", listOf("dxvk.enableUnifiedImageLayouts"), def = true),
            sw("Implicit resolves", "dxvk.enableImplicitResolves: SAMPLE_ZERO resolves for games that sample multisampled images as textures.", listOf("dxvk.enableImplicitResolves"), def = true),
            sw("Nvidia raw access chains", "dxvk.enableNvRawAccessChains: VK_NV_raw_access_chains (Nvidia only; debugging).", listOf("dxvk.enableNvRawAccessChains"), def = true),
            sw("Nvidia CUDA interop", "dxvk.enableNvCudaInterop: VK_NVX_* extensions needed for DLSS (Nvidia only).", listOf("dxvk.enableNvCudaInterop"), def = true),
            tri("Lower sin/cos", "dxvk.lowerSinCos: custom sine/cosine approximation. Auto = only on drivers with poor precision.", listOf("dxvk.lowerSinCos")),
        )),
        Section("gplasync", "GPLAsync builds only", "Options of dxvk-gplasync / gplall builds. Plain upstream DXVK ignores them. Default = whatever the build uses.", listOf(
            triBuild("Async shader compilation", "dxvk.enableAsync: compile shaders asynchronously to reduce stutter.", listOf("dxvk.enableAsync")),
            triBuild("GPL async cache", "dxvk.gplAsyncCache: cache for asynchronously compiled pipeline libraries.", listOf("dxvk.gplAsyncCache")),
            pick("Frame pacing mode", "dxvk.framePace: max-frame-latency is upstream behaviour; low-latency-vrr-N is tuned for a refresh rate of N Hz.", listOf("dxvk.framePace"), "",
                listOf("" to "Default (build)", "max-frame-latency" to "max-frame-latency") + listOf("60", "90", "120", "144", "165", "240", "360").map { "low-latency-vrr-$it" to "low-latency-vrr-$it" }),
            pick("Low latency offset (µs)", "dxvk.lowLatencyOffset: fine-tunes low-latency-vrr modes. 0 = default; negative values are common.", listOf("dxvk.lowLatencyOffset"), "",
                listOf("" to "Default (build)") + listOf("-20000", "-10000", "-5000", "-2000", "0", "2000", "5000", "10000").map { it to it }),
            triBuild("Allow CPU frame overlap", "dxvk.lowLatencyAllowCpuFramesOverlap: let CPU frames overlap in low-latency modes.", listOf("dxvk.lowLatencyAllowCpuFramesOverlap")),
        )),
        Section("d3d11", "Direct3D 11 & sampling", "D3D11 behaviour; sampler options apply to D3D9 as well.", listOf(
            pick("Max feature level", "d3d11.maxFeatureLevel: highest feature level a D3D11 device can be created with.", listOf("d3d11.maxFeatureLevel"), "12_1",
                values("12_1", "9_1", "9_2", "9_3", "10_0", "10_1", "11_0", "11_1", "12_0", "12_1")),
            pick("Max tessellation factor", "d3d11.maxTessFactor: cap tessellation for games that overuse it. 0 = no cap.", listOf("d3d11.maxTessFactor"), "0",
                listOf("0" to "Default (0)") + listOf("8", "16", "32", "64").map { it to it }),
            pick("Sampler anisotropy", "d3d11/d3d9.samplerAnisotropy: force anisotropic filtering for all samplers; 0 disables it; -1 = no effect. May break some passes.", d("samplerAnisotropy"), "-1",
                listOf("-1" to "Default (-1)", "0" to "Off (0)") + listOf("1", "2", "4", "8", "16").map { it to "${it}x" }),
            pick("Sampler LOD bias", "d3d11/d3d9.samplerLodBias: added to the game's LOD bias. Positive = less detail, negative = sharper but shimmery.", d("samplerLodBias"), "0.0",
                listOf("-2.0", "-1.5", "-1.0", "-0.5", "0.0", "0.5", "1.0").map { it to (if (it == "0.0") "Default (0.0)" else it) }),
            sw("Clamp negative LOD bias", "d3d11/d3d9.clampNegativeLodBias: clamp negative LOD bias to 0.", d("clampNegativeLodBias")),
            sw("Force sample rate shading", "d3d11/d3d9.forceSampleRateShading: per-sample shading with MSAA. Clearer but slower.", d("forceSampleRateShading")),
            sw("Disable MSAA", "d3d11.disableMsaa: force the sample count of all textures to 1.", listOf("d3d11.disableMsaa")),
            sw("Relaxed barriers", "d3d11.relaxedBarriers: relaxed pipeline barriers around UAV writes. May improve speed but cause rendering issues.", listOf("d3d11.relaxedBarriers")),
            sw("Relaxed graphics barriers", "d3d11.relaxedGraphicsBarriers: relaxed UAV barriers in graphics shaders only.", listOf("d3d11.relaxedGraphicsBarriers")),
            sw("Force compute LDS barriers", "d3d11.forceComputeLdsBarriers: barriers after group-shared memory writes in compute shaders. Workaround; may be slower.", listOf("d3d11.forceComputeLdsBarriers")),
            sw("Force compute UAV barriers", "d3d11.forceComputeUavBarriers: full barriers after read-write UAV access in compute shaders. Workaround; likely slower.", listOf("d3d11.forceComputeUavBarriers")),
            txt("Cached dynamic resources", "d3d11.cachedDynamicResources: letters v (vertex), i (index), c (constant), r (shader resources), a (all). For buggy apps that read back dynamic resources.", listOf("d3d11.cachedDynamicResources")),
            sw("Disable direct image mapping", "d3d11.disableDirectImageMapping: work around games that ignore the mapped row pitch of dynamic images.", listOf("d3d11.disableDirectImageMapping")),
            sw("Enable context lock", "d3d11.enableContextLock: force the D3D11 context lock (ID3D10Multithread). For debugging race conditions.", listOf("d3d11.enableContextLock")),
            sw("Expose driver command lists", "d3d11.exposeDriverCommandLists: some games choose deferred contexts based on this flag.", listOf("d3d11.exposeDriverCommandLists"), def = true),
            sw("Reproducible command stream", "d3d11/d3d9.reproducibleCommandStream: same D3D commands give the same Vulkan commands. Benchmarking; may hurt performance.", d("reproducibleCommandStream")),
        )),
        Section("d3d9", "Direct3D 9", "D3D9 compatibility options. D3D8 games run on top of the D3D9 layer.", listOf(
            pick("Shader model", "d3d9.shaderModel: shader model reported to the game (0 = fixed function only). Limited to 1 for D3D8.", listOf("d3d9.shaderModel"), "3", values("3", "0", "1", "2", "3")),
            sw("DPI aware", "d3d9.dpiAware: call SetProcessDPIAware on device creation; avoids upscaling blur on HiDPI screens.", listOf("d3d9.dpiAware"), def = true),
            sw("Lenient clear", "d3d9.lenientClear: fast-path clear when close enough to clearing a full render target.", listOf("d3d9.lenientClear")),
            pick("Float emulation", "d3d9.floatEmulation: True = fast, less accurate; Strict = slow, accurate; False = off; Auto = DXVK picks.", listOf("d3d9.floatEmulation"), "Auto",
                listOf("Auto" to "Auto", "True" to "On (fast)", "Strict" to "Strict", "False" to "Off")),
            tri("Device-local constant buffers", "d3d9.deviceLocalConstantBuffers: stream shader constants into VRAM. Auto = discrete GPUs only.", listOf("d3d9.deviceLocalConstantBuffers")),
            tri("Use FP16", "d3d9.useFP16: FP16 for partial-precision shader instructions. May help weak GPUs, may cause rendering issues. Default is Off.", listOf("d3d9.useFP16"), def = "False"),
            sw("Cube depth formats", "d3d9.supportCubeDepthFormats: depth formats for cube textures. Some games need it, others break.", listOf("d3d9.supportCubeDepthFormats")),
            sw("DF depth formats", "d3d9.supportDFFormats: vendor DF floating point depth formats (AMD/Intel). Ignored on Nvidia.", listOf("d3d9.supportDFFormats"), def = true),
            sw("Use D32F for D24", "d3d9.useD32forD24: reproduces AMD issues on other hardware.", listOf("d3d9.useD32forD24")),
            sw("Support X4R4G4B4", "d3d9.supportX4R4G4B4: expose the X4R4G4B4 format.", listOf("d3d9.supportX4R4G4B4"), def = true),
            sw("Disable A8 render target", "d3d9.disableA8RT: disable A8 render targets.", listOf("d3d9.disableA8RT")),
            sw("Force sampler-type spec constants", "d3d9.forceSamplerTypeSpecConstants: for games that use the wrong image/sampler type combination.", listOf("d3d9.forceSamplerTypeSpecConstants")),
            pick("Force aspect ratio", "d3d9.forceAspectRatio: only expose display modes with this aspect ratio.", listOf("d3d9.forceAspectRatio"), "",
                listOf("" to "Default (off)") + listOf("4:3", "5:4", "3:2", "16:10", "16:9", "21:9").map { it to it }),
            sw("Mode count compatibility", "d3d9.modeCountCompatibility: limit the advertised mode count to the desktop mode and a few fallbacks.", listOf("d3d9.modeCountCompatibility")),
            sw("Enumerate by displays", "d3d9.enumerateByDisplays: enumerate adapters by display (Windows behaviour) instead of by physical adapter.", listOf("d3d9.enumerateByDisplays"), def = true),
            sw("Cached write-only buffers", "d3d9.cachedWriteOnlyBuffers: allocate write-only default-pool buffers in cached system memory.", listOf("d3d9.cachedWriteOnlyBuffers")),
            sw("Seamless cubes", "d3d9.seamlessCubes: do not use non-seamless cube maps even if supported.", listOf("d3d9.seamlessCubes")),
            sw("Device loss on focus loss", "d3d9.deviceLossOnFocusLoss: report DEVICELOST when losing focus in fullscreen.", listOf("d3d9.deviceLossOnFocusLoss")),
            sw("Count losable resources", "d3d9.countLosableResources: reject Device::Reset while losable resources are alive. Turn off for games that leak them.", listOf("d3d9.countLosableResources"), def = true),
            sw("Extra front buffer", "d3d9.extraFrontbuffer: add a second buffer for GetFrontBufferData (Silent Hill 2 Enhanced Edition).", listOf("d3d9.extraFrontbuffer")),
            sw("Ignore default buffer lock range", "d3d9.ignoreDefaultBufferLockRange: mark the whole default-pool buffer dirty on lock. Slower.", listOf("d3d9.ignoreDefaultBufferLockRange")),
        )),
        Section("d3d8", "Direct3D 8", "Workarounds for old D3D8 games.", listOf(
            num("Depth reference scale (bits)", "d3d8.scaleDref: scale Dref to [0..1] for early D3D8 games; value is the bit depth (usually 24). 0 = off.", listOf("d3d8.scaleDref"), "0"),
            sw("Shadow perspective divide", "d3d8.shadowPerspectiveDivide: emulate old Nvidia projection of shadow-map coordinates.", listOf("d3d8.shadowPerspectiveDivide")),
            txt("Force vertex shader declaration", "d3d8.forceVsDecl: comma-separated register:type pairs, e.g. 0:2,3:2,7:1.", listOf("d3d8.forceVsDecl")),
            sw("Draw call batching", "d3d8.batching: batch many similar draw calls. May hurt performance or cause artifacts.", listOf("d3d8.batching")),
            sw("Place P8 textures in scratch", "d3d8.placeP8InScratch: always allow P8 texture creation.", listOf("d3d8.placeP8InScratch")),
            sw("Legacy discard behaviour", "d3d8.forceLegacyDiscard: D3DLOCK_DISCARD only honoured for dynamic write-only buffers.", listOf("d3d8.forceLegacyDiscard")),
        )),
        Section("debug", "Debug", null, listOf(
            sw("Debug utils", "dxvk.enableDebugUtils: user annotations such as BeginEvent/EndEvent. Same as DXVK_DEBUG=markers.", listOf("dxvk.enableDebugUtils")),
        )),
    )

    /** Mode presentasi gabungan: kombinasi `syncInterval` / `presentInterval` dan `dxvk.tearFree`. */
    val PRESENT: List<Present> = listOf(
        Present("default", "Default (game decides)", emptyMap()),
        Present("fifo", "VSync (FIFO)", mapOf("dxgi.syncInterval" to "1", "d3d9.presentInterval" to "1")),
        Present("fifo_relaxed", "VSync, relaxed FIFO", mapOf("dxgi.syncInterval" to "1", "d3d9.presentInterval" to "1", "dxvk.tearFree" to "False")),
        Present("half", "VSync x2 (repeat each frame twice)", mapOf("dxgi.syncInterval" to "2", "d3d9.presentInterval" to "2")),
        Present("mailbox", "Mailbox (VSync off, no tearing)", mapOf("dxgi.syncInterval" to "0", "d3d9.presentInterval" to "0", "dxvk.tearFree" to "True")),
        Present("immediate", "Immediate (VSync off, may tear)", mapOf("dxgi.syncInterval" to "0", "d3d9.presentInterval" to "0")),
    )
    private val PRESENT_KEYS = listOf("dxgi.syncInterval", "d3d9.presentInterval", "dxvk.tearFree")

    /** Elemen HUD (`dxvk.hud`, sama dengan DXVK_HUD). */
    val HUD: List<Pair<String, String>> = listOf(
        "devinfo" to "GPU name and driver version",
        "fps" to "Frames per second",
        "frametimes" to "Frame time graph",
        "submissions" to "Command buffer submissions",
        "drawcalls" to "Draw calls and render passes",
        "pipelines" to "Pipeline count",
        "descriptors" to "Descriptor pools and sets",
        "memory" to "Device memory allocated and used",
        "allocations" to "Memory chunk suballocation detail",
        "gpuload" to "GPU load",
        "version" to "DXVK version",
        "api" to "D3D feature level in use",
        "compiler" to "Shader compiler activity",
        "samplers" to "Sampler pairs in use (D3D9 only)",
        "ffshaders" to "Fixed-function shaders (D3D9 only)",
        "swvp" to "Software vertex processing (D3D9 only)",
    )
    private const val HUD_KEY = "dxvk.hud"

    // ------------------------------------------------------------------ penyimpanan

    private val KEY_RE = Regex("^[A-Za-z0-9_.]{1,64}$")

    /** Menyisakan karakter yang aman di EXTRA_OPT proot (tanpa spasi, tanda kutip, `;`, `#`, `=`, `$`). */
    fun sanitize(v: String): String = v.filter { it.isLetterOrDigit() && it.code < 128 || it in "._:,+-/" }.take(64)

    private fun parse(): LinkedHashMap<String, String> {
        val m = LinkedHashMap<String, String>()
        for (line in Cfg.dxvkConfig.lineSequence()) {
            val i = line.indexOf('=')
            if (i <= 0) continue
            val k = line.substring(0, i).trim()
            val v = line.substring(i + 1).trim()
            if (KEY_RE.matches(k) && v.isNotEmpty()) m[k] = v
        }
        return m
    }

    private fun save(m: Map<String, String>) {
        Cfg.dxvkConfig = m.entries.joinToString("\n") { "${it.key}=${it.value}" }
    }

    /** Nilai buatan pengguna (tanpa dasar [TUNE]). */
    fun user(): Map<String, String> = parse()

    /** Dasar sebuah kunci: nilai [TUNE] bila aktif, selain itu bawaan DXVK. */
    private fun baseline(o: Opt): String = (if (Cfg.dxvkTune) TUNE[o.keys[0]] else null) ?: o.def

    /** Nilai yang berlaku untuk [o]: nilai pengguna, atau dasarnya. */
    fun value(o: Opt): String = parse()[o.keys[0]] ?: baseline(o)

    /** Apakah [o] punya nilai buatan pengguna. */
    fun isChanged(o: Opt): Boolean = parse().containsKey(o.keys[0])

    /** Menyetel [o] ke [v]; nilai yang sama dengan dasarnya (atau kosong) menghapus penimpaan. */
    @Synchronized
    fun set(o: Opt, v: String) {
        val m = parse()
        val clean = if (o.kind == Kind.TEXT || o.kind == Kind.NUMBER) sanitize(v) else v
        if (clean.isEmpty() || clean == baseline(o)) o.keys.forEach { m.remove(it) } else o.keys.forEach { m[it] = clean }
        save(m)
    }

    @Synchronized
    fun reset() { Cfg.dxvkConfig = "" }

    /** Menghapus penimpaan semua opsi di [s] (mode presentasi dan HUD tidak ikut). */
    @Synchronized
    fun resetSection(s: Section) {
        val m = parse()
        s.opts.forEach { o -> o.keys.forEach { m.remove(it) } }
        save(m)
    }

    // ------------------------------------------------------------------ mode presentasi & HUD

    fun presentModeId(): String {
        val m = parse()
        val have = PRESENT_KEYS.filter { m.containsKey(it) }
        if (have.isEmpty()) return "default"
        val sync = m["dxgi.syncInterval"]
        val pres = m["d3d9.presentInterval"]
        val tear = m["dxvk.tearFree"]
        return PRESENT.firstOrNull {
            it.settings["dxgi.syncInterval"] == sync && it.settings["d3d9.presentInterval"] == pres && it.settings["dxvk.tearFree"] == tear
        }?.id ?: "custom"
    }

    @Synchronized
    fun setPresentMode(id: String) {
        val mode = PRESENT.firstOrNull { it.id == id } ?: return
        val m = parse()
        PRESENT_KEYS.forEach { m.remove(it) }
        m.putAll(mode.settings)
        save(m)
    }

    /** Elemen HUD yang aktif (`full` berarti semuanya). */
    fun hud(): Set<String> = parse()[HUD_KEY]?.split(',')?.filter { it.isNotBlank() }?.toSet().orEmpty()

    @Synchronized
    fun setHud(items: Set<String>) {
        val m = parse()
        val text = if ("full" in items) "full" else HUD.map { it.first }.filter { it in items }.joinToString(",")
        if (text.isEmpty()) m.remove(HUD_KEY) else m[HUD_KEY] = text
        save(m)
    }

    // ------------------------------------------------------------------ hasil akhir

    /** Pasangan kunci -> nilai yang dikirim ke DXVK: dasar [TUNE] (bila aktif) ditimpa nilai pengguna. */
    fun effective(): Map<String, String> {
        val m = LinkedHashMap<String, String>()
        if (Cfg.dxvkTune) m.putAll(TUNE)
        m.putAll(parse())
        return m.filter { KEY_RE.matches(it.key) && sanitize(it.value) == it.value && it.value.isNotEmpty() }
    }

    /** Isi `DXVK_CONFIG`: `kunci=nilai;kunci=nilai` (tanpa spasi), atau null bila tidak ada yang dikirim. */
    fun configText(): String? {
        if (!Cfg.dxvkConfigOn) return null
        val m = effective()
        return if (m.isEmpty()) null else m.entries.joinToString(";") { "${it.key}=${it.value}" }
    }

    /** Fragmen untuk `EXTRA_OPT` ([Launcher.guestSpec]): `DXVK_CONFIG=...`, atau null. */
    fun envOpt(): String? = configText()?.let { "DXVK_CONFIG=$it" }
}
