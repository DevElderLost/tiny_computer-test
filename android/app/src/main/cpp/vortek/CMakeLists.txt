# Renderer Vulkan Vortek (dari Winlator): proses aplikasi menerima perintah Vulkan dari ICD guest
# (libvulkan_vortek.so, glibc) lewat soket unix, lalu mengeksekusinya di driver Vulkan Android
# (Turnip lewat adrenotools atau libvulkan sistem) dan menampilkan hasilnya ke window X++ lewat AHardwareBuffer.
# Helper libwinlator yang dipakai (ring buffer, array, shm) dikompilasi langsung ke sini; libwinlator utuh
# (X server Java, drawable GL) tidak dibawa.
file(GLOB VORTEK_SOURCES CONFIGURE_DEPENDS ${CMAKE_CURRENT_SOURCE_DIR}/src/*.c)
add_library(vortekrenderer SHARED
    ${VORTEK_SOURCES}
    winlator/src/ring_buffer.c
    winlator/src/arrays.c
    winlator/src/sysvshared_memory.c)
target_compile_definitions(vortekrenderer PRIVATE VK_USE_PLATFORM_ANDROID_KHR)
target_include_directories(vortekrenderer PRIVATE include winlator/include)
target_link_libraries(vortekrenderer adrenotools log android)
