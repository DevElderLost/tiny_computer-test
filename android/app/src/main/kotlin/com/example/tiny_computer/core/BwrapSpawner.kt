package com.example.tiny_computer.core

import android.content.Context
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.os.Process
import android.system.Os
import android.util.Base64
import android.util.Log
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Pengganti bubblewrap untuk Flatpak, dipindahkan dari `BwrapSpawner` milik DroidDeck. Android tidak memberi
 * aplikasi user namespace, jadi `bwrap` tidak bisa jalan. Flatpak dipasangi `tiny-bwrap` (`FLATPAK_BWRAP`), yang
 * membaca baris perintah bwrap lalu melakukan salah satu dari dua hal:
 *
 * - sandbox yang tidak memindahkan apa pun (pemicu pasca-pasang `--ro-bind / /`, proksi D-Bus) dijalankan langsung;
 * - selain itu, sandbox dikirim ke sini lewat soket abstrak `<package>.bwrap`, yang hanya melayani uid aplikasi sendiri.
 *
 * Di sini pohonnya dibangun di `cache/bwrap/<n>`: folder akar untuk tautan/folder/berkas tingkat atas, folder
 * sementara untuk `--tmpfs` di dalam bind, dan setiap `--bind` diterjemahkan dari path guest ke path host. Hasilnya
 * dijalankan sebagai proot tersendiri ([Launcher.sandboxScript]) di samping proot sesi. Keluaran, pid (untuk
 * `--info-fd`), dan status keluar mengalir kembali lewat soket; shim `tiny-bwrap` yang berhenti mematikan sandbox.
 *
 * Variabel Turnip/Zink dan pilihan Wayland/X11 tidak berasal dari sini: `flatpak run` membersihkan lingkungan, jadi
 * [FlatpakWayland] menaruhnya sebagai opsi `--env`/`--socket` di baris perintah `flatpak run`. Pustaka controller
 * (`LD_PRELOAD`) belum dipasang ke sandbox, jadi aplikasi Flathub belum melihat controller virtual.
 */
object BwrapSpawner {
    private const val TAG = "TinyBwrap"
    fun socket(context: Context): String = "${context.packageName}.bwrap"
    private val seq = AtomicInteger()
    @Volatile private var server: LocalServerSocket? = null

    /**
     * PID proot tiap sandbox yang sedang berjalan (id sandbox -> pid). Sandbox dijalankan dari proses aplikasi ini,
     * BUKAN dari pohon proot sesi, jadi pemindai pohon sesi tidak menemukannya. [Launcher] memakainya untuk
     * menutup aplikasi Flatpak dengan halus (konfigurasi sempat tersimpan) sebelum sandbox dimatikan paksa.
     */
    private val running = java.util.concurrent.ConcurrentHashMap<Int, Int>()
    fun sandboxPids(): List<Int> = running.values.toList()

    @Synchronized
    fun start(context: Context) {
        if (server != null) return
        val app = context.applicationContext
        val name = socket(app)
        val socket = try {
            LocalServerSocket(name)
        } catch (e: IOException) {
            Log.e(TAG, "tidak bisa mendengarkan $name", e)
            return
        }
        server = socket
        File(app.cacheDir, "bwrap").deleteRecursively()
        Thread({
            while (true) {
                val client = try { socket.accept() } catch (e: IOException) { Log.e(TAG, "accept", e); break }
                Thread({ serve(app, client) }, "bwrap-${seq.get() + 1}").apply { isDaemon = true; start() }
            }
        }, "bwrap-server").apply { isDaemon = true; start() }
    }

    private fun serve(context: Context, client: LocalSocket) {
        val id = seq.incrementAndGet()
        val dir = File(context.cacheDir, "bwrap/$id")
        client.use {
            val out = DataOutputStream(client.outputStream)
            try {
                val peer = client.peerCredentials
                if (peer.uid != Process.myUid()) {
                    Log.w(TAG, "sandbox untuk uid ${peer.uid} ditolak")
                    return
                }
                val input = DataInputStream(client.inputStream)
                val kind = input.readUnsignedByte()
                val body = ByteArray(input.readInt()).also { input.readFully(it) }
                if (kind != 'R'.code) return
                val request = JSONObject(String(body, Charsets.UTF_8))
                val plan = startable(plan(context, request, dir))
                plan.notes.forEach { note -> synchronized(out) { frame(out, 'E', "sandbox $id: $note".toByteArray()) } }
                run(plan, client, out, id)
            } catch (e: Exception) {
                Log.e(TAG, "sandbox $id", e)
                runCatching {
                    frame(out, 'E', (e.message ?: e.toString()).toByteArray())
                    frame(out, 'X', "1".toByteArray())
                }
            } finally {
                running.remove(id)
                runCatching { rescueThrowawayData(dir) }
                dir.deleteRecursively()
            }
        }
    }

    class Plan(val root: File, val binds: List<Bind>, val cwd: String, val argv: List<String>, val env: Map<String, String>, val notes: List<String> = emptyList())

