cmake_minimum_required(VERSION 3.22.1)
project("tiny_native" C CXX)

# Wayland compositor + Vulkan present (dari DroidDeck) memakai C biasa; tanpa -Werror.
set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -O2 -Wno-unused-function -Wimplicit-function-declaration")

# Setiap library dihubungkan dengan segmen LOAD 16 KB (kernel Android 15+ dengan page 16 KB).
set(CMAKE_SHARED_LINKER_FLAGS "${CMAKE_SHARED_LINKER_FLAGS} -Wl,-z,max-page-size=16384")
set(CMAKE_EXE_LINKER_FLAGS "${CMAKE_EXE_LINKER_FLAGS} -Wl,-z,max-page-size=16384")

# --- bawaan Tiny Computer: soket audio mikrofon ---
add_library(native-socket SHARED native-socket.cpp)
find_library(log-lib log)
target_link_libraries(native-socket ${log-lib})

# --- audio native langsung ke Android (tanpa PulseAudio): soket unix dari plugin ALSA di guest -> AAudio ---
add_library(tinyaudio SHARED tinyaudio/tinyaudio_core.c tinyaudio/tinyaudio_jni.cpp)
target_include_directories(tinyaudio PRIVATE tinyaudio)
target_link_libraries(tinyaudio aaudio ${log-lib})

# --- dari DroidDeck ---
# Loader driver Turnip (dipakai compositor untuk dmabuf import).
add_subdirectory(adrenotools)

# Compositor Wayland dalam proses (libdroiddeckwayland.so). Mesin frame generation
# (LSFG/Win-FG) tidak disertakan: CMake waylandcomp otomatis memakai stub.
add_subdirectory(waylandcomp)

# Pagar memori (store fence) untuk ring input palsu (FakeInputWriter).
add_library(sdnative SHARED sdnative/sdnative.c)
target_link_libraries(sdnative log)
