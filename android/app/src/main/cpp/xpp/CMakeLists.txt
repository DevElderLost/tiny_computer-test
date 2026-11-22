# X++ native X server (dari Winlator Ludashi, folder ludashix11): server X11 ditulis dalam C++ yang
# berjalan di dalam proses aplikasi, menggambar langsung ke Surface lewat EGL (GLES) atau DisplayX
# (ASurfaceControl) atau Vulkan (swapchain). Dipakai sebagai pilihan "Display server" di Pengaturan Desktop,
# berdampingan dengan Xwayland/Wayland. "Old Display" memakai backend EGL (sama seperti Winlator Ludashi).
# Backend Vulkan = VulkanRendererContext Winlator (vulkan/), tanpa mesin frame generation (vulkan/framegen_stub.cpp).
file(GLOB XPP_CORE_SOURCES CONFIGURE_DEPENDS
    ${CMAKE_CURRENT_SOURCE_DIR}/core/*.cpp
    ${CMAKE_CURRENT_SOURCE_DIR}/core/io/*.cpp
    ${CMAKE_CURRENT_SOURCE_DIR}/core/cursor/*.cpp
    ${CMAKE_CURRENT_SOURCE_DIR}/base/*.cpp)

# ---- Shader backend Vulkan: GLSL -> header SPIR-V saat build ----
# Memakai glslangValidator bila ada di PATH (apt: glslang-tools), bila tidak glslc bawaan NDK (shader-tools).
set(XPP_VK_DIR ${CMAKE_CURRENT_SOURCE_DIR}/vulkan)
set(XPP_VK_GEN ${CMAKE_CURRENT_BINARY_DIR}/vkshaders)
file(MAKE_DIRECTORY ${XPP_VK_GEN})
find_program(XPP_GLSLANG glslangValidator)
if(NOT XPP_GLSLANG)
    find_program(XPP_GLSLC glslc HINTS
        ${ANDROID_NDK}/shader-tools/${ANDROID_HOST_TAG}
        ${ANDROID_NDK}/shader-tools/linux-x86_64
        ${ANDROID_NDK}/shader-tools/darwin-x86_64
        ${ANDROID_NDK}/shader-tools/windows-x86_64)
endif()
if(NOT XPP_GLSLANG AND NOT XPP_GLSLC)
    message(FATAL_ERROR "xpp: glslangValidator atau glslc (NDK shader-tools) tidak ditemukan; dibutuhkan untuk shader backend Vulkan")
endif()

function(xpp_shader SRC VAR OUT)
    if(XPP_GLSLANG)
        add_custom_command(OUTPUT ${OUT}
            COMMAND ${XPP_GLSLANG} -V --vn ${VAR} -x -o ${OUT} ${XPP_VK_DIR}/${SRC}
            DEPENDS ${XPP_VK_DIR}/${SRC} VERBATIM)
    else()
        add_custom_command(OUTPUT ${OUT}
            COMMAND ${XPP_GLSLC} --target-env=vulkan1.0 -mfmt=c -o ${OUT}.raw ${XPP_VK_DIR}/${SRC}
            COMMAND ${CMAKE_COMMAND} -DIN=${OUT}.raw -DOUT=${OUT} -DVAR=${VAR}
                    -P ${CMAKE_CURRENT_SOURCE_DIR}/cmake/wrap_spirv.cmake
            DEPENDS ${XPP_VK_DIR}/${SRC} ${CMAKE_CURRENT_SOURCE_DIR}/cmake/wrap_spirv.cmake VERBATIM)
    endif()
endfunction()

xpp_shader(window.vert          window_vert_code          ${XPP_VK_GEN}/window_vert.h)
xpp_shader(window.frag          window_frag_code          ${XPP_VK_GEN}/window_frag.h)
xpp_shader(window_sgsr.frag     window_sgsr_frag_code     ${XPP_VK_GEN}/window_sgsr_frag.h)
xpp_shader(window_fsr1.frag     window_fsr1_frag_code     ${XPP_VK_GEN}/window_fsr1_frag.h)
xpp_shader(window_lanczos.frag  window_lanczos_frag_code  ${XPP_VK_GEN}/window_lanczos_frag.h)
xpp_shader(window_stretch.frag  window_stretch_frag_code  ${XPP_VK_GEN}/window_stretch_frag.h)
xpp_shader(window_postfx.frag   window_postfx_frag_code   ${XPP_VK_GEN}/window_postfx_frag.h)

add_library(xpp SHARED
    ${XPP_CORE_SOURCES}
    src/native_drawable.cpp
    src/native_screen.cpp
    src/native_extensions.cpp
    src/native_glx.cpp
    src/native_randr.cpp
    src/native_icc.cpp
    src/native_compat.cpp
    src/native_xinput.cpp
    src/native_displayx_bridge.cpp
    src/native_renderer.cpp
    src/native_egl_renderer.cpp
    src/native_displayx_renderer.cpp
    src/native_vulkan_renderer.cpp
    src/native_jni.cpp
    vulkan/VulkanRendererContext.cpp
    vulkan/VulkanRendererFilters.cpp
    vulkan/VulkanRendererPipelineOpt.cpp
    vulkan/VulkanRendererWfg.cpp
    vulkan/framegen_stub.cpp
    vulkan/view_transformation.cpp
    vulkan/xform.cpp
    vulkan/vk_dispatch.c
    ${XPP_VK_GEN}/window_vert.h
    ${XPP_VK_GEN}/window_frag.h
    ${XPP_VK_GEN}/window_sgsr_frag.h
    ${XPP_VK_GEN}/window_fsr1_frag.h
    ${XPP_VK_GEN}/window_lanczos_frag.h
    ${XPP_VK_GEN}/window_stretch_frag.h
    ${XPP_VK_GEN}/window_postfx_frag.h)

set_target_properties(xpp PROPERTIES CXX_STANDARD 20 CXX_STANDARD_REQUIRED ON)
target_compile_definitions(xpp PRIVATE LUDASHI_NATIVE_X11=1 FMT_HEADER_ONLY=1)
target_include_directories(xpp PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${CMAKE_CURRENT_SOURCE_DIR}/include
    ${CMAKE_CURRENT_SOURCE_DIR}/third_party
    ${XPP_VK_DIR}
    ${XPP_VK_GEN})
target_compile_options(xpp PRIVATE
    "$<$<COMPILE_LANGUAGE:CXX>:-include;${CMAKE_CURRENT_SOURCE_DIR}/include/common.h>"
    -Wall -Wextra -Wno-unused-parameter -Wno-missing-field-initializers -fvisibility=hidden)
target_link_libraries(xpp PRIVATE android log EGL GLESv2 vulkan adrenotools dl atomic)