    /** Satu bind proot: [host] di Android menjadi [guest] di dalam sandbox. */
    data class Bind(val host: String, val guest: String) {
        val spec: String get() = if (host == guest) host else "$host:$guest"
    }

    internal fun under(path: String, dir: String): Boolean =
        dir == "/" || path == dir || path.startsWith(if (dir.endsWith("/")) dir else "$dir/")

    /** Path host untuk path [guest] kontainer: lewat bind sesi yang paling spesifik, atau langsung di rootfs. */
    internal fun hostPath(sessionBinds: List<Bind>, rootfs: String, guest: String): String {
        val bind = sessionBinds.filter { under(guest, it.guest) }.maxByOrNull { it.guest.length }
            ?: return rootfs.trimEnd('/') + guest
        val rest = guest.substring(bind.guest.length).trimStart('/')
        return if (rest.isEmpty()) bind.host else bind.host.trimEnd('/') + "/" + rest
    }

    internal fun bindsFor(sessionBinds: List<Bind>, rootfs: String, src: String, dest: String): List<Bind> {
        val out = ArrayList<Bind>()
        out.add(Bind(hostPath(sessionBinds, rootfs, src), dest))
        for (b in sessionBinds.sortedBy { it.guest.length }) {
            if (b.guest != src && under(b.guest, src)) {
                out.add(Bind(b.host, (dest.trimEnd('/') + "/" + b.guest.substring(src.length).trimStart('/'))))
            }
        }
        return out
    }

    /**
     * Mount milik sesi kontainer (lihat perintah boot) yang perlu diterjemahkan saat flatpak menunjuk path guest.
     *
     * `/data`, `/system`, `/apex`, `/storage` adalah mount identitas milik Android. Aplikasi dengan
     * `--filesystem=host` (mis. Azahar: `host:ro`) membuat flatpak men-bind setiap isi `/` guest ke path yang sama,
     * termasuk `/data`. Tanpa entri ini `/data` diterjemahkan ke `<rootfs>/data` (folder kosong) dan menimpa
     * `/data` Android di sandbox, sehingga symlink `.l2s.*` (target path host `/data/user/0/...`) menggantung dan
     * proot gagal dengan "'/usr/bin/env' not found".
     */
    private fun sessionBinds(): List<Bind> = listOf(
        Bind("/proc", "/proc"),
        Bind("/dev", "/dev"),
        Bind("/sys", "/sys"),
        Bind("/data", "/data"),
        Bind("/system", "/system"),
        Bind("/apex", "/apex"),
        Bind("/storage", "/storage"),
        Bind("/storage/self/primary", "/media/sd"),
        Bind(File(TinyEnv.containerDir(), "tmp").path, "/dev/shm"),
        Bind(File(TinyEnv.dataPath, "tiny").path, "/home/tiny/.local/share/tiny"),
    ) + homeMounts()

    /**
     * Mount folder rumah milik sesi (perintah boot): Download, Documents, Pictures, Music, Videos, DCIM, Public
     * menunjuk ke penyimpanan Android. Mount itu hanya ada di proot sesi; tanpa entri ini `--filesystem=home` (atau
     * `host`) di Flatpak hanya melihat folder kosong di rootfs, sehingga dialog berkas cuma menampilkan `Desktop`.
     * Nama folder mengikuti bahasa (boot Mandarin: 下载, 文档, ...; selain itu: Downloads, Documents, ...).
     */
    private fun padBinds(): List<Bind> {
        if (!File(TinyEnv.dataPath, "tiny/extra/libfakeinput.so").isFile) return emptyList()
        val pad = Launcher.padPeek() ?: return emptyList()
        val out = ArrayList<Bind>()
        for (m in pad.mounts) {
            val spec = m.removePrefix("--mount=")
            if (':' !in spec) continue
            val host = spec.substringBeforeLast(':')
            val guest = spec.substringAfterLast(':')
            if (File(host).exists() && guest.startsWith("/")) out.add(Bind(host, guest))
        }
        return out
    }

    private fun homeMounts(): List<Bind> {
        val out = ArrayList<Bind>()
        val data = TinyEnv.dataPath
        val container = TinyEnv.containerDir().path
        for (tok in TinyEnv.defaultBoot.split(Regex("\\s+"))) {
            if (!tok.startsWith("--mount=")) continue
            val spec = tok.removePrefix("--mount=")
            if (':' !in spec) continue
            val guest = spec.substringAfterLast(':')
            if (!guest.startsWith("/home/tiny/") || guest == "/home/tiny/.local/share/tiny") continue
            val host = spec.substringBeforeLast(':').replace("\$DATA_DIR", data).replace("\$CONTAINER_DIR", container)
            if (host.contains('$') || !File(host).exists()) continue
            out.add(Bind(host, guest))
        }
        return out
    }

