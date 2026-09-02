package com.mcpanel

import android.app.Activity
import android.os.Bundle
import android.graphics.Color
import android.graphics.Typeface
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
import java.io.File

/**
 * First-run screen: downloads the Termux bootstrap (~33 MB) into the app's
 * private storage and extracts it. Runs once; later launches skip this.
 */
class BootstrapActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val BLACK = Color.rgb(0, 0, 0)
    private val FG = Color.rgb(232, 232, 232)
    private val MUTED = Color.rgb(119, 119, 119)
    private val ACCENT = Color.rgb(0, 224, 127)
    private val ERROR = Color.rgb(255, 69, 58)

    private lateinit var status: TextView
    private lateinit var bar: ProgressBar
    private lateinit var retry: android.widget.Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Embed.isBootstrapped(this)) { goMain(); return }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(BLACK); setPadding(48, 96, 48, 32) }
        fun tv(t: String, size: Int, c: Int): TextView = TextView(this).apply { text = t; textSize = size.toFloat(); setTextColor(c); setPadding(0, 8, 0, 8) }
        col.addView(tv("PREPARANDO ENTORNO", 11, FG))
        col.addView(tv("Se descargará el entorno base de Termux (~33 MB) una única vez. Queda dentro de MCPanel; no se necesita la app de Termux.", 14, FG))
        status = tv("", 13, MUTED); col.addView(status)
        bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { isIndeterminate = true }
        col.addView(bar)
        retry = android.widget.Button(this).apply {
            text = "REINTENTAR"; setTextColor(BLACK); setBackgroundColor(ACCENT); isAllCaps = true
            visibility = android.view.View.GONE
            setOnClickListener { install() }
        }
        col.addView(retry)
        setContentView(ScrollView(this).apply { addView(col) })
        install()
    }

    private fun install() {
        retry.visibility = android.view.View.GONE
        bar.isIndeterminate = true
        status.text = "Descargando bootstrap…"
        status.setTextColor(MUTED)
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    val cache = File(cacheDir, "bootstrap.zip")
                    val dl = Apis.downloadToFile(Embed.BOOTSTRAP_URL, cache)
                    if (!dl) return@withContext false
                    status.post { status.text = "Extrayendo…" }
                    val r = Embed.installBootstrap(this@BootstrapActivity, cache.inputStream()) { }
                    cache.delete()
                    r
                } catch (_: Exception) { false }
            }
            if (ok) { toast("Entorno listo."); goMain() }
            else {
                status.text = "Error descargando el entorno. Verifica tu conexión."
                status.setTextColor(ERROR)
                retry.visibility = android.view.View.VISIBLE
            }
        }
    }

    private fun goMain() {
        startActivity(android.content.Intent(this, MainActivity::class.java))
        finish()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
