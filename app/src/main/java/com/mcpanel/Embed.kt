package com.mcpanel

import android.content.Context
import android.os.Environment
import java.io.File
import java.util.zip.ZipInputStream

/**
 * Embedded Linux environment (Termux bootstrap, GPLv3) inside the app's
 * private storage. No separate Termux app, no intents.
 *
 * The applicationId is io.mcpanel on purpose: the bootstrap binaries embed
 * /data/data/com.termux paths and com.termux / io.mcpanel are both exactly
 * 10 characters, so every occurrence can be byte-patched in place without
 * breaking ELF offsets or script shebangs. PREFIX must therefore be spelled
 * exactly /data/data/io.mcpanel/files/usr everywhere.
 *
 * Bootstrap zip ships in assets/bootstrap/. Its root IS the prefix
 * (bin/, lib/, etc/ at top level, plus SYMLINKS.txt) — extracted into
 * files/usr.
 */
object Embed {
    const val BOOTSTRAP_ASSET = "bootstrap/bootstrap-aarch64.zip"
    const val PREFIX_PATH = "/data/data/io.mcpanel/files/usr"
    const val HOME_PATH = "/data/data/io.mcpanel/files/home"

    private val FROM = "com.termux".toByteArray(Charsets.US_ASCII)
    private val TO = "io.mcpanel".toByteArray(Charsets.US_ASCII)

    fun filesDir(ctx: Context): File = ctx.getFilesDir()
    fun prefix(ctx: Context): File = File(PREFIX_PATH)
    fun home(ctx: Context): File = File(HOME_PATH)
    fun script(ctx: Context): File = File(home(ctx), "mcpanel/mc_manager.sh")
    fun serverDir(ctx: Context): File = File(home(ctx), "mcserver")

    fun isBootstrapped(ctx: Context): Boolean =
        File(prefix(ctx), "bin/bash").exists() &&
        File(prefix(ctx), "bin/apt").exists() &&
        File(prefix(ctx), "bin/pkg").exists()

    /**
     * Shared dir: /sdcard/MCPanel if external storage is readable, else
     * app-private Android/data/io.mcpanel/files/MCPanel (script gets the
     * resolved path via MC_SHARED, never a blind /sdcard path).
     */
    fun sharedDir(ctx: Context): File {
        val sd = Environment.getExternalStorageDirectory()
        if (sd != null) {
            val probe = File(sd, "MCPanel/.probe")
            return try {
                probe.parentFile?.mkdirs()
                if (probe.createNewFile() || probe.exists()) {
                    probe.delete()
                    File(sd, "MCPanel")
                } else File(ctx.getExternalFilesDir(null) ?: filesDir(ctx), "MCPanel")
            } catch (_: Exception) {
                File(ctx.getExternalFilesDir(null) ?: filesDir(ctx), "MCPanel")
            }
        }
        return File(ctx.getExternalFilesDir(null) ?: filesDir(ctx), "MCPanel")
    }

    /** Where ServerService captures every script run (stdout+stderr+exit). */
    fun lastRunLog(ctx: Context): File = File(sharedDir(ctx), "last_run.log")

    /** Replace every com.termux with io.mcpanel (same byte length) in place. */
    private fun patchInPlace(b: ByteArray): Boolean {
        var changed = false
        val n = b.size; val m = FROM.size
        var i = 0
        while (i <= n - m) {
            if (b[i] == FROM[0]) {
                var j = 1
                while (j < m && b[i + j] == FROM[j]) j++
                if (j == m) {
                    System.arraycopy(TO, 0, b, i, m)
                    changed = true
                    i += m
                    continue
                }
            }
            i++
        }
        return changed
    }

    /** Extract the bundled bootstrap zip into files/usr:
     *  exec bits restored, every file byte-patched, symlinks rebuilt from
     *  the zip's own SYMLINKS.txt (format: absolute-target←./relative-link). */
    fun installBootstrap(ctx: Context, zipBytes: java.io.InputStream, onProgress: (Int) -> Unit): Boolean {
        val root = prefix(ctx)
        root.mkdirs()
        try {
            var count = 0
            val zin = ZipInputStream(zipBytes.buffered())
            while (true) {
                val e = zin.nextEntry ?: break
                if (e.isDirectory) { zin.closeEntry(); continue }
                val bytes = zin.readBytes()
                zin.closeEntry()
                if (e.name.contains("..") || e.name.startsWith("/")) continue // zip-slip guard
                val out = File(root, e.name)
                out.parentFile?.mkdirs()
                patchInPlace(bytes)
                out.writeBytes(bytes)
                val name = e.name
                val isExec = name.startsWith("bin/") || name.startsWith("libexec/") ||
                        name.startsWith("lib/apt/") || name.endsWith(".sh") ||
                        name == "SYMLINKS.txt"
                try {
                    out.setReadable(true, false)
                    out.setWritable(true, false)
                    if (isExec) out.setExecutable(true, false)
                } catch (_: Exception) {}
                count++
                if (count % 200 == 0) onProgress(count)
            }
            zin.close()
            onProgress(count)
            if (!File(root, "bin/bash").exists() || !File(root, "bin/apt").exists()) return false
            createSymlinks(ctx)
            File(root, "tmp").mkdirs()
            writeProfile(ctx)
            installScript(ctx)
            return true
        } catch (_: Exception) { return false }
    }

