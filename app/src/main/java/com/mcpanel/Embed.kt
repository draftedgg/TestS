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
 * Bootstrap zip: termux/termux-packages releases (apt.android-7 variant).
 * It contains ./usr/* paths; we extract into files/ so usr lands correctly.
 */
object Embed {
    const val BOOTSTRAP_TAG = "bootstrap-2026.08.30-r1+apt.android-7"
    const val BOOTSTRAP_ASSET = "bootstrap-aarch64.zip"
    const val BOOTSTRAP_URL =
        "https://github.com/termux/termux-packages/releases/download/$BOOTSTRAP_TAG/$BOOTSTRAP_ASSET"

    fun filesDir(ctx: Context): File = ctx.getFilesDir()
    fun prefix(ctx: Context): File = File(filesDir(ctx), "usr")
    fun home(ctx: Context): File = File(filesDir(ctx), "home")
    fun shared(ctx: Context): File = File(Environment_getExternalStorage(), "MCPanel")
    private fun Environment_getExternalStorage(): File =
        android.os.Environment.getExternalStorageDirectory()

    fun isBootstrapped(ctx: Context): Boolean =
        File(prefix(ctx), "bin/bash").exists() && File(prefix(ctx), "bin/apt").exists()

    /** Extract the bootstrap zip into files/ with exec permissions restored. */
    fun installBootstrap(ctx: Context, zipBytes: java.io.InputStream, onProgress: (Int) -> Unit): Boolean {
        val root = filesDir(ctx)
        root.mkdirs()
        var total: Int = -1
        try {
            val zin = ZipInputStream(zipBytes.buffered())
            var count = 0
            while (true) {
                val e = zin.nextEntry ?: break
                val out = File(root, e.name)
                if (e.isDirectory) { out.mkdirs(); continue }
                out.parentFile?.mkdirs()
                zin.copyTo(out.outputStream().buffered(), 65536)
                // Termux bootstrap stores mode in name suffix "@mode"; default sane:
                // bin/* and *.sh executable, rest 0600/0644 by path.
                val name = e.name
                val isExec = name.startsWith("usr/bin/") || name.endsWith(".sh") ||
                        name.startsWith("usr/lib/apt/") && name.contains("methods")
                try {
                    out.setReadable(true); out.setWritable(true)
                    out.setExecutable(isExec, false)
                } catch (_: Exception) {}
                count++
                if (count % 250 == 0) onProgress(-1) // indeterminate tick
            }
            zin.close()
            // sanity: bash exists
            if (!File(prefix(ctx), "bin/bash").exists()) return false
            // write termux-like env profile
            val etcProfile = File(prefix(ctx), "etc/profile")
            etcProfile.parentFile?.mkdirs()
            if (!etcProfile.exists()) {
                etcProfile.writeText(
                    """
                    export PREFIX=${prefix(ctx).absolutePath}
                    export HOME=${home(ctx).absolutePath}
                    export PATH=${prefix(ctx).absolutePath}/bin:\$PATH
                    export TMPDIR=${prefix(ctx).absolutePath}/tmp
                    export LD_PRELOAD=${prefix(ctx).absolutePath}/lib/libtermux-exec.so
                    export TERM=xterm-256color
                    """.trimIndent() + "\n"
                )
            }
            File(prefix(ctx), "tmp").mkdirs()
            return true
        } catch (_: Exception) { return false }
    }

    /** Run a command inside the embedded prefix. Blocks; returns exit code. */
    fun exec(ctx: Context, command: String, args: List<String>, onLine: ((String) -> Unit)? = null): Int {
        val bash = File(prefix(ctx), "bin/bash")
        if (!bash.exists()) return -1
        val env = mutableListOf(
            "PATH=${prefix(ctx).absolutePath}/bin",
            "HOME=${home(ctx).absolutePath}",
            "TMPDIR=${prefix(ctx).absolutePath}/tmp",
            "PREFIX=${prefix(ctx).absolutePath}",
            "TERM=xterm-256color",
            "LD_PRELOAD=${prefix(ctx).absolutePath}/lib/libtermux-exec.so",
            "ANDROID_DATA=/data",
            "ANDROID_ROOT=/system",
            "BOOTCLASSPATH=" + System.getProperty("java.boot.classpath") ?: "",
            "LANG=en_US.UTF-8",
        )
        val pb = ProcessBuilder(listOf(bash.absolutePath, "-c", command) + args)
        pb.directory(home(ctx))
        pb.environment().clear()
        pb.environment().putAll(env)
        pb.redirectErrorStream(true)
        val p = try { pb.start() } catch (_: Exception) { return -1 }
        if (onLine != null) {
            p.inputStream.bufferedReader().forEachLine { onLine(it) }
        }
        return try { p.waitFor() } catch (_: InterruptedException) { -1 }
    }

    /** Absolute path of a script bundled in res/raw or assets. */
    fun installScript(ctx: Context, resId: Int, target: File) {
        target.parentFile?.mkdirs()
        ctx.resources.openRawResource(resId).use { input ->
            target.outputStream().use { input.copyTo(it) }
        }
        target.setExecutable(true, false)
    }
}
