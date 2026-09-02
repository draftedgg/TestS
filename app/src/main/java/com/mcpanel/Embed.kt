package com.mcpanel

import android.content.Context
import java.io.File
import java.util.zip.ZipInputStream

/**
 * Embedded Linux environment (Termux bootstrap, GPLv3) inside the app's
 * private storage. No separate Termux app, no intents.
 *
 * Layout (same conventions as Termux):
 *   PREFIX = /data/data/com.mcpanel/files/usr
 *   HOME   = /data/data/com.mcpanel/files/home
 *
 * Bootstrap zip ships inside the APK at assets/bootstrap/. Its root IS the
 * prefix (bin/, lib/, etc/ at top level) — extracted into files/usr.
 * SYMLINKS.txt lines look like: <abs target>←<./link-path> and targets
 * referencing com.termux are rewritten to this app's prefix.
 */
object Embed {
    const val BOOTSTRAP_ASSET = "bootstrap/bootstrap-aarch64.zip"

    fun filesDir(ctx: Context): File = ctx.getFilesDir()
    fun prefix(ctx: Context): File = File(filesDir(ctx), "usr")
    fun home(ctx: Context): File = File(filesDir(ctx), "home")

    fun isBootstrapped(ctx: Context): Boolean =
        File(prefix(ctx), "bin/bash").exists() && File(prefix(ctx), "bin/apt").exists()

    /** Extract the bundled bootstrap zip into files/usr with exec bits + symlinks. */
    fun installBootstrap(ctx: Context, zipBytes: java.io.InputStream, onProgress: (Int) -> Unit): Boolean {
        val root = prefix(ctx)
        root.mkdirs()
        try {
            val zin = ZipInputStream(zipBytes.buffered())
            var count = 0
            while (true) {
                val e = zin.nextEntry ?: break
                val out = File(root, e.name)
                if (e.isDirectory) { out.mkdirs(); continue }
                out.parentFile?.mkdirs()
                zin.copyTo(out.outputStream().buffered(), 65536)
                val name = e.name
                val isExec = name.startsWith("bin/") || name.endsWith(".sh") ||
                        name.startsWith("libexec/") || name.startsWith("lib/apt/")
                try {
                    out.setReadable(true); out.setWritable(true)
                    out.setExecutable(isExec, false)
                } catch (_: Exception) {}
                count++
                if (count % 250 == 0) onProgress(-1)
            }
            zin.close()
            createSymlinks(ctx)
            if (!File(root, "bin/bash").exists()) return false
            writeProfile(ctx)
            File(root, "tmp").mkdirs()
            return true
        } catch (_: Exception) { return false }
    }

    /** Recreate symlinks from assets bootstrap SYMLINKS.txt (target←link). */
    private fun createSymlinks(ctx: Context) {
        try {
            val txt = ctx.assets.open("bootstrap/SYMLINKS.txt").bufferedReader().readText()
            txt.lineSequence().filter { it.contains('←') }.forEach { lineRaw ->
                val line = lineRaw.trim()
                val idx = line.indexOf('←')
                if (idx <= 0) return@forEach
                val targetAbs = line.substring(0, idx)
                val linkRel = line.substring(idx + 1).removePrefix("./")
                val target = targetAbs.replace("/data/data/com.termux/files/usr", prefix(ctx).absolutePath)
                val link = File(prefix(ctx), linkRel)
                link.parentFile?.mkdirs()
                try { link.delete() } catch (_: Exception) {}
                try {
                    android.system.Os.symlink(target, link.absolutePath)
                } catch (_: Throwable) {
                    val src = File(target)
                    if (src.exists()) try { src.copyTo(link, overwrite = true) } catch (_: Exception) {}
                }
            }
        } catch (_: Exception) {}
    }

    private fun writeProfile(ctx: Context) {
        val etcProfile = File(prefix(ctx), "etc/profile")
        etcProfile.parentFile?.mkdirs()
        if (!etcProfile.exists()) {
            val P = prefix(ctx).absolutePath
            val H = home(ctx).absolutePath
            etcProfile.writeText(
                "export PREFIX=" + P + "\n" +
                "export HOME=" + H + "\n" +
                "export PATH=" + P + "/bin:\$PATH\n" +
                "export TMPDIR=" + P + "/tmp\n" +
                "export LD_PRELOAD=" + P + "/lib/libtermux-exec.so\n" +
                "export TERM=xterm-256color\n"
            )
        }
    }

    /** Run a command inside the embedded prefix. Blocks; returns exit code. */
    fun exec(ctx: Context, command: String, args: List<String>, onLine: ((String) -> Unit)? = null): Int {
        val bash = File(prefix(ctx), "bin/bash")
        if (!bash.exists()) return -1
        val bootCp = System.getProperty("java.boot.classpath") ?: ""
        val env = listOf(
            "PATH=" + prefix(ctx).absolutePath + "/bin",
            "HOME=" + home(ctx).absolutePath,
            "TMPDIR=" + prefix(ctx).absolutePath + "/tmp",
            "PREFIX=" + prefix(ctx).absolutePath,
            "TERM=xterm-256color",
            "LD_PRELOAD=" + prefix(ctx).absolutePath + "/lib/libtermux-exec.so",
            "ANDROID_DATA=/data",
            "ANDROID_ROOT=/system",
            "BOOTCLASSPATH=" + bootCp,
            "LANG=en_US.UTF-8",
        )
        val pb = ProcessBuilder(listOf(bash.absolutePath, "-c", command) + args)
        pb.directory(home(ctx))
        val pe = pb.environment()
        pe.clear()
        env.forEach { kv -> val i2 = kv.indexOf('='); if (i2 > 0) pe[kv.substring(0, i2)] = kv.substring(i2 + 1) }
        pb.redirectErrorStream(true)
        val p = try { pb.start() } catch (_: Exception) { return -1 }
        if (onLine != null) {
            p.inputStream.bufferedReader().forEachLine { onLine(it) }
        }
        return try { p.waitFor() } catch (_: InterruptedException) { -1 }
    }

    /** Install a bundled raw script to the embedded HOME. */
    fun installScript(ctx: Context, resId: Int, target: File) {
        target.parentFile?.mkdirs()
        ctx.resources.openRawResource(resId).use { input ->
            target.outputStream().use { input.copyTo(it) }
        }
        target.setExecutable(true, false)
    }
}
