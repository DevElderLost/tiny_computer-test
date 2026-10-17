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
        // `/data` Android harus tetap /data asli: symlink .l2s.* (hardlink proot --link2symlink) menunjuk ke path
        // host `/data/user/0/...`. Bind flatpak apa pun di /data atau di bawahnya (mis. --filesystem=host) dibuang.
        binds.removeAll { under(it.guest, "/data") }
        addBind(Bind("/data", "/data"))
        notes.add("bind di /home: " + binds.filter { under(it.guest, "/home") }.joinToString(" ") { it.guest }.ifEmpty { "(tidak ada)" })
        notes.add("${binds.size} bind; /usr=${binds.firstOrNull { it.guest == "/usr" }?.host ?: "TIDAK ADA"}")
        notes.add(probe(binds, root, "/usr/bin/env"))
        if (binds.any { it.guest == "/proc" }) genericCpuinfo(context)?.let { addBind(Bind(it.path, "/proc/cpuinfo")) }
        val argv = request.getJSONArray("argv").let { a -> (0 until a.length()).map { a.getString(it) } }
        val env = LinkedHashMap<String, String>()
        request.optJSONObject("env")?.let { e -> e.keys().forEach { k -> env[k] = e.getString(k) } }
        browserSandboxes(env)
        return Plan(root, binds, request.optString("cwd", "/").ifEmpty { "/" }, argv, env, notes)
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

    /**
     * `/proc/cpuinfo` tanpa nomor bagian inti: inti ARMv9 Snapdragon membuat LLVM mengira SVE2 ada padahal
     * Qualcomm mematikannya, dan shader pertama llvmpipe mati dengan SIGILL.
     */
    private fun genericCpuinfo(context: Context): File? = try {
        val out = File(context.cacheDir, "cpuinfo-generic")
        val text = File("/proc/cpuinfo").readLines()
            .filterNot { it.startsWith("CPU implementer") || it.startsWith("CPU part") || it.startsWith("CPU variant") || it.startsWith("CPU revision") }
            .joinToString("\n", postfix = "\n")
        if (!out.isFile || out.readText() != text) {
            val staged = File.createTempFile("cpuinfo", null, context.cacheDir)
            staged.writeText(text)
            if (!staged.renameTo(out)) staged.delete()
        }
        out
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
        "/lib", "/lib32", "/lib64", "/bin", "/sbin", "/data", "/home/tiny/.var")

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
     * Tingkat 0 = rencana apa adanya; 1 = + `/usr/bin/env` langsung; 2 = + buang bind ekspor host dan bind seluruh
     * rumah (`/home`, `/home/tiny`); 3 = hanya bind yang esensial + folder rumah sesi (Download, Documents, ...);
     * 4 = hanya bind yang esensial. Makin tinggi makin sedikit yang dibagikan ke aplikasi, tetapi makin kecil peluang
     * proot gagal. Log 2026-10-17 (Azahar, 89 bind): tingkat 0-2 gagal dan hanya tingkat esensial lolos, sehingga
     * `/home/tiny` kosong; bind seluruh rumah di atas folder sesi dibuang dulu, folder Android-nya dipertahankan.
     */
    private fun variant(plan: Plan, tier: Int): Plan {
        if (tier == 0) return plan
        var binds: List<Bind> = plan.binds
        if (tier >= 2) binds = binds.filterNot { b -> b.guest == "/home" || b.guest == "/home/tiny" || HOST_EXPORTS.any { under(b.guest, it) } }
        if (tier >= 3) {
            val keepHome = if (tier == 3) homeMounts().map { it.guest }.toSet() else emptySet()
            binds = binds.filter { b -> ESSENTIAL.any { under(b.guest, it) } || b.guest in keepHome }
        }
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

    /** Rencana pertama (mulai dari [startTier]) yang lolos [preflight]; bila semuanya gagal, rencana awal beserta diagnosisnya. */
    private fun startable(plan: Plan): Plan {
        val log = ArrayList<String>()
        for (tier in startTier..MAX_TIER) {
            val candidate = variant(plan, tier)
            val error = preflight(candidate)
            if (error == null) {
                startTier = tier
                if (tier > 0 || log.isNotEmpty()) log.add("preflight: tingkat $tier lolos (${candidate.binds.size} bind)")
                return Plan(candidate.root, candidate.binds, candidate.cwd, candidate.argv, candidate.env, plan.notes + log)
            }
            log.add("preflight tingkat $tier gagal: $error")
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
            if (pid != null) synchronized(out) { frame(out, 'P', pid.toString().toByteArray()) }
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
