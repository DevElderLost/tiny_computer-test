package com.example.tiny_computer.core

import android.content.Context
import android.net.Uri
import android.os.StatFs
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest

/**
 * Memasang AppImage Linux (.AppImage) dari menu Shortcut, mengikuti AppImageManager milik DroidDeck:
 * berkas dicek dulu (ELF, tanda AppImage tipe 2, dan ARSITEKTUR), lalu diekstrak SEKALI ke rootfs
 * (proot tidak punya FUSE untuk me-mount image, dan mengekstrak tiap dijalankan memakan ruang /tmp sebesar
 * isinya setiap kali). Hasilnya menjadi berkas `.desktop` pengguna seperti aplikasi Linux lain, jadi tampil
 * di grid Shortcut, dijalankan oleh [ShortcutRunner] di sesi proot baru, dan punya pengaturan per aplikasi.
 *
 * Hanya ARM64 (aarch64) yang diterima: arsitektur lain (x86_64, x86, ARM 32-bit, dst) DITOLAK sebelum
 * ada yang disalin atau dipasang. Proses pemasangannya sendiri berupa skrip di dalam kontainer yang
 * dijalankan di TERMINAL, sehingga setiap langkah dan galatnya terlihat di tab Terminal.
 *
 * Tata letak (path tamu = di dalam kontainer):
 * - `/home/tiny/.local/share/appimages/<id>/` : image sementara, skrip, hasil ekstraksi di `app/`, ikon
 * - `/home/tiny/.local/share/applications/<id>.desktop` : entri menu
 */
object AppImageInstaller {
    /** Awalan id berkas .desktop; juga penanda bahwa entri ini boleh dihapus dari menu Shortcut. */
    const val ID_PREFIX = "tinyappimg-"

    private const val ELF_AARCH64 = 183

    sealed class Outcome {
        /** Image sudah disalin dan skrip pasang siap dijalankan di terminal lewat [terminalCommand]. */
        data class Ready(val name: String, val id: String) : Outcome()
        object NotAppImage : Outcome()
        /** Bukan ARM64; [arch] nama arsitekturnya (mis. "x86_64"). Pemasangan dibatalkan. */
        data class WrongArch(val arch: String) : Outcome()
        data class BadType(val type: Int) : Outcome()
        object NoSystem : Outcome()
        data class NoSpace(val needMb: Long) : Outcome()
        data class Failed(val message: String) : Outcome()
    }

    fun isAppImageShortcut(id: String) = id.startsWith(ID_PREFIX)

    /**
     * Id AppImage yang skrip pasangnya sedang berjalan di terminal. Diisi saat perintah dikirim ke terminal;
     * TinyApp menunggu penanda selesai ([isDone]) lalu menutup tampilan terminal dan kembali ke tab Shortcut.
     */
    var watching by mutableStateOf<String?>(null)
        private set

    fun watch(id: String) { watching = id }

    fun stopWatching(id: String) { if (watching == id) watching = null }

    /** Skrip pasang menulis berkas ini di langkah terakhir; tidak ada bila gagal (terminal dibiarkan agar galatnya terbaca). */
    fun isDone(id: String) = File(File(baseDir(), id), DONE_MARK).exists()

    private const val DONE_MARK = "install.done"

    /**
     * Id AppImage yang skrip uninstall-nya sedang berjalan di terminal. Seperti [watching] untuk pemasangan:
     * TinyApp menunggu penanda selesai ([isRemoved]) lalu menutup terminal dan kembali ke tab Shortcut.
     */
    var removing by mutableStateOf<String?>(null)
        private set

    fun watchRemove(id: String) { removing = id }

    fun stopWatchingRemove(id: String) { if (removing == id) removing = null }

    /**
     * Skrip uninstall menulis berkas ini di langkah terakhir. Letaknya DI LUAR folder `<id>/` (folder itu ikut
     * dihapus), dan tidak ada bila gagal supaya terminal dibiarkan terbuka dan galatnya terbaca.
     */
    fun isRemoved(id: String) = File(baseDir(), "$id$REMOVE_DONE_SUFFIX").exists()

