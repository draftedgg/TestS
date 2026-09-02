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
 * First-run screen: extracts the bundled Termux bootstrap (~33 MB compressed,
 * ~90 MB on disk, ~3700 files) into the app's private storage. No network.
 * Runs once; later launches skip this screen.
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
    private lateinit var retry: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Embed.isBootstrapped(this)) { goMain(); return }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(BLACK); setPadding(48, 96, 48, 32) }
        fun tv(t: String, size: Int, c: Int): TextView = TextView(this).apply { text = t; textSize = size.toFloat(); setTextColor(c); setPadding(0, 8, 0, 8) }
        col.addView(tv("PREPARANDO ENTORNO", 11, FG))
        col.addView(tv("El entorno Linux viene incluido en la app. Se extrae una única vez en este dispositivo. No se necesita conexión.", 14, FG))
        status = tv("Extrayendo…", 13, MUTED); col.addView(status)
        bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { isIndeterminate = true }
        col.addView(bar)
        retry = Button(this).apply {
            text = "REINTENTAR"; setTextColor(BLACK); setBackgroundColor(ACCENT); isAllCaps = true
            visibility = View.GONE
            setOnClickListener { install() }
        }
        col.addView(retry)
        setContentView(ScrollView(this).apply { addView(col) })
        install()
    }

    private fun install() {
        retry.visibility = View.GONE
        bar.isIndeterminate = true
        status.text = "Extrayendo…"
        status.setTextColor(MUTED)
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    assets.open(Embed.BOOTSTRAP_ASSET).use { input ->
                        Embed.installBootstrap(this@BootstrapActivity, input) { count ->
                            if (count > 0) ui {
                                bar.isIndeterminate = false
                                status.text = "Extrayendo… $count archivos"
                            }
                        }
                    }
                } catch (_: Exception) { false }
            }
            if (ok) { toast("Entorno listo."); goMain() }
            else {
                status.text = "Error extrayendo el entorno. Reintenta."
                status.setTextColor(ERROR)
                retry.visibility = View.VISIBLE
            }
        }
    }

    private fun ui(block: () -> Unit) { android.os.Handler(mainLooper).post(block) }

    private fun goMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