    internal fun plan(context: Context, request: JSONObject, dir: File): Plan {
        val rootfs = TinyEnv.containerDir().path
        val session = sessionBinds()
        val root = File(dir, "root").apply { mkdirs() }
        val binds = ArrayList<Bind>()
        var scratch = 0
        fun addBind(b: Bind) {
            binds.removeAll { it.guest == b.guest }
            binds.add(b)
        }
        fun coveringBind(dest: String) = binds.filter { under(dest, it.guest) }.maxByOrNull { it.guest.length }
        fun newScratch(): File = File(dir, "x${scratch++}")
        fun writable(dest: String): File? {
            val cover = coveringBind(dest) ?: return inRoot(root, dest)
            if (!under(cover.host, dir.path)) return null
            return inRoot(File(cover.host), dest.substring(cover.guest.length))
        }
        val notes = ArrayList<String>()
        val ops = request.getJSONArray("ops")
        for (i in 0 until ops.length()) {
            val op = ops.getJSONObject(i)
            val dest = op.optString("dest")
            when (op.getString("t")) {
                "bind" -> {
                    val src = op.getString("src")
                    val all = bindsFor(session, rootfs, src, dest)
                    if (!File(all.first().host).exists()) {
                        if (!op.optBoolean("try")) {
                            Log.w(TAG, "sumber sandbox $src tidak ada; dilewati")
                            notes.add("bind $src -> $dest DILEWATI: ${all.first().host} tidak ada")
                        }
                        continue
                    }
                    all.forEach { addBind(it) }
                }
                "proc" -> bindsFor(session, rootfs, "/proc", dest).forEach { addBind(it) }
                "dev" -> bindsFor(session, rootfs, "/dev", dest).forEach { addBind(it) }
                "dir" -> {
                    // flatpak menutup ~/.var/app dengan tmpfs agar aplikasi lain tak terlihat, lalu mem-bind ~/.var/app/<id>.
                    // Rumah sudah dibagikan (--filesystem=home), jadi isolasi itu tidak berarti apa-apa di sini, sedangkan
                    // folder sementara di induk bind data aplikasi bergantung pada urutan bind bertingkat proot: bila
                    // meleset, konfigurasi tertulis ke folder sandbox sementara dan hilang begitu aplikasi ditutup.
                    if (op.optBoolean("empty") && (dest == "/home/tiny/.var" || dest == "/home/tiny/.var/app")) {
                        notes.add("tmpfs $dest dilewati (data aplikasi tetap di ~/.var/app asli)")
                        continue
                    }
                    val perms = op.optInt("perms", 0x1ed)
                    val here = writable(dest)
                    when {
                        op.optBoolean("empty") && coveringBind(dest) != null ->
                            addBind(Bind(newScratch().apply { mkdirs(); chmod(this, perms) }.path, dest))
                        here != null && (here.isDirectory || here.mkdirs()) -> chmod(here, perms)
                        File(hostPath(binds, root.path, dest)).isDirectory -> {}
                        else -> addBind(Bind(newScratch().apply { mkdirs(); chmod(this, perms) }.path, dest))
                    }
                }
                "file" -> {
                    val data = Base64.decode(op.getString("data"), Base64.DEFAULT)
                    val here = writable(dest)
                    val f = if (here != null && here.parentFile?.let { it.isDirectory || it.mkdirs() } == true) here
                            else newScratch().also { addBind(Bind(it.path, dest)) }
                    f.writeBytes(data)
                    chmod(f, op.optInt("perms", 0x1b6))
                }
                "symlink" -> {
                    val target = op.getString("target")
                    val link = writable(dest)
                    if (link != null) {
                        link.parentFile?.mkdirs()
                        if (!Files.isSymbolicLink(link.toPath()) && !link.exists()) Files.createSymbolicLink(link.toPath(), java.nio.file.Paths.get(target))
                    } else {
                        val abs = if (target.startsWith("/")) target else normalize(dest.substringBeforeLast('/') + "/" + target)
                        val host = hostPath(binds, root.path, abs)
                        if (File(host).exists()) addBind(Bind(host, dest)) else Log.w(TAG, "symlink $dest -> $target: tidak ada isinya; dilewati")
                    }
                }
            }
        }
        // Data aplikasi Flatpak (~/.var/app/<id>: konfigurasi, simpanan, cache) HARUS berasal dari rootfs yang
        // persisten. Dipasang terakhir dan tanpa bind sementara di atasnya, apa pun urutan bind dari flatpak.
        flatpakAppId(request)?.let { appId ->
            val guestDir = "/home/tiny/.var/app/$appId"
            val hostDir = File(hostPath(session, rootfs, guestDir))
            if (hostDir.isDirectory || hostDir.mkdirs()) {
                binds.removeAll { under(it.guest, guestDir) && under(it.host, dir.path) }
                addBind(Bind(hostDir.path, guestDir))
                notes.add("data aplikasi persisten: ${hostDir.path} -> $guestDir")
            } else notes.add("data aplikasi: GAGAL membuat ${hostDir.path}; konfigurasi tidak akan tersimpan")
        } ?: notes.add("data aplikasi: id Flatpak tidak ditemukan di permintaan sandbox")
        notes.add("bind di ~/.var: " + binds.filter { under(it.guest, "/home/tiny/.var") }
            .joinToString(" ") { "${it.guest}=${it.host}" }.ifEmpty { "(tidak ada)" })
        // `/data` Android harus tetap /data asli: symlink .l2s.* (hardlink proot --link2symlink) menunjuk ke path
        // host `/data/user/0/...`. Bind flatpak apa pun di /data atau di bawahnya (mis. --filesystem=host) dibuang.
        binds.removeAll { under(it.guest, "/data") }
        addBind(Bind("/data", "/data"))
        // Audio langsung: soket server AAudio (container/tmp/.tinyaudio) dan ~/.asoundrc dimasukkan ke sandbox. Soket
        // /tmp milik sandbox adalah folder sementara, jadi tanpa bind ini plugin tinyaudio tidak menemukan servernya;
        // dan ~/.asoundrc harus ikut walau tingkat preflight membuang bind /home/tiny.
        if (Launcher.directAudioActive()) {
            val sock = TinyAudio.hostSocket()
            if (File(sock).exists()) {
                addBind(Bind(sock, TinyAudio.GUEST_SOCKET))
                notes.add("audio langsung: ${TinyAudio.GUEST_SOCKET}")
            } else notes.add("audio langsung: soket $sock belum ada")
            val rc = FlatpakWayland.asoundrcFile()
            if (rc.isFile && rc.readText().startsWith(Launcher.ASOUND_MARK)) addBind(Bind(rc.path, "/home/tiny/.asoundrc"))
        }
        // Controller virtual (libfakeinput): /dev/input dan sysfs palsu yang sama dengan proot sesi. Tanpa ini aplikasi
        // Flatpak (mis. Azahar > Controls) tidak melihat pad apa pun dan pemetaan tombol tidak pernah menerima masukan.
        val pad = padBinds()
        pad.forEach { addBind(it) }
        if (pad.isNotEmpty()) notes.add("controller: " + pad.joinToString(" ") { it.guest })
        // flatpak-spawn pengganti: runtime freedesktop 25.08+ (mis. PPSSPP) memuat gambar lewat glycin, dan glycin
        // di dalam sandbox yang punya /.flatpak-info menjalankan pemuatnya lewat `flatpak-spawn --sandbox`, yang
        // butuh helper sesi Flatpak lewat D-Bus. Di sini helper itu tidak ada: pemuat SVG keluar dengan status 1,
        // GTK gagal memuat ikon `image-missing` dan aplikasi abort (sinyal 6) sebelum membuka satu jendela pun,
        // sehingga layarnya tinggal hitam. Pengganti ini hanya menjalankan perintahnya langsung.
        run {
            val shim = File(dir, "flatpak-spawn")
            try {
                shim.writeText(SPAWN_SHIM)
                chmod(shim, 0x1ed)
                addBind(Bind(shim.path, "/usr/bin/flatpak-spawn"))
                notes.add("flatpak-spawn: pengganti langsung dipasang di /usr/bin/flatpak-spawn")
            } catch (e: Exception) {
                Log.w(TAG, "flatpak-spawn pengganti gagal dibuat", e)
                notes.add("flatpak-spawn: GAGAL membuat pengganti (${e.message})")
            }
        }
        notes.add("bind di /home: " + binds.filter { under(it.guest, "/home") }.joinToString(" ") { it.guest }.ifEmpty { "(tidak ada)" })
        notes.add("${binds.size} bind; /usr=${binds.firstOrNull { it.guest == "/usr" }?.host ?: "TIDAK ADA"}")
        notes.add(probe(binds, root, "/usr/bin/env"))
        if (binds.any { it.guest == "/proc" }) {
            val generic = genericCpuinfo(context)
            if (generic != null) {
                addBind(Bind(generic.path, "/proc/cpuinfo"))
                notes.add("cpuinfo: nomor inti dibuang (inti ARMv9 dengan SVE2 palsu)")
            } else notes.add("cpuinfo: asli (bukan inti ARMv9); LLVM/JIT memakai CPU sebenarnya")
        }
        val argv = request.getJSONArray("argv").let { a -> (0 until a.length()).map { a.getString(it) } }
        val env = LinkedHashMap<String, String>()
        request.optJSONObject("env")?.let { e -> e.keys().forEach { k -> env[k] = e.getString(k) } }
        browserSandboxes(env)
        return Plan(root, binds, request.optString("cwd", "/").ifEmpty { "/" }, argv, env, notes)
    }