    private const val REMOVE_DONE_SUFFIX = ".uninstall.done"
    private const val REMOVE_SCRIPT_SUFFIX = ".uninstall.sh"

    fun isAppImageName(name: String?) = name != null && name.endsWith(".appimage", ignoreCase = true)

    private fun baseDir() = File(TinyEnv.containerDir(), "home/tiny/.local/share/appimages")
    private fun appsDir() = File(TinyEnv.containerDir(), "home/tiny/.local/share/applications")
    private fun guestDir(id: String) = "/home/tiny/.local/share/appimages/$id"

    fun displayName(ctx: Context, uri: Uri): String? =
        runCatching {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/')

    private fun sizeOf(ctx: Context, uri: Uri): Long =
        runCatching {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else -1L
            }
        }.getOrNull() ?: -1L

    /** Nama arsitektur dari `e_machine` ELF. */
    private fun archName(machine: Int, elfClass: Int): String = when (machine) {
        ELF_AARCH64 -> "aarch64"
        62 -> "x86_64"
        3 -> "x86 (32-bit)"
        40 -> "ARM (32-bit)"
        243 -> "RISC-V"
        8 -> "MIPS"
        21 -> "PowerPC64"
        else -> "machine $machine (ELF${if (elfClass == 1) "32" else "64"})"
    }

    /**
     * Memeriksa kepala berkas. Urutannya seperti DroidDeck: ELF -> arsitektur -> tanda AppImage ("AI" di byte 8,
     * tipe di byte 10). Mengembalikan null bila boleh dipasang, atau alasan penolakannya.
     */
    internal fun inspect(head: ByteArray): Outcome? {
        if (head.size < 20) return Outcome.NotAppImage
        if (head[0] != 0x7f.toByte() || head[1] != 'E'.code.toByte() || head[2] != 'L'.code.toByte() || head[3] != 'F'.code.toByte()) {
            return Outcome.NotAppImage
        }
        val machine = (head[18].toInt() and 0xff) or ((head[19].toInt() and 0xff) shl 8)
        if (machine != ELF_AARCH64) return Outcome.WrongArch(archName(machine, head[4].toInt()))
        if (head[8] != 'A'.code.toByte() || head[9] != 'I'.code.toByte()) return Outcome.NotAppImage
        if (head[10].toInt() != 2) return Outcome.BadType(head[10].toInt())
        return null
    }

    private fun readHead(ctx: Context, uri: Uri): ByteArray? =
        runCatching {
            ctx.contentResolver.openInputStream(uri)?.use { input ->
                val head = ByteArray(20)
                var n = 0
                while (n < head.size) {
                    val r = input.read(head, n, head.size - n)
                    if (r < 0) break
                    n += r
                }
                if (n < head.size) null else head
            }
        }.getOrNull()

    private fun safe(s: String) = s.replace(Regex("[^A-Za-z0-9._-]"), "_")

    private fun idFor(fileName: String, size: Long): String {
        val label = fileName.dropLast(".appimage".length).lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(32).ifEmpty { "app" }
        val h = MessageDigest.getInstance("SHA-1").digest("$fileName:$size".toByteArray()).joinToString("") { "%02x".format(it) }
        return "$ID_PREFIX$label-${h.take(8)}"
    }

    /** Nama yang aman dipakai sebagai literal di skrip sh (satu baris, tanpa kutip dan karakter kontrol). */
    private fun shLabel(s: String) = s.replace(Regex("[\\r\\n]+"), " ").replace(Regex("[^\\p{L}\\p{N} ._()+-]"), "").trim().ifEmpty { "AppImage" }

