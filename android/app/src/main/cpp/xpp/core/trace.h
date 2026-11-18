#pragma once

namespace xorg {
// Jejak protokol untuk diagnosis. Aktif hanya selama berkas penanda `<dir>/.xpp-trace` ada (dibuat pengguna dengan
// `touch /tmp/.xpp-trace` di dalam kontainer); isi jejak ditulis ke `<dir>/xpp-trace.log`. <dir> = folder tmp kontainer yang
// diberikan aplikasi lewat variabel lingkungan XPP_TRACE_DIR. Pemeriksaan penanda dibatasi sekali per detik.
bool TraceOn();
void Trace(const char* format, ...) __attribute__((format(printf, 1, 2)));
// Mode tampilan compositor: aktif selama berkas penanda `<dir>/.xpp-composite` ada (`touch /tmp/.xpp-composite`). Server lalu
// menampilkan root yang digambar compositor (xfwm4) dan bukan window satu per satu. Dicek sekali per detik.
bool CompositeDisplayRequested();
}  // namespace xorg