    private val APP_ID = Regex("[A-Za-z0-9_][A-Za-z0-9_.-]*")

    private const val D = "$"

    /**
     * Pengganti `flatpak-spawn`: melewati opsi sandbox/bus (`--sandbox`, `--watch-bus`, `--forward-fd=N`, ...),
     * menerapkan `--directory=` dan `--env=`, lalu menjalankan perintahnya langsung. Berkas yang diteruskan lewat
     * `--forward-fd` tetap terbuka karena proses ini hanya `exec`.
     */
    private val SPAWN_SHIM = """#!/bin/sh
while [ ${D}# -gt 0 ]; do
  case "${D}1" in
    --env=*) export "${D}{1#--env=}" ;;
    --directory=*) cd "${D}{1#--directory=}" 2>/dev/null || true ;;
    --) shift; break ;;
    --*) ;;
    *) break ;;
  esac
  shift
done
exec "${D}@"
"""

    /** Id aplikasi Flatpak dari `FLATPAK_ID` (--setenv) atau baris `name=` di `/.flatpak-info`; null bila tak ada. */
    private fun flatpakAppId(request: JSONObject): String? {
        request.optJSONObject("env")?.optString("FLATPAK_ID")?.takeIf { APP_ID.matches(it) }?.let { return it }
        val ops = request.optJSONArray("ops") ?: return null
        for (i in 0 until ops.length()) {
            val op = ops.optJSONObject(i) ?: continue
            if (op.optString("t") != "file" || op.optString("dest") != "/.flatpak-info") continue
            val text = runCatching { String(Base64.decode(op.getString("data"), Base64.DEFAULT), Charsets.UTF_8) }.getOrNull() ?: continue
            text.lineSequence().firstOrNull { it.startsWith("name=") }?.substringAfter('=')?.trim()
                ?.takeIf { APP_ID.matches(it) }?.let { return it }
        }
        return null
    }

