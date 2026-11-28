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
# ICD guest bawaan (aset vortek/libvulkan_vortek.so, Vortek 2.1) memakai protokol lama tanpa ring pool; OFF bila ICD diganti
# dengan build yang cocok dengan sumber host ini (2 + RING_POOL_ARRAY_LEN fd, request diawali ringId).
option(VORTEK_LEGACY_ICD "Bicara dengan ICD Vortek lama (2 fd, tanpa ringId)" ON)
target_compile_definitions(vortekrenderer PRIVATE VK_USE_PLATFORM_ANDROID_KHR)
if(VORTEK_LEGACY_ICD)
    target_compile_definitions(vortekrenderer PRIVATE VORTEK_LEGACY_ICD)
endif()
target_include_directories(vortekrenderer PRIVATE include winlator/include)
target_link_libraries(vortekrenderer adrenotools log android)