    /** Rebuild symlinks from the extracted SYMLINKS.txt (already patched). */
    private fun createSymlinks(ctx: Context) {
        val f = File(prefix(ctx), "SYMLINKS.txt")
        if (!f.exists()) return
        try {
            f.readText().lineSequence().forEach { raw ->
                val line = raw.trim()
                val idx = line.indexOf('←')
                if (idx <= 0) return@forEach
                val target = line.substring(0, idx).trim()
                val linkRel = line.substring(idx + 1).trim().removePrefix("./")
                if (target.isEmpty() || linkRel.isEmpty()) return@forEach
                val link = File(prefix(ctx), linkRel)
                link.parentFile?.mkdirs()
                try { link.delete() } catch (_: Exception) {}
                try {
                    android.system.Os.symlink(target, link.absolutePath)
                } catch (_: Throwable) {
                    val src = File(target)
                    if (src.exists() && !link.exists()) {
                        try { src.copyTo(link, overwrite = true) } catch (_: Exception) {}
                    }
                }
            }
        } catch (_: Exception) {}
    }

    private fun writeProfile(ctx: Context) {
        val etcProfile = File(prefix(ctx), "etc/profile")
        etcProfile.parentFile?.mkdirs()
        if (!etcProfile.exists()) {
            etcProfile.writeText(
                "export PREFIX=" + PREFIX_PATH + "\n" +
                "export HOME=" + HOME_PATH + "\n" +
                "export PATH=" + PREFIX_PATH + "/bin:\$PATH\n" +
                "export TMPDIR=" + PREFIX_PATH + "/tmp\n" +
                "export LD_PRELOAD=" + PREFIX_PATH + "/lib/libtermux-exec-ld-preload.so\n" +
                "export TERM=xterm-256color\n"
            )
        }
    }

    /** Copy mc_manager.sh from res/raw into HOME/mcpanel/ (on every boot so
     *  script fixes ship with app updates). */
    fun installScript(ctx: Context) {
        val target = script(ctx)
        target.parentFile?.mkdirs()
        ctx.resources.openRawResource(R.raw.mc_manager).use { input ->
            target.outputStream().use { input.copyTo(it) }
        }
        target.setExecutable(true, false)
        File(prefix(ctx), "tmp").mkdirs()
    }

    /**
     * Run the manager script with an argv array (no -c quoting pitfalls):
     *   <prefix>/bin/bash <script> <subcommand> [args...]
     * Blocks; returns exit code (-1 = could not start; reason is appended
     * to last_run.log). Streams output lines if requested.
     */
    fun runManager(ctx: Context, subcommand: String, args: List<String> = emptyList(), onLine: ((String) -> Unit)? = null): Int {
        installScript(ctx)
        File(HOME_PATH).mkdirs()
        File(prefix(ctx), "tmp").mkdirs()
        val bash = File(prefix(ctx), "bin/bash")
        val log = lastRunLog(ctx)
        fun diag(msg: String) {
            try { log.parentFile?.mkdirs(); log.appendText("[DIAG] $msg\n") } catch (_: Exception) {}
        }
        if (!bash.exists()) {
            diag("ERROR: bash no existe en ${bash.absolutePath} — el entorno no se extrajo correctamente")
            return -1
        }
        val argv = mutableListOf(
            bash.absolutePath,
            script(ctx).absolutePath,
            subcommand
        ) + args
        fun makePb(withPreload: Boolean): ProcessBuilder {
            val pb = ProcessBuilder(argv)
            pb.directory(home(ctx))
            val pe = pb.environment()
            // keep system env, overlay ours (some OEMs need system vars)
            pe["PATH"] = PREFIX_PATH + "/bin:/system/bin"
            pe["LD_LIBRARY_PATH"] = PREFIX_PATH + "/lib"
            pe["HOME"] = HOME_PATH
            pe["TMPDIR"] = PREFIX_PATH + "/tmp"
            pe["PREFIX"] = PREFIX_PATH
            pe["MC_HOME"] = HOME_PATH
            pe["TERM"] = "xterm-256color"
            pe["LANG"] = "en_US.UTF-8"
            if (withPreload) pe["LD_PRELOAD"] = PREFIX_PATH + "/lib/libtermux-exec-ld-preload.so" else pe.remove("LD_PRELOAD")
            pe["ANDROID_DATA"] = "/data"
            pe["ANDROID_ROOT"] = "/system"
            pe["EXTERNAL_STORAGE"] = "/sdcard"
            pe["MC_SHARED"] = sharedDir(ctx).absolutePath
            pb.redirectErrorStream(true)
            return pb
        }
        // attempt 1: with LD_PRELOAD (Termux standard); attempt 2: without it
        // (a failing preload lib aborts exec on strict linkers)
        var p: Process? = null
        var lastErr: Exception? = null
        for (attempt in 1..2) {
            try {
                p = makePb(attempt == 1).start()
                if (attempt == 2) diag("arrancó sin LD_PRELOAD (el intento 1 falló)")
                break
            } catch (e: Exception) {
                lastErr = e
                diag("intento $attempt: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
        if (p == null) {
            diag("ERROR: no se pudo ejecutar el proceso. Causa más probable: permiso de ejecución denegado por el sistema (reinstala la app y concede todos los permisos) o binario corrupto.")
            return -1
        }
        if (onLine != null) {
            p.inputStream.bufferedReader().forEachLine { onLine(it) }
        }
        return try { p.waitFor() } catch (_: InterruptedException) { -1 }
    }
}