    /**
     * Setelah sandbox ditutup: berkas yang ditulis aplikasi ke `~/.var/app/<id>` DI DALAM folder sandbox sementara
     * (bukan rootfs) akan terhapus bersama folder itu. Itu persis gejala "pengaturan kembali ke awal". Selamatkan
     * ke rootfs (berkas lebih baru menang) dan catat peringatan di log Shortcut supaya penyebabnya terlihat.
     */
    private fun rescueThrowawayData(dir: File) {
        val apps = File(dir, "root/home/tiny/.var/app").listFiles()?.filter { it.isDirectory } ?: return
        for (app in apps) {
            val files = app.walkTopDown().filter { it.isFile }.toList()
            if (files.isEmpty()) continue
            val target = File(TinyEnv.containerDir(), "home/tiny/.var/app/${app.name}")
            var saved = 0
            for (f in files) {
                val dst = File(target, f.relativeTo(app).path)
                if (dst.exists() && dst.lastModified() >= f.lastModified()) continue
                runCatching { dst.parentFile?.mkdirs(); f.copyTo(dst, overwrite = true); saved++ }
            }
            Launcher.logApp("flatpak", "PERINGATAN: ${app.name} menulis ${files.size} berkas ke folder sandbox sementara, " +
                "bukan ke ~/.var/app asli; $saved berkas diselamatkan ke ${target.path}")
        }
    }

    /**
     * Sandbox proses anak peramban: Firefox mematikan sandbox-nya sendiri, Chromium/Electron memakai strategi mimic
     * zypak. Portal Flatpak menolak pemanggil yang `/proc/<pid>/root`-nya tidak punya `.flatpak-info`, dan akar
     * sandbox proot adalah akar host.
     */
    private fun browserSandboxes(env: MutableMap<String, String>) {
        env.putIfAbsent("ZYPAK_ZYGOTE_STRATEGY_SPAWN", "0")
        env.putIfAbsent("QTWEBENGINE_DISABLE_SANDBOX", "1")
        if (env.containsKey("DISPLAY")) env["XDG_SESSION_TYPE"] = "x11"
        else if (env.containsKey("WAYLAND_DISPLAY")) env["XDG_SESSION_TYPE"] = "wayland"
        for (name in listOf("MOZ_DISABLE_CONTENT_SANDBOX", "MOZ_DISABLE_GMP_SANDBOX", "MOZ_DISABLE_RDD_SANDBOX",
                            "MOZ_DISABLE_SOCKET_PROCESS_SANDBOX", "MOZ_DISABLE_UTILITY_SANDBOX", "MOZ_SHM_NO_SEALS")) {
            env.putIfAbsent(name, "1")
        }
    }

    /** `CPU part` inti ARM ARMv9 (Cortex-A510/A710/X2/A715/X3/A520/A720/X4/X925/A725) yang dilaporkan punya SVE2. */
    private val ARMV9_PARTS = setOf(0xd46, 0xd47, 0xd48, 0xd4d, 0xd4e, 0xd80, 0xd81, 0xd82, 0xd85, 0xd87)

    private fun needsGenericCpuinfo(lines: List<String>): Boolean {
        var implementer = -1
        for (line in lines) {
            val value = line.substringAfter(':', "").trim().removePrefix("0x")
            if (line.startsWith("CPU implementer")) implementer = value.toIntOrNull(16) ?: -1
            else if (line.startsWith("CPU part")) {
                val part = value.toIntOrNull(16) ?: continue
                // 0x41 = ARM; 0x51 part 0x001 = Qualcomm Oryon
                if ((implementer == 0x41 && part in ARMV9_PARTS) || (implementer == 0x51 && part == 0x001)) return true
            }
        }
        return false
    }

