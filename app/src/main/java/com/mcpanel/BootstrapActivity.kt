package com.mcpanel

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * First-run screen: extracts the bundled Termux bootstrap matching the
 * device ABI (~30-33 MB compressed, ~90 MB on disk, ~3700 files) into the
 * app's private storage. No network. Runs once; later launches skip this.
 *
 * If the extracted environment cannot actually exec (wrong architecture,
 * missing ELF interpreter), the ABI probe detects it and re-extracts from
 * the other bundled bootstrap automatically.
 */
class BootstrapActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val BLACK = Color.rgb(11, 15, 20)
    private val FG = Color.rgb(237, 242, 247)
    private val MUTED = Color.rgb(148, 163, 184)
    private val ACCENT = Color.rgb(46, 229, 157)
    private val ERROR = Color.rgb(255, 107, 94)

    private lateinit var status: TextView
    private lateinit var bar: ProgressBar
    private lateinit var retry: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!Embed.bootstrapSupported()) { showUnsupported(); return }
        if (Embed.isBootstrapped(this)) { goMain(); return }
        buildUi()
        install()
    }

    private fun buildUi() {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(BLACK); setPadding(48, 96, 48, 32) }
        fun tv(t: String, size: Int, c: Int): TextView = TextView(this).apply { text = t; textSize = size.toFloat(); setTextColor(c); setPadding(0, 8, 0, 8) }
        col.addView(tv("PREPARANDO ENTORNO", 11, FG))
        col.addView(tv("El entorno Linux viene incluido en la app. Se extrae una única vez en este dispositivo. No se necesita conexión.", 14, FG))
        col.addView(tv("Arquitectura: " + Embed.deviceAbi(), 12, MUTED))
        status = tv("Extrayendo…", 13, MUTED); col.addView(status)
        bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { isIndeterminate = true }
        col.addView(bar)
        retry = Button(this).apply {
            text = "REINTENTAR"; setTextColor(BLACK); setBackgroundColor(ACCENT); isAllCaps = true
            minHeight = 0
            setPadding(32, 0, 32, 0)
            visibility = View.GONE
            setOnClickListener { install() }
        }
        col.addView(retry)
        setContentView(ScrollView(this).apply { addView(col) })
    }

    private fun showUnsupported() {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(BLACK); setPadding(48, 96, 48, 32) }
        val tv = TextView(this).apply {
            textSize = 14f; setTextColor(ERROR)
            text = "Este dispositivo (" + Embed.deviceAbi() + ") no tiene una arquitectura ARM compatible con el entorno incluido."
        }
        col.addView(tv)
        setContentView(ScrollView(this).apply { addView(col) })
    }

    private fun install() {
        retry.visibility = View.GONE
        bar.isIndeterminate = true
        status.text = "Extrayendo…"
        status.setTextColor(MUTED)
        scope.launch {
            val outcome = withContext(Dispatchers.IO) {
                // Primary attempt: bootstrap matching SUPPORTED_ABIS.
                var asset = Embed.bootstrapAsset(this@BootstrapActivity)
                var ok = try {
                    assets.open(asset).use { input ->
                        Embed.installBootstrap(this@BootstrapActivity, input, { c -> if (c > 0) ui { bar.isIndeterminate = false; status.text = "Extrayendo… $c archivos" } }, marker(asset))
                    }
                } catch (_: Exception) { false }
                // If it extracts but cannot exec (wrong arch / interp), try the
                // other bundled bootstrap before giving up.
                if (!ok && Embed.bootstrapSupported()) {
                    val other = if (asset == Embed.BOOTSTRAP_ARM) Embed.BOOTSTRAP_AARCH64 else Embed.BOOTSTRAP_ARM
                    try {
                        assets.open(other).use { input ->
                            val ok2 = Embed.installBootstrap(this@BootstrapActivity, input, { c -> if (c > 0) ui { bar.isIndeterminate = false; status.text = "Probando otra arquitectura… $c archivos" } }, marker(other))
                            if (ok2) { asset = other; true } else false
                        }
                    } catch (_: Exception) { false }
                }
                Triple(ok, asset, Embed.bashDiag(this@BootstrapActivity))
            }
            if (outcome.first) { toast("Entorno listo."); goMain() }
            else {
                status.text = "Error: " + outcome.third
                status.setTextColor(ERROR)
                retry.visibility = View.VISIBLE
            }
        }
    }

    /** Marker file naming which bootstrap was extracted (stale-marker guard). */
    private fun marker(asset: String): String =
        "tmp/.bootstrap-" + if (asset == Embed.BOOTSTRAP_ARM) "arm" else "aarch64"

    private fun ui(block: () -> Unit) { android.os.Handler(mainLooper).post(block) }

    private fun goMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
