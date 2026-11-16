# X++ native X server (dari Winlator Ludashi, folder ludashix11): server X11 ditulis dalam C++ yang
# berjalan di dalam proses aplikasi, menggambar langsung ke Surface lewat EGL (GLES) atau DisplayX
# (ASurfaceControl). Dipakai sebagai pilihan "Display server" di Pengaturan Desktop, berdampingan
# dengan Xwayland/Wayland. Backend Vulkan Winlator sengaja tidak dibawa (lihat src/native_renderer.cpp).
file(GLOB XPP_CORE_SOURCES CONFIGURE_DEPENDS
    ${CMAKE_CURRENT_SOURCE_DIR}/core/*.cpp
    ${CMAKE_CURRENT_SOURCE_DIR}/core/io/*.cpp
    ${CMAKE_CURRENT_SOURCE_DIR}/core/cursor/*.cpp
    ${CMAKE_CURRENT_SOURCE_DIR}/base/*.cpp)

add_library(xpp SHARED
    ${XPP_CORE_SOURCES}
    src/native_drawable.cpp
    src/native_screen.cpp
    src/native_extensions.cpp
    src/native_glx.cpp
    src/native_randr.cpp
    src/native_compat.cpp
    src/native_xinput.cpp
    src/native_displayx_bridge.cpp
    src/native_renderer.cpp
    src/native_egl_renderer.cpp
    src/native_displayx_renderer.cpp
    src/native_jni.cpp)

set_target_properties(xpp PROPERTIES CXX_STANDARD 20 CXX_STANDARD_REQUIRED ON)
target_compile_definitions(xpp PRIVATE LUDASHI_NATIVE_X11=1 FMT_HEADER_ONLY=1)
target_include_directories(xpp PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${CMAKE_CURRENT_SOURCE_DIR}/include
    ${CMAKE_CURRENT_SOURCE_DIR}/third_party)
target_compile_options(xpp PRIVATE
    "$<$<COMPILE_LANGUAGE:CXX>:-include;${CMAKE_CURRENT_SOURCE_DIR}/include/common.h>"
    -Wall -Wextra -Wno-unused-parameter -Wno-missing-field-initializers -fvisibility=hidden)
target_link_libraries(xpp PRIVATE android log EGL GLESv2 dl atomic)