    /**
     * `/proc/cpuinfo` tanpa nomor bagian inti, HANYA untuk inti ARMv9: inti ARMv9 Snapdragon membuat LLVM mengira
     * SVE2 ada padahal Qualcomm mematikannya, dan shader pertama llvmpipe mati dengan SIGILL. Pada inti lain
     * (Cortex-A53/A73/A75/A76/A78 dst.) nomor itu dibiarkan: tanpanya LLVM memilih CPU "generic" sehingga llvmpipe,
     * JIT emulator, dan pustaka yang membaca cpuinfo jalan lebih lambat di sandbox Flatpak dibanding AppImage
     * (proot sesi tidak mengubah cpuinfo). Mengembalikan null bila berkas asli boleh dipakai.
     */
    private fun genericCpuinfo(context: Context): File? = try {
        val lines = File("/proc/cpuinfo").readLines()
        if (!needsGenericCpuinfo(lines)) null
        else {
            val out = File(context.cacheDir, "cpuinfo-generic")
            val text = lines
                .filterNot { it.startsWith("CPU implementer") || it.startsWith("CPU part") || it.startsWith("CPU variant") || it.startsWith("CPU revision") }
                .joinToString("\n", postfix = "\n")
            if (!out.isFile || out.readText() != text) {
                val staged = File.createTempFile("cpuinfo", null, context.cacheDir)
                staged.writeText(text)
                if (!staged.renameTo(out)) staged.delete()
            }
            out
        }
    } catch (e: Exception) {
        Log.w(TAG, "cpuinfo", e); null
    }

    /** Tingkat percobaan terakhir yang lolos preflight; sandbox berikutnya mulai dari sini (hanya naik). */
    @Volatile private var startTier = 0
    private const val MAX_TIER = 4

    /** Folder akar guest yang hanya bermakna di host; bind-nya (mis. dari `--filesystem=host`) tidak dibutuhkan aplikasi. */
    private val HOST_EXPORTS = listOf("/system", "/apex", "/storage", "/media", "/mnt", "/opt", "/srv", "/boot", "/root")

    /** Bind minimum agar aplikasi Flatpak bisa berjalan (dipakai tingkat terakhir). */
    private val ESSENTIAL = listOf("/usr", "/app", "/etc", "/run", "/dev", "/proc", "/sys", "/tmp", "/var",
        "/lib", "/lib32", "/lib64", "/bin", "/sbin", "/data", "/home/tiny/.var",
        // folder extra (plugin ALSA tinyaudio, ICD Turnip, libfakeinput) dan ~/.asoundrc: tanpa ini tingkat 3-4
        // membuang bind-nya dan pustaka/konfigurasi di path guest itu hilang dari sandbox
        "/home/tiny/.local/share/tiny", "/home/tiny/.asoundrc")

    /**
     * `/usr/bin/env` di runtime Flatpak adalah rantai symlink `.proot.l2s.*` (hasil `--link2symlink` saat checkout
     * ostree). Sandbox dengan banyak bind (80+, mis. `--filesystem=host`) gagal dengan "proot error:
     * '/usr/bin/env' not found" padahal sandbox kecil (ldconfig, 12 bind) dengan rantai yang sama lolos. Bind berkas
     * nyata hasil rantai itu langsung ke `/usr/bin/env` supaya proot tidak perlu menelusuri symlink saat memulai.
     */
    private fun directEnv(binds: List<Bind>): Bind? {
        val usr = binds.firstOrNull { it.guest == "/usr" } ?: return null
        val real = try { File(usr.host, "bin/env").canonicalFile } catch (e: IOException) { return null }
        if (!real.isFile) return null
        if (!real.canExecute()) runCatching { real.setExecutable(true, false) }
        return Bind(real.path, "/usr/bin/env")
    }

    /**
     * Isi rumah asli (`/home/tiny` di rootfs) sebagai bind per entri, dipakai saat bind seluruh rumah dibuang
     * (tingkat 2-3). Tanpa ini sandbox hanya melihat folder sesi (Documents, Downloads, Music, Photos, Pictures,
     * Public, Videos) dan semua folder lain di rumah (Desktop, folder buatan sendiri) hilang dari dialog berkas
     * aplikasi Flathub. Entri tersembunyi (`.config`, `.local`, `.var`, ...) sengaja dilewati: data aplikasi
     * sudah dipasang terpisah, dan bind di dalam pohon yang sama dengan runtime Flatpak (`~/.local/share/flatpak`)
     * adalah pola yang membuat proot gagal menemukan `/usr/bin/env`. Bind yang sudah ada tidak ditimpa.
     */
    private fun homeChildren(plan: Plan, binds: List<Bind>): List<Bind> {
        val home = plan.binds.firstOrNull { it.guest == "/home/tiny" } ?: return emptyList()
        val kids = File(home.host).listFiles()?.sortedBy { it.name } ?: return emptyList()
        val out = ArrayList<Bind>()
        for (k in kids) {
            if (k.name.startsWith(".") || !k.exists()) continue
            val guest = "/home/tiny/" + k.name
            if (binds.any { it.guest == guest } || binds.any { under(it.guest, guest) }) continue
            out.add(Bind(k.path, guest))
            if (out.size >= MAX_HOME_CHILDREN) break
        }
        return out
    }