    /**
     * Memvalidasi pilihan pengguna (harus .AppImage, ELF aarch64, tipe 2, sistem terpasang, ruang cukup) lalu
     * menyalin image ke rootfs dan menulis skrip pasang. Dipanggil di thread latar. Penolakan terjadi SEBELUM
     * ada yang disalin, jadi image x86_64 tidak meninggalkan sisa apa pun.
     */
    fun prepare(ctx: Context, uri: Uri): Outcome {
        val file = displayName(ctx, uri)
        if (!isAppImageName(file)) return Outcome.NotAppImage
        val head = readHead(ctx, uri) ?: return Outcome.NotAppImage
        inspect(head)?.let { return it }
        val root = TinyEnv.containerDir()
        if (!Startup.installed || !File(root, "home/tiny").isDirectory) return Outcome.NoSystem
        val size = sizeOf(ctx, uri)
        // image dan hasil ekstraksinya (kira-kira sebesar image lagi) ada bersamaan di rootfs
        val need = (if (size > 0) size else 64L shl 20) * 3
        if (StatFs(root.path).availableBytes < need) return Outcome.NoSpace(need shr 20)
        val id = idFor(file!!, size)
        val dir = File(baseDir(), id)
        return try {
            deleteTree(dir)
            dir.mkdirs()
            val image = File(dir, "image.AppImage")
            ctx.contentResolver.openInputStream(uri)!!.use { input -> image.outputStream().use { input.copyTo(it, 1 shl 20) } }
            // dibaca ulang dari salinan: tanda yang diperiksa harus milik berkas yang benar-benar diekstrak
            val copied = ByteArray(20).also { b -> image.inputStream().use { it.read(b) } }
            inspect(copied)?.let { deleteTree(dir); return it }
            image.setExecutable(true, false)
            val label = file.dropLast(".appimage".length)
            val script = File(dir, "install.sh")
            script.writeText(installScript(id, shLabel(label)))
            script.setReadable(true, false)
            script.setExecutable(true, false)
            Outcome.Ready(label, id)
        } catch (e: Exception) {
            deleteTree(dir)
            Outcome.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    /** Baris yang ditulis ke terminal kontainer untuk menjalankan pemasangan [id]. */
    fun terminalCommand(id: String): String = "clear; bash ${guestDir(id)}/install.sh"

    private fun installScript(id: String, label: String): String = """#!/bin/bash
ID='$id'
LABEL='$label'
BASE="${'$'}HOME/.local/share/appimages/${'$'}ID"
APPS="${'$'}HOME/.local/share/applications"
echo "=== Pasang AppImage: ${'$'}LABEL ==="
echo "[1/4] Arsitektur: aarch64 (arm64) - OK"
cd "${'$'}BASE" || { echo "GAGAL: folder ${'$'}BASE tidak ada"; exit 1; }
chmod +x image.AppImage
echo "[2/4] Mengekstrak AppImage (proot tidak punya FUSE, jadi diekstrak sekali)..."
rm -rf squashfs-root AppDir app
if ! ./image.AppImage --appimage-extract > extract.log 2>&1; then
  tail -n 20 extract.log
  echo "GAGAL: AppImage tidak bisa diekstrak."
  rm -rf squashfs-root AppDir extract.log
  exit 1
fi
SRC="${'$'}(readlink -f squashfs-root)"
if [ ! -d "${'$'}SRC" ] || [ ! -e "${'$'}SRC/AppRun" ]; then
  echo "GAGAL: hasil ekstraksi tidak berisi AppRun."
  rm -rf squashfs-root AppDir extract.log
  exit 1
fi
mv "${'$'}SRC" app && rm -f squashfs-root
# symlink hasil proot (link2symlink) menunjuk ke <folder-ekstraksi>/... dengan path absolut; tanpa folder lama itu semuanya putus
# (termasuk app/sharun, sehingga [ -e ] di bawah salah menyangka bukan AppImage sharun). Arahkan nama lama ke app.
ln -sfn app "${'$'}(basename "${'$'}SRC")"
echo "    ${'$'}(find app -type f | wc -l) berkas diekstrak"
echo "[3/4] Membuat shortcut..."
DESK="${'$'}(ls app/*.desktop 2>/dev/null | head -n 1)"
key() { [ -n "${'$'}DESK" ] && sed -n "/^\[Desktop Entry\]/,/^\[/{s/^${'$'}1=//p}" "${'$'}DESK" | head -n 1 | tr -d '\r'; }
NAME="${'$'}(key Name)"; [ -z "${'$'}NAME" ] && NAME="${'$'}LABEL"
COMMENT="${'$'}(key Comment)"
CATS="${'$'}(key Categories)"; [ -z "${'$'}CATS" ] && CATS='Utility;'
ICONNAME="${'$'}(key Icon)"
ICON=""
if [ -n "${'$'}ICONNAME" ]; then
  P="${'$'}(find app -type f -name "${'$'}ICONNAME.png" -exec ls -S {} + 2>/dev/null | head -n 1)"
  if [ -n "${'$'}P" ] && cp "${'$'}P" icon.png; then ICON="${'$'}BASE/icon.png"; fi
  if [ -z "${'$'}ICON" ]; then
    S="${'$'}(find app -type f -name "${'$'}ICONNAME.svg" | head -n 1)"
    if [ -n "${'$'}S" ] && cp "${'$'}S" icon.svg; then ICON="${'$'}BASE/icon.svg"; fi
  fi
fi
if [ -z "${'$'}ICON" ] && [ -e app/.DirIcon ] && cp -L app/.DirIcon icon.png 2>/dev/null; then ICON="${'$'}BASE/icon.png"; fi
# AppImage berbasis sharun (anylinux): di proot, hardlink hasil ekstraksi menjadi symlink .proot.l2s.* dan sharun
# salah membaca namanya sendiri (gagal "Failed to check ELF class"). Lewati sharun: jalankan biner asli lewat
# ld-linux bawaan AppImage dengan peluncur buatan sendiri.
LAUNCH=""
LDSO="${'$'}(ls app/shared/lib/ld-linux-*.so.* 2>/dev/null | head -n 1)"
if { [ -e app/sharun ] || [ -L app/sharun ]; } && [ -n "${'$'}LDSO" ] && [ -d app/shared/bin ]; then
  BIN="${'$'}(key Exec)"; BIN="${'$'}{BIN%% *}"; BIN="${'$'}{BIN##*/}"
  [ -n "${'$'}BIN" ] && [ -f "app/shared/bin/${'$'}BIN" ] || BIN="${'$'}(ls app/shared/bin 2>/dev/null | head -n 1)"
  if [ -n "${'$'}BIN" ] && [ -f "app/shared/bin/${'$'}BIN" ]; then
    cat > app/tiny-launch.sh <<'WRAP'
#!/bin/sh
# dibuat installer Tiny Computer: menjalankan biner asli tanpa sharun (lihat install.sh)
APPDIR="${'$'}(cd "${'$'}(dirname "${'$'}0")" && pwd)"
export APPDIR
cd "${'$'}APPDIR/bin" 2>/dev/null || cd "${'$'}APPDIR"
LP="${'$'}APPDIR/shared/lib"
for d in "${'$'}APPDIR"/shared/lib/*/; do
  [ -n "${'$'}(ls "${'$'}d"lib*.so* 2>/dev/null | head -n 1)" ] && LP="${'$'}LP:${'$'}{d%/}"
done
if [ -f "${'$'}APPDIR/.env" ]; then
  while IFS= read -r l; do
    case "${'$'}l" in ''|'#'*) ;; *=*) export "${'$'}l" ;; esac
  done < "${'$'}APPDIR/.env"
fi
exec "${'$'}APPDIR/__LDSO__" --library-path "${'$'}LP" "${'$'}APPDIR/shared/bin/__BIN__" "${'$'}@"
WRAP
    sed -i -e "s|__LDSO__|${'$'}{LDSO#app/}|" -e "s|__BIN__|${'$'}BIN|" app/tiny-launch.sh
    chmod +x app/tiny-launch.sh
    LAUNCH="${'$'}BASE/app/tiny-launch.sh"
  fi
fi
mkdir -p "${'$'}APPS"
{
  echo "[Desktop Entry]"
  echo "Type=Application"
  echo "Name=${'$'}NAME"
  [ -n "${'$'}COMMENT" ] && echo "Comment=${'$'}COMMENT"
  [ -n "${'$'}ICON" ] && echo "Icon=${'$'}ICON"
  echo "Exec=${'$'}{LAUNCH:-${'$'}BASE/app/AppRun} %U"
  echo "Path=${'$'}BASE/app"
  echo "Terminal=false"
  echo "Categories=${'$'}CATS"
  echo "X-Tiny-AppImage=${'$'}ID"
} > "${'$'}APPS/${'$'}ID.desktop"
chmod a+r "${'$'}APPS/${'$'}ID.desktop"
rm -f image.AppImage extract.log
echo "[4/4] Selesai: ${'$'}NAME terpasang."
echo "Buka tab Shortcut (tekan tombol segarkan) lalu ketuk ikonnya untuk menjalankan."
: > "${'$'}BASE/install.done"
"""

    private fun validId(id: String) = isAppImageShortcut(id) && '/' !in id && !id.contains("..") && id.matches(Regex("[A-Za-z0-9._-]+"))

    /** True bila AppImage [id] ada di sistem (folder pasang atau entri .desktop-nya). */
    fun isInstalled(id: String) = validId(id) && (File(baseDir(), id).exists() || File(appsDir(), "$id.desktop").exists())

    /**
     * Menulis skrip uninstall untuk [id] ke `appimages/<id>.uninstall.sh` (di luar folder `<id>/` supaya tidak
     * terhapus oleh dirinya sendiri). Skrip ditulis saat dibutuhkan, jadi AppImage yang dipasang sebelum fitur ini
     * ada juga bisa dihapus. Mengembalikan false bila [id] tidak valid atau sistem Linux belum terpasang.
     */
    fun prepareRemove(id: String): Boolean {
        if (!validId(id) || !Startup.installed || !File(TinyEnv.containerDir(), "home/tiny").isDirectory) return false
        return try {
            baseDir().mkdirs()
            File(baseDir(), "$id$REMOVE_DONE_SUFFIX").delete()
            val script = File(baseDir(), "$id$REMOVE_SCRIPT_SUFFIX")
            script.writeText(uninstallScript(id))
            script.setReadable(true, false)
            script.setExecutable(true, false)
            true
        } catch (_: Exception) {
            false
        }
    }

    /** Baris yang ditulis ke terminal kontainer untuk menjalankan uninstall [id]. */
    fun terminalRemoveCommand(id: String): String = "clear; bash /home/tiny/.local/share/appimages/$id$REMOVE_SCRIPT_SUFFIX"

    /**
     * Skrip uninstall = kebalikan [installScript], langkahnya dibalik dari yang terakhir:
     * - install [4/4] hapus image & tulis `install.done`   -> uninstall [1/4] hapus penanda `install.done`
     * - install [3/4] buat ikon, peluncur, `.desktop`      -> uninstall [2/4] hapus `.desktop`, ikon, peluncur
     * - install [2/4] ekstrak ke `app/` + symlink alias    -> uninstall [3/4] hapus `app/`, alias, sisa ekstraksi
     * - install [1/4] salin image + skrip ke `<id>/`       -> uninstall [4/4] hapus folder `<id>/` dan skrip ini
     * `.desktop` hanya dihapus bila penanda `X-Tiny-AppImage=<id>` cocok, jadi entri lain tidak tersentuh.
     */
    private fun uninstallScript(id: String): String = """#!/bin/bash
ID='$id'
BASE="${'$'}HOME/.local/share/appimages/${'$'}ID"
APPS="${'$'}HOME/.local/share/applications"
SELF="${'$'}HOME/.local/share/appimages/${'$'}ID$REMOVE_SCRIPT_SUFFIX"
DONE="${'$'}HOME/.local/share/appimages/${'$'}ID$REMOVE_DONE_SUFFIX"
DESK="${'$'}APPS/${'$'}ID.desktop"
NAME=""
[ -f "${'$'}DESK" ] && NAME="${'$'}(sed -n 's/^Name=//p' "${'$'}DESK" | head -n 1 | tr -d '\r')"
[ -z "${'$'}NAME" ] && NAME="${'$'}ID"
cd "${'$'}HOME" || exit 1
echo "=== Hapus AppImage: ${'$'}NAME ==="
echo "[1/4] Mencabut penanda pemasangan..."
rm -f "${'$'}BASE/install.done"
echo "[2/4] Menghapus shortcut (menu, ikon, peluncur)..."
if [ -f "${'$'}DESK" ]; then
  if grep -q "^X-Tiny-AppImage=${'$'}ID${'$'}" "${'$'}DESK"; then
    rm -f "${'$'}DESK"
  else
    echo "    LEWATI: ${'$'}DESK bukan milik AppImage ini"
  fi
fi
rm -f "${'$'}BASE/icon.png" "${'$'}BASE/icon.svg" "${'$'}BASE/app/tiny-launch.sh"
echo "[3/4] Menghapus hasil ekstraksi AppImage..."
if [ -d "${'$'}BASE/app" ]; then
  echo "    ${'$'}(find "${'$'}BASE/app" -type f 2>/dev/null | wc -l) berkas dihapus"
fi
# rm -rf tidak mengikuti symlink: aman untuk isi AppImage yang penuh symlink absolut
chmod -R u+w "${'$'}BASE/app" 2>/dev/null
rm -rf "${'$'}BASE/app" "${'$'}BASE/squashfs-root" "${'$'}BASE/AppDir" "${'$'}BASE/extract.log" "${'$'}BASE/image.AppImage"
# alias symlink nama folder ekstraksi lama -> app (dibuat saat pasang)
find "${'$'}BASE" -maxdepth 1 -type l -exec rm -f {} + 2>/dev/null
echo "[4/4] Menghapus folder AppImage..."
chmod -R u+w "${'$'}BASE" 2>/dev/null
rm -rf "${'$'}BASE"
if [ -e "${'$'}BASE" ] || [ -L "${'$'}BASE" ]; then
  echo "GAGAL: ${'$'}BASE tidak bisa dihapus."
  exit 1
fi
echo "Selesai: ${'$'}NAME dihapus."
echo "Kembali ke tab Shortcut..."
: > "${'$'}DONE"
rm -f "${'$'}SELF"
"""

    /**
     * Sisa uninstall yang hanya bisa dikerjakan dari sisi Android setelah skrip selesai: pengaturan per aplikasi,
     * skrip peluncur, log, cache ikon, dan penanda selesai. Dipanggil di thread latar.
     */
    fun finishRemove(id: String) {
        if (!validId(id)) return
        DesktopApps.forgetIcon(id)
        ShortcutPrefs.reset(id)
        File(TinyEnv.dataPath, "tiny/shortcuts/run-${safe(id)}.sh").delete()
        File(TinyEnv.containerDir(), "home/tiny/.vnc/shortcut-${safe(id)}.log").delete()
        File(baseDir(), "$id$REMOVE_DONE_SUFFIX").delete()
        File(baseDir(), "$id$REMOVE_SCRIPT_SUFFIX").delete()
    }

    /** Menghapus AppImage terpasang beserta entri menu, pengaturan, dan isi ekstraksinya. Entri lain tidak disentuh. */
    fun remove(id: String): Boolean {
        if (!isAppImageShortcut(id) || '/' in id || id.contains("..")) return false
        deleteTree(File(baseDir(), id))
        val ok = File(appsDir(), "$id.desktop").delete()
        DesktopApps.forgetIcon(id)
        ShortcutPrefs.reset(id)
        File(TinyEnv.dataPath, "tiny/shortcuts/run-${safe(id)}.sh").delete()
        File(TinyEnv.containerDir(), "home/tiny/.vnc/shortcut-${safe(id)}.log").delete()
        return ok
    }

    /**
     * Menghapus pohon folder TANPA mengikuti symlink: isi AppImage penuh symlink absolut (path tamu) yang
     * dari sisi Android menunjuk ke path milik sistem host, jadi `deleteRecursively` tidak aman dipakai di sini.
     */
    private fun deleteTree(dir: File) {
        val start: Path = dir.toPath()
        if (!Files.exists(start, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return
        try {
            Files.walkFileTree(start, object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(d: Path, attrs: BasicFileAttributes): FileVisitResult {
                    // folder dari ekstraksi bisa tanpa izin tulis; dibuka dulu supaya isinya bisa dihapus
                    d.toFile().setWritable(true, false)
                    return FileVisitResult.CONTINUE
                }
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    runCatching { Files.delete(file) }
                    return FileVisitResult.CONTINUE
                }
                override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = FileVisitResult.CONTINUE
                override fun postVisitDirectory(d: Path, exc: IOException?): FileVisitResult {
                    runCatching { Files.delete(d) }
                    return FileVisitResult.CONTINUE
                }
            })
        } catch (_: IOException) {
        }
    }
}
