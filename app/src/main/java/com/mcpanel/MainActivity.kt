package com.mcpanel

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.app.AlertDialog
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File

class MainActivity : Activity() {
    private val shared = File(Environment.getExternalStorageDirectory(), "MCPanel")
    private val state = File(shared, "state.json")
    private val console = File(shared, "console.log")
    private val installLog = File(shared, "install.log")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var pollJob: Job? = null
    private lateinit var root: LinearLayout
    private var output: TextView? = null
    private var current = "config"
    private val green = Color.rgb(0, 224, 127)
    private val fg = Color.rgb(232, 232, 232)
    private val muted = Color.rgb(119, 119, 119)

    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); showConfig() }
    override fun onDestroy() { pollJob?.cancel(); scope.cancel(); super.onDestroy() }

    private fun base(title: String): LinearLayout {
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.BLACK); setPadding(20, 20, 20, 12) }
        text(title, 11, fg).also { root.addView(it) }
        return root
    }
    private fun text(value: String, size: Int, color: Int = fg): TextView = TextView(this).apply { text = value; textSize = size.toFloat(); setTextColor(color); typeface = if (size <= 13) android.graphics.Typeface.MONOSPACE else android.graphics.Typeface.DEFAULT; setPadding(0, 8, 0, 8) }
    private fun button(label: String, action: () -> Unit): Button = Button(this).apply { text = label; setTextColor(if (label == getString(R.string.start) || label == getString(R.string.bootstrap)) Color.BLACK else fg); setBackgroundColor(if (label == getString(R.string.start) || label == getString(R.string.bootstrap)) green else Color.BLACK); setOnClickListener { action() } }
    private fun install(view: View) { setContentView(view) }

    private fun showConfig() {
        current = "config"; val v = base(getString(R.string.configuration))
        val termux = try { packageManager.getApplicationInfo("com.termux", PackageManager.GET_META_DATA) } catch (_: PackageManager.NameNotFoundException) { null }
        v.addView(text(if (termux != null) "● ${getString(R.string.termux_ok)}" else "○ ${getString(R.string.termux_missing)}", 14, if (termux != null) green else Color.RED))
        val command = "echo allow-external-apps=true >> ~/.termux/termux.properties"
        v.addView(text(getString(R.string.external_apps), 13, muted)); v.addView(text(command, 13))
        v.addView(button(getString(R.string.copy)) { copy(command) })
        v.addView(text(if (Environment.isExternalStorageManager()) "● ${getString(R.string.storage_ok)}" else "○ ${getString(R.string.storage_ok)} — permiso pendiente", 14, if (Environment.isExternalStorageManager()) green else Color.RED))
        if (!Environment.isExternalStorageManager()) v.addView(button("ABRIR PERMISOS") { startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))) })
        v.addView(button(getString(R.string.bootstrap)) { runTermux("bootstrap"); watch(installLog) })
        v.addView(button(getString(R.string.continue_text)) { showCreate() })
        install(v)
    }

    private fun showCreate() {
        current = "create"; val v = base(getString(R.string.create_server))
        val loader = Spinner(this).apply { adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, arrayOf("paper", "fabric", "forge", "neoforge")) }
        val version = EditText(this).apply { hint = "Versión, mínimo 1.17"; setTextColor(fg); setHintTextColor(muted); inputType = 1 }
        v.addView(text("LOADER", 11, muted)); v.addView(loader); v.addView(text("VERSIÓN", 11, muted)); v.addView(version)
        val ram = text("RAM automática: revisada por Termux", 13, muted); v.addView(ram)
        v.addView(button("INSTALAR") { val ver = version.text.toString().trim(); if (ver.matches(Regex("1\\.(1[7-9]|20|21)(\\.[0-9]+)?"))) { runTermux("install", "--loader", loader.selectedItem.toString(), "--version", ver); showInstallLog() } else { ram.text = "Versión no válida. Se requiere Minecraft 1.17 o posterior."; ram.setTextColor(Color.RED) } })
        install(v)
    }
    private fun showInstallLog() { val v = base("INSTALACIÓN"); output = text("", 13); v.addView(output); v.addView(button("ATRÁS") { showCreate() }); install(v); watch(installLog) }

    private fun showServer() {
        current = "server"; val v = base(getString(R.string.server)); output = text("", 13); v.addView(output)
        v.addView(button("INICIAR / DETENER") { val running = readState()?.optBoolean("running", false) ?: false; runTermux(if (running) "stop" else "start") })
        v.addView(button("CONSOLA") { watch(console) })
        v.addView(button("MODS") { showMods(v) })
        v.addView(button("TÚNEL") { runTermux("playit-status"); watch(installLog) })
        v.addView(button("BACKUP") { runTermux("backup"); watch(installLog) })
        v.addView(button("BORRAR SERVIDOR") { confirmDelete() })
        v.addView(button("CONFIGURACIÓN") { showConfig() }); install(v); watch(console)
    }
    private fun showMods(parent: LinearLayout) {
        val input = EditText(this).apply { hint = "Nombre del archivo .jar"; setTextColor(fg); setHintTextColor(muted) }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(input, LinearLayout.LayoutParams(0, -2, 1f)); row.addView(button("INSTALAR") { runTermux("mod-install", input.text.toString().trim()) })
        parent.addView(row, parent.indexOfChild(output) + 1)
    }
    private fun confirmDelete() {
        val first = AlertDialog.Builder(this).setTitle("Borrar servidor") .setMessage("Esta acción elimina ~/mcserver.").setNegativeButton("CANCELAR", null).setPositiveButton("CONTINUAR", null).create()
        first.setOnShowListener { first.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            AlertDialog.Builder(this).setTitle("Confirmar borrado").setMessage("Escribe BORRAR para continuar.").setView(EditText(this)).setNegativeButton("CANCELAR", null).setPositiveButton("BORRAR") { _, _ -> runTermux("server-delete") }.show(); first.dismiss()
        } }; first.show()
    }
    private fun readState(): JSONObject? = try { JSONObject(state.readText()) } catch (_: Exception) { null }
    private fun watch(file: File) { pollJob?.cancel(); pollJob = scope.launch { while (isActive) { val s = withContext(Dispatchers.IO) { if (file.exists()) file.readText().takeLast(12000) else "" }; output?.text = s; delay(1000) } } }
    private fun runTermux(vararg args: String) { val i = Intent("com.termux.RUN_COMMAND").apply { setClassName("com.termux", "com.termux.app.RunCommandService"); putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/home/mcpanel/mc_manager.sh"); putExtra("com.termux.RUN_COMMAND_ARGUMENTS", args); putExtra("com.termux.RUN_COMMAND_BACKGROUND", true); putExtra("com.termux.RUN_COMMAND_WORKDIR", "/data/data/com.termux/files/home") }; try { startService(i) } catch (_: Exception) { Toast.makeText(this, "Termux no está preparado.", Toast.LENGTH_SHORT).show() } }
    private fun copy(value: String) { (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("MCPanel", value)); Toast.makeText(this, "Copiado.", Toast.LENGTH_SHORT).show() }
}