    private const val MAX_HOME_CHILDREN = 64

    /**
     * Tingkat 0 = rencana apa adanya; 1 = + `/usr/bin/env` langsung; 2 = + buang bind ekspor host dan bind seluruh
     * rumah (`/home`, `/home/tiny`) lalu ganti dengan bind per entri isi rumah ([homeChildren]) bila [children];
     * 3 = hanya bind yang esensial + folder rumah sesi (Download, Documents, ...) + entri rumah bila [children];
     * 4 = hanya bind yang esensial. Makin tinggi makin sedikit yang dibagikan ke aplikasi, tetapi makin kecil peluang
     * proot gagal. Log 2026-10-17 (Azahar, 89 bind): tingkat 0-2 gagal dan hanya tingkat esensial lolos, sehingga
     * `/home/tiny` kosong; bind seluruh rumah di atas folder sesi dibuang dulu, folder Android-nya dipertahankan.
     * Log 2026-10-22 (VS Code): tingkat 2 lolos, tetapi dengan bind rumah dibuang, `/home/tiny` hanya berisi folder
     * sesi dan folder lain tidak muncul; karena itu isi rumah dikembalikan per entri.
     */
    private fun variant(plan: Plan, tier: Int, children: Boolean = true): Plan {
        if (tier == 0) return plan
        var binds: List<Bind> = plan.binds
        if (tier >= 2) binds = binds.filterNot { b -> b.guest == "/home" || b.guest == "/home/tiny" || HOST_EXPORTS.any { under(b.guest, it) } }
        if (tier >= 3) {
            val keepHome = if (tier == 3) homeMounts().map { it.guest }.toSet() else emptySet()
            binds = binds.filter { b -> ESSENTIAL.any { under(b.guest, it) } || b.guest in keepHome }
        }
        if (children && tier in 2..3) binds = binds + homeChildren(plan, binds)
        directEnv(binds)?.let { env -> binds = binds.filterNot { it.guest == "/usr/bin/env" } + env }
        return Plan(plan.root, binds, plan.cwd, plan.argv, plan.env, plan.notes)
    }

