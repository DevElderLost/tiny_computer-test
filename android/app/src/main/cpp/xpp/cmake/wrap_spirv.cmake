# Membungkus keluaran `glslc -mfmt=c` (daftar inisialisasi C) menjadi header array uint32_t, formatnya sama dengan
# `glslangValidator -V --vn <nama> -x`. Dipanggil dengan: cmake -DIN=... -DOUT=... -DVAR=... -P wrap_spirv.cmake
file(READ "${IN}" body)
string(REGEX REPLACE "[{}]" "" body "${body}")
file(WRITE "${OUT}" "#pragma once\n#include <stdint.h>\nstatic const uint32_t ${VAR}[] = {\n${body}\n};\n")