    /**
     * Menjalankan proot yang sama persis dengan bind rencana, tetapi hanya `env -i` (tanpa perintah), untuk
     * memastikan proot bisa menemukan `/usr/bin/env`. Mengembalikan null bila lolos, selain itu pesan galatnya.
     */
    private fun preflight(plan: Plan): String? = try {
        val script = Launcher.sandboxScript(plan.root, "/", plan.binds.map { it.spec }, emptyMap(), emptyList())
        val process = ProcessBuilder("/system/bin/sh", "-c", script)
            .redirectErrorStream(true)
            .redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
            .start()
        val text = StringBuilder()
        val reader = Thread({
            try { process.inputStream.bufferedReader().forEachLine { if (text.length < 4000) text.appendLine(it) } }
            catch (e: IOException) { /* proses dimatikan */ }
        }, "bwrap-preflight").apply { isDaemon = true; start() }
        if (!process.waitFor(10, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            null // menggantung bukan tanda galat pencarian program; jangan hukum rencananya
        } else {
            reader.join(500)
            if (process.exitValue() == 0) null
            else text.lines().map { it.trim() }.firstOrNull { it.startsWith("proot error") || it.startsWith("proot info") }
                ?: text.lines().map { it.trim() }.lastOrNull { it.isNotEmpty() && !it.startsWith("TINYPID=") && !it.startsWith("proot warning") }
                ?: "keluar ${process.exitValue()}"
        }
    } catch (e: Exception) {
        Log.w(TAG, "preflight", e); null
    }

    /**
     * Rencana pertama (mulai dari [startTier]) yang lolos [preflight]; bila semuanya gagal, rencana awal beserta
     * diagnosisnya. Di tingkat 2-3 dicoba dulu dengan isi rumah per entri, lalu tanpa itu bila proot menolaknya.
     */
    private fun startable(plan: Plan): Plan {
        val log = ArrayList<String>()
        for (tier in startTier..MAX_TIER) {
            for (children in if (tier in 2..3) listOf(true, false) else listOf(false)) {
                val candidate = variant(plan, tier, children)
                val error = preflight(candidate)
                if (error == null) {
                    startTier = tier
                    val label = if (tier in 2..3) (if (children) ", isi rumah per entri" else ", TANPA isi rumah") else ""
                    if (tier > 0 || log.isNotEmpty()) log.add("preflight: tingkat $tier lolos (${candidate.binds.size} bind$label)")
                    log.add("bind di /home akhir: " + candidate.binds.filter { under(it.guest, "/home") }.joinToString(" ") { it.guest }.ifEmpty { "(tidak ada)" })
                    return Plan(candidate.root, candidate.binds, candidate.cwd, candidate.argv, candidate.env, plan.notes + log)
                }
                log.add("preflight tingkat $tier${if (tier in 2..3) (if (children) " (+isi rumah)" else " (tanpa isi rumah)") else ""} gagal: $error")
            }
        }
        log.add("preflight: semua tingkat gagal; memakai rencana awal. bind: " + plan.binds.joinToString(" ") { it.spec }.take(3000))
        return Plan(plan.root, plan.binds, plan.cwd, plan.argv, plan.env, plan.notes + log)
    }

    /** Telusuri [guest] lewat bind sandbox seperti proot, hop demi hop, untuk log diagnosis. */
    private fun probe(binds: List<Bind>, root: File, guest: String): String {
        val steps = ArrayList<String>()
        var path = hostPath(binds, root.path, guest)
        for (hop in 0 until 6) {
            val p = File(path).toPath()
            if (!Files.isSymbolicLink(p)) {
                val f = File(path)
                steps.add("$path " + when {
                    !f.exists() -> "(TIDAK ADA)"
                    f.isFile && !f.canExecute() -> "(ada, TIDAK bisa dieksekusi)"
                    else -> "(ada)"
                })
                return "$guest => " + steps.joinToString(" => ")
            }
            val target = try { Files.readSymbolicLink(p).toString() } catch (e: Exception) { return "$guest => $path (readlink gagal: ${e.message})" }
            if (target.startsWith("/")) {
                val cover = binds.filter { under(target, it.guest) }.maxByOrNull { it.guest.length }
                steps.add("$path -> $target [" + (if (cover == null) "tanpa bind (opsi proot)" else "bind ${cover.guest}=${cover.host}") + "]")
                path = if (cover == null) target else hostPath(binds, root.path, target)
            } else {
                steps.add("$path -> $target")
                path = File(File(path).parentFile, target).path
            }
        }
        return "$guest => rantai symlink terlalu panjang: " + steps.joinToString(" => ")
    }

    internal fun normalize(path: String): String {
        val out = ArrayList<String>()
        for (part in path.split('/')) when (part) {
            "", "." -> {}
            ".." -> if (out.isNotEmpty()) out.removeAt(out.size - 1)
            else -> out.add(part)
        }
        return "/" + out.joinToString("/")
    }

    private fun inRoot(root: File, guest: String): File? {
        var f = root
        for (part in guest.split('/').filter { it.isNotEmpty() }) {
            if (part == "..") return null
            f = File(f, part)
            if (Files.isSymbolicLink(f.toPath())) return null
        }
        return f
    }

    private fun chmod(f: File, mode: Int) {
        try { Os.chmod(f.path, mode) } catch (e: Exception) { /* sistem berkas tanpa chmod: biarkan */ }
    }

    private fun run(plan: Plan, client: LocalSocket, out: DataOutputStream, id: Int) {
        val script = Launcher.sandboxScript(plan.root, plan.cwd, plan.binds.map { it.spec }, plan.env, plan.argv)
        val process = ProcessBuilder("/system/bin/sh", "-c", script)
            .redirectErrorStream(true)
            .redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
            .start()
        Log.i(TAG, "sandbox $id: ${plan.argv.joinToString(" ")} (${plan.binds.size} bind)")
        Thread({
            try { while (client.inputStream.read() >= 0) { /* shim tidak mengirim apa-apa; EOF = shim berhenti */ } }
            catch (e: IOException) { /* sama dengan EOF */ }
            if (process.isAlive) {
                Log.i(TAG, "sandbox $id: bwrap-nya hilang; sandbox dihentikan")
                process.destroy()
            }
        }, "bwrap-$id-watch").apply { isDaemon = true; start() }
        val buffer = ByteArray(16384)
        var logged = 0
        process.inputStream.use { stream ->
            // baris pertama skrip: TINYPID=<pid proot>; diteruskan sebagai bingkai 'P' untuk --info-fd
            val first = StringBuilder()
            while (true) {
                val b = try { stream.read() } catch (e: IOException) { -1 }
                if (b < 0 || b == '\n'.code) break
                first.append(b.toChar())
            }
            val pid = first.toString().removePrefix("TINYPID=").trim().toIntOrNull()
            if (pid != null) {
                running[id] = pid
                synchronized(out) { frame(out, 'P', pid.toString().toByteArray()) }
            }
            while (true) {
                val n = try { stream.read(buffer) } catch (e: IOException) { -1 }
                if (n < 0) break
                if (logged < 4096) {
                    String(buffer, 0, minOf(n, 4096 - logged)).lines().filter { it.isNotBlank() }
                        .forEach { Log.i(TAG, "sandbox $id: $it") }
                    logged += n
                }
                try { synchronized(out) { frame(out, 'O', buffer.copyOf(n)) } } catch (e: IOException) { break }
            }
        }
        val status = process.waitFor()
        running.remove(id)
        Log.i(TAG, "sandbox $id keluar $status")
        runCatching { synchronized(out) { frame(out, 'X', status.toString().toByteArray()) } }
    }

    private fun frame(out: DataOutputStream, kind: Char, payload: ByteArray) {
        out.writeByte(kind.code)
        out.writeInt(payload.size)
        out.write(payload)
        out.flush()
    }
}
