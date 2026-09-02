package com.mcpanel

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

class MainActivity : Activity() {
    private val shared get() = File(Environment.getExternalStorageDirectory(), "MCPanel")
    private val stateFile get() = File(shared, "state.json")
    private val consoleLog get() = File(shared, "console.log")
    private val installLog get() = File(shared, "install.log")
    private val inbox get() = File(shared, "inbox")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var pollJob: Job? = null
    private var pollFile: File? = null

    // design system
    private val BLACK = Color.rgb(0, 0, 0)
    private val FG = Color.rgb(232, 232, 232)
    private val MUTED = Color.rgb(119, 119, 119)
    private val SEP = Color.rgb(28, 28, 28)
    private val ACCENT = Color.rgb(0, 224, 127)
    private val ERROR = Color.rgb(255, 69, 58)
    private val WARN = Color.rgb(255, 176, 32)
    private val PRESSED = Color.rgb(17, 17, 17)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val st = readState()
        if (st != null && st.optBoolean("installed")) showServer() else showConfig()
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    // ── UI helpers (design system: 0dp corners, no shadows, mono data) ──
    private fun screen(vararg children: View): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(BLACK); setPadding(24, 32, 24, 16) }
        children.forEach { col.addView(it) }
        return ScrollView(this).apply { setBackgroundColor(BLACK); addView(col, LinearLayout.LayoutParams(-1, -2)) }
    }

    private fun header(title: String): TextView = TextView(this).apply {
        text = title; textSize = 11f; setTextColor(FG); letterSpacing = 0.08f; typeface = Typeface.DEFAULT; setPadding(0, 12, 0, 8)
    }

    private fun mono(text: String, color: Int = FG, size: Int = 13): TextView = TextView(this).apply {
        this.text = text; textSize = size.toFloat(); setTextColor(color); typeface = Typeface.MONOSPACE; setPadding(0, 6, 0, 6)
    }

    private fun row(label: String, value: String, valueColor: Int = FG): View {
        val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setBackgroundColor(BLACK); minimumHeight = 144 }
        r.addView(TextView(this).apply { text = label; textSize = 14f; setTextColor(MUTED); setPadding(0, 12, 16, 12) },
            LinearLayout.LayoutParams(0, -2, 1f))
        r.addView(TextView(this).apply { text = value; textSize = 13f; setTextColor(valueColor); typeface = Typeface.MONOSPACE; setPadding(0, 12, 0, 12) },
            LinearLayout.LayoutParams(-2, -2))
        val sep = View(this).apply { setBackgroundColor(SEP) }
        return LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; addView(r); addView(sep, LinearLayout.LayoutParams(-1, 1)) }
    }

    private fun button(label: String, primary: Boolean = false, action: () -> Unit): Button = Button(this).apply {
        text = label; isAllCaps = true; textSize = 14f
        background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = 0f
            setColor(if (primary) ACCENT else BLACK)
            setStroke(1, Color.rgb(68, 68, 68))
        }
        setTextColor(if (primary) BLACK else FG)
        setOnClickListener { action() }
        setOnTouchListener { v, ev ->
            when (ev.action) {
                android.view.MotionEvent.ACTION_DOWN -> v.setBackgroundColor(PRESSED)
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> v.setBackgroundColor(if (primary) ACCENT else BLACK)
            }
            false
        }
    }

    private fun separator(): View = View(this).apply { setBackgroundColor(SEP) }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    // ── state / intents ──────────────────────────────────────────────
    private fun readState(): JSONObject? = try { JSONObject(stateFile.readText()) } catch (_: Exception) { null }

    private fun runTermux(vararg args: String) {
        val i = Intent("com.termux.RUN_COMMAND").apply {
            setClassName("com.termux", "com.termux.app.RunCommandService")
            putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/home/mcpanel/mc_manager.sh")
            putExtra("com.termux.RUN_COMMAND_ARGUMENTS", args)
            putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
            putExtra("com.termux.RUN_COMMAND_WORKDIR", "/data/data/com.termux/files/home")
        }
        try { startService(i) } catch (_: Exception) { toast("Termux no está preparado.") }
    }

    private fun download(url: String, name: String) {
        val i = Intent(this, DownloadService::class.java).putExtra(DownloadService.EXTRA_URL, url).putExtra(DownloadService.EXTRA_NAME, name)
        ContextCompat.startForegroundService(this, i)
        toast("Descargando $name")
    }

    private fun copy(value: String) {
        (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("MCPanel", value))
        toast("Copiado.")
    }

    /** Tail a file into the current output TextView until replaced or screen exits. */
    private fun watch(target: File, out: TextView) {
        pollJob?.cancel()
        pollFile = target
        pollJob = scope.launch {
            while (isActive && pollFile === target) {
                val s = withContext(Dispatchers.IO) { if (target.exists()) target.readText().takeLast(12000) else "" }
                if (out.text.toString() != s) { out.text = s }
                delay(1000)
            }
        }
    }

    /** All-files access exists on API 30+; older devices use legacy WRITE/READ. */
    private fun hasStorage(): Boolean = if (android.os.Build.VERSION.SDK_INT >= 30) {
        Environment.isExternalStorageManager()
    } else {
        checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    // ══════════════ SCREEN 1: CONFIGURACIÓN ══════════════
    private fun showConfig() {
        pollJob?.cancel()
        val termuxInstalled = try { packageManager.getPackageInfo("com.termux", 0); true } catch (_: Exception) { false }
        val hasStorage = hasStorage()
        val scriptInstalled = File(Environment.getExternalStorageDirectory(), "Android/data/com.termux/files/home/mcpanel/mc_manager.sh").exists() ||
                (readState()?.optString("last_action", "").let { it == "bootstrap" || it == "install" })

        val allowCmd = "echo allow-external-apps=true >> ~/.termux/termux.properties"

        fun dot(ok: Boolean) = if (ok) "●" else "○"
        fun dotColor(ok: Boolean) = if (ok) ACCENT else WARN

        val v = screen(
            header("CONFIGURACIÓN"),
            row(dot(termuxInstalled) + " Termux instalado", if (termuxInstalled) "OK" else "FALTA", dotColor(termuxInstalled)),
            mono("Instálalo desde F-Droid o GitHub. Nunca Play Store.", MUTED, 12),
            separator(),
            row(dot(hasStorage) + " Permiso de todos los archivos", if (hasStorage) "OK" else "PENDIENTE", dotColor(hasStorage)),
            if (!hasStorage) button("CONCEDER PERMISO") {
                if (android.os.Build.VERSION.SDK_INT >= 30) {
                    try { startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))) }
                    catch (_: Exception) { startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
                } else {
                    requestPermissions(arrayOf(
                        android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
                        android.Manifest.permission.READ_EXTERNAL_STORAGE), 1)
                }
            } else View(this),
            separator(),
            header("ALLOW-EXTERNAL-APPS"),
            mono("Ejecuta en Termux:", MUTED, 12),
            mono(allowCmd),
            button("COPIAR COMANDO") { copy(allowCmd) },
            row(dot(scriptInstalled) + " Bootstrap ejecutado", if (scriptInstalled) "OK" else "PENDIENTE", dotColor(scriptInstalled)),
            button("EJECUTAR BOOTSTRAP", primary = true) {
                runTermux("bootstrap")
                scope.launch { delay(2500); showBootstrapLog() }
            },
            button("VER REGISTRO") { showBootstrapLog() },
            View(this),
            button("CONTINUAR") { showCreate() },
        )
        setContentView(v)
    }

    private fun showBootstrapLog() {
        val out = mono("")
        val v = screen(header("BOOTSTRAP — INSTALL.LOG"), out, button("ATRÁS") { showConfig() })
        setContentView(v); watch(installLog, out)
    }

    // ══════════════ SCREEN 2: CREAR SERVIDOR ══════════════
    private var selectedLoader = "paper"
    private var selectedVersion: String? = null

    private fun showCreate() {
        pollJob?.cancel()
        val loaders = listOf(
            Triple("paper", "Paper", "Vanilla optimizado + plugins"),
            Triple("fabric", "Fabric", "Mods ligeros + rendimiento"),
            Triple("forge", "Forge", "Mods clásicos"),
            Triple("neoforge", "NeoForge", "Fork moderno de Forge"),
        )
        val versionInput = EditText(this).apply {
            hint = "o escribe la versión exacta (mín 1.17)"; setTextColor(FG); setHintTextColor(MUTED); typeface = Typeface.MONOSPACE; textSize = 13f
        }
        val versionList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val ramInfo = mono("", MUTED)
        var fetched: List<String> = emptyList()

        fun refreshRam() {
            val total = ramMB()
            val preset = ramPreset(total)
            ramInfo.text = "RAM dispositivo: ${total} MB — preset ${preset.first}/${preset.second}"
        }

        fun loadVersions(loader: String) {
            versionList.removeAllViews()
            versionList.addView(mono("Cargando versiones…", MUTED, 12))
            scope.launch {
                val versions = withContext(Dispatchers.IO) {
                    when (loader) {
                        "paper" -> Apis.paperVersions()
                        "fabric" -> Apis.fabricVersions()
                        "forge" -> Apis.forgeVersions()
                        else -> Apis.neoforgeVersions()
                    }
                }.filter { mcAtLeast(it, "1.17") }.take(40)
                fetched = versions
                versionList.removeAllViews()
                if (versions.isEmpty()) {
                    versionList.addView(mono("Sin conexión o sin versiones. Escribe la versión manual.", ERROR, 12))
                    return@launch
                }
                versions.forEach { v ->
                    val t = mono(v)
                    t.setOnClickListener { selectedVersion = v; versionInput.setText(v); highlight(versionList, t) }
                    versionList.addView(t)
                }
            }
        }

        val loaderButtons = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        loaders.forEach { (id, name, desc) ->
            val b = Button(this).apply {
                text = "$name  ·  $desc"; isAllCaps = false; textSize = 14f
                background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = 0f; setColor(BLACK); setStroke(1, SEP) }
                setTextColor(if (id == selectedLoader) ACCENT else FG)
            }
            b.setOnClickListener {
                selectedLoader = id; selectedVersion = null
                loaders.forEach { (lid, _, _) ->
                    loaderButtons.getChildAt(loaders.indexOfFirst { it.first == lid }).setBackgroundColor(BLACK)
                }
                b.setBackgroundColor(PRESSED)
                loadVersions(id); refreshRam()
            }
            loaderButtons.addView(b, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 2, 0, 2) })
        }

        refreshRam(); loadVersions(selectedLoader)

        val v = screen(
            header("CREAR SERVIDOR — 1. LOADER"),
            loaderButtons,
            header("2. VERSIÓN"),
            versionList,
            versionInput,
            header("3. RAM"),
            ramInfo,
            button("INSTALAR", primary = true) {
                val ver = versionInput.text.toString().trim()
                if (!ver.matches(Regex("""1\.\d+(\.\d+)?"""))) { ramInfo.text = "Versión no válida."; ramInfo.setTextColor(ERROR); return@button }
                if (!mcAtLeast(ver, "1.17")) { ramInfo.text = "MC < 1.17 no funciona bien en Termux."; ramInfo.setTextColor(ERROR); return@button }
                selectedVersion = ver
                showCreateSummary(ver)
            },
            button("VOLVER") { showServer(); },
        )
        setContentView(v)
    }

    private fun highlight(list: LinearLayout, selected: View) {
        for (i in 0 until list.childCount) list.getChildAt(i).setBackgroundColor(BLACK)
        selected.setBackgroundColor(PRESSED)
    }

    private fun showCreateSummary(version: String) {
        val preset = ramPreset(ramMB())
        val v = screen(
            header("RESUMEN"),
            row("Loader", selectedLoader, ACCENT),
            row("Versión", version),
            row("RAM", "${preset.first} / ${preset.second}"),
            row("Destino", "~/mcserver"),
            View(this),
            button("CONFIRMAR E INSTALAR", primary = true) {
                val loader = selectedLoader
                // descargas en la app a inbox; el script consume inbox primero
                scope.launch {
                    when (loader) {
                        "paper" -> {
                            val build = withContext(Dispatchers.IO) { Apis.paperLatestBuild(version) }
                            if (build == null) { runTermux("install", "--loader", loader, "--version", version); return@launch }
                            download(Apis.paperJarUrl(version, build), "paper-$version-$build.jar")
                            runTermux("install", "--loader", loader, "--version", version)
                        }
                        "fabric" -> {
                            val url = withContext(Dispatchers.IO) { Apis.fabricInstallerUrl() }
                            if (url != null) download(url, "fabric-installer.jar")
                            runTermux("install", "--loader", loader, "--version", version)
                        }
                        "forge" -> {
                            val b = withContext(Dispatchers.IO) { Apis.forgeBuild(version) }
                            if (b != null) download(Apis.forgeInstallerUrl(version, b), "forge-$version-$b-installer.jar")
                            runTermux("install", "--loader", loader, "--version", version)
                        }
                        else -> {
                            val vs = withContext(Dispatchers.IO) { Apis.neoforgeVersions() }
                            val neo = vs.firstOrNull { it.startsWith(version.substringBeforeLast('.') + ".") } ?: vs.firstOrNull()
                            if (neo != null) download(Apis.neoforgeInstallerUrl(neo), "neoforge-$neo-installer.jar")
                            runTermux("install", "--loader", loader, "--version", version)
                        }
                    }
                    showInstallProgress()
                }
            },
            button("ATRÁS") { showCreate() },
        )
        setContentView(v)
    }

    private fun showInstallProgress() {
        val out = mono("")
        val v = screen(header("INSTALANDO — INSTALL.LOG"), out, button("IR AL SERVIDOR") { showServer() })
        setContentView(v); watch(installLog, out)
    }

    // ══════════════ SCREEN 3: SERVIDOR ══════════════
    private fun showServer(tab: String = "status") {
        pollJob?.cancel()
        val st = readState()
        val running = st?.optBoolean("running") ?: false
        val installed = st?.optBoolean("installed") ?: false

        val statusLine = mono(
            "loader  ${st?.optString("loader") ?: "—"}\n" +
            "vers    ${st?.optString("version") ?: "—"}\n" +
            "estado  ${if (running) "● ACTIVO" else "○ DETENIDO"}\n" +
            "ram     ${st?.optString("ram_min") ?: "—"}/${st?.optString("ram_max") ?: "—"}\n" +
            "puerto  ${st?.optInt("port", 25565) ?: 25565}\n" +
            "playit  ${st?.optString("playit", null)?.let { JSONObject(it).optString("address", "—") } ?: "—"}",
            if (running) ACCENT else FG
        )

        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val v = screen(
            header("SERVIDOR"),
            statusLine,
            button(if (running) "DETENER" else "INICIAR", primary = true) { runTermux(if (running) "stop" else "start") },
            tabsRow(tab) { showServer(it) },
            body,
        )
        when (tab) {
            "console" -> consoleTab(body)
            "mods" -> modsTab(body, st?.optString("loader") ?: "paper", st?.optString("version") ?: "")
            "tunnel" -> tunnelTab(body, st)
            "data" -> dataTab(body)
            else -> body.addView(mono("Servidor detenido.", MUTED).takeIf { !running } ?: mono(""))
        }
        setContentView(v)
        if (tab == "console") watch(consoleLog, findOutput(body))
    }

    private fun tabsRow(current: String, onClick: (String) -> Unit): View {
        val tabs = listOf("status" to "ESTADO", "console" to "CONSOLA", "mods" to "MODS", "tunnel" to "TÚNEL", "data" to "DATOS")
        val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        tabs.forEach { (id, label) ->
            val b = Button(this).apply {
                text = label; textSize = 11f; isAllCaps = true
                background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = 0f; setColor(BLACK); setStroke(1, SEP) }
                setTextColor(if (id == current) ACCENT else MUTED)
            }
            b.setOnClickListener { onClick(id) }
            r.addView(b, LinearLayout.LayoutParams(0, -2, 1f))
        }
        return r
    }

    private fun findOutput(root: LinearLayout): TextView {
        for (i in 0 until root.childCount) {
            val c = root.getChildAt(i)
            if (c is TextView && c.typeface == Typeface.MONOSPACE) return c
        }
        return mono("")
    }

    private fun consoleTab(body: LinearLayout) {
        val out = mono("")
        val input = EditText(this).apply { hint = "comando (sin /)"; setTextColor(FG); setHintTextColor(MUTED); typeface = Typeface.MONOSPACE; textSize = 13f }
        body.addView(out, LinearLayout.LayoutParams(-1, 0, 1f))
        body.addView(input)
        body.addView(button("ENVIAR") { val c = input.text.toString().trim(); if (c.isNotEmpty()) runTermux("send", c); input.setText("") })
    }

    private fun modsTab(body: LinearLayout, loader: String, mcVersion: String) {
        if (mcVersion.isEmpty()) { body.addView(mono("Instala un servidor primero.", WARN, 12)); return }
        val query = EditText(this).apply { hint = "buscar en Modrinth"; setTextColor(FG); setHintTextColor(MUTED); textSize = 13f }
        val results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val installedDir = if (loader == "paper") File(Environment.getExternalStorageDirectory(), "Android/data/com.termux/files/home/mcserver/plugins")
                           else File(Environment.getExternalStorageDirectory(), "Android/data/com.termux/files/home/mcserver/mods")

        body.addView(header("BÚSQUEDA MODRINTH"))
        body.addView(query)
        body.addView(button("BUSCAR") {
            results.removeAllViews(); results.addView(mono("Buscando…", MUTED, 12))
            scope.launch {
                val hits = withContext(Dispatchers.IO) { Apis.modrinthSearch(query.text.toString().trim(), mcVersion, loader) }
                results.removeAllViews()
                if (hits.isEmpty()) results.addView(mono("Sin resultados.", MUTED, 12))
                hits.forEach { h ->
                    val r = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL; setBackgroundColor(BLACK); setPadding(0, 8, 0, 8) }
                    r.addView(LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        addView(mono(h.title, FG)); addView(mono(h.description.take(60), MUTED, 11))
                    }, LinearLayout.LayoutParams(0, -2, 1f))
                    r.addView(button("INSTALAR") {
                        toast("Buscando archivo…")
                        scope.launch {
                            val url = withContext(Dispatchers.IO) { Apis.modrinthDownloadUrl(h.slug, mcVersion, loader) }
                            if (url == null) toast("Sin versión compatible.")
                            else download(url, url.substringAfterLast('/'))
                        }
                    })
                    results.addView(r); results.addView(separator())
                }
            }
        })
        body.addView(header("RESULTADOS")); body.addView(results)

        body.addView(header("INSTALADOS"))
        val installedList = mono(
            if (installedDir.exists()) installedDir.listFiles()?.joinToString("\n") { it.name } ?: "(vacío)" else "(sin datos — se instala al mover desde inbox)",
            MUTED, 12)
        body.addView(installedList)
    }

    private fun tunnelTab(body: LinearLayout, st: JSONObject?) {
        val playit = st?.optJSONObject("playit")
        body.addView(row("playit", if (playit?.optBoolean("running") == true) "● ACTIVO" else "○ DETENIDO",
            if (playit?.optBoolean("running") == true) ACCENT else MUTED))
        body.addView(mono("Dirección: ${playit?.optString("address") ?: "—"}", MUTED, 12))
        body.addView(button("INICIAR TÚNEL") { runTermux("playit-start") })
        body.addView(button("ACTUALIZAR ESTADO") { runTermux("playit-status"); scope.launch { delay(1500); showServer("tunnel") } })
        val claim = playit?.optString("address", "") ?: ""
        if (claim.startsWith("http")) body.addView(button("ABRIR CLAIM EN NAVEGADOR") { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(claim))) })
    }

    private fun dataTab(body: LinearLayout) {
        body.addView(header("BACKUPS"))
        body.addView(button("CREAR BACKUP") { runTermux("backup"); toast("Backup en proceso.") })
        body.addView(mono("Se guardan en ~/mc_backups (máx 5).", MUTED, 12))
        body.addView(separator())
        body.addView(header("PELIGRO"))
        body.addView(button("BORRAR SERVIDOR") { confirmDelete() })
    }

    private fun confirmDelete() {
        val step1 = AlertDialog.Builder(this)
            .setTitle("Borrar servidor").setMessage("Se eliminará ~/mcserver completo. Esta acción no se puede deshacer.")
            .setNegativeButton("CANCELAR", null).setPositiveButton("CONTINUAR", null).create()
        step1.setOnShowListener {
            step1.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val input = EditText(this)
                val step2 = AlertDialog.Builder(this)
                    .setTitle("Confirmación final").setMessage("Escribe BORRAR para confirmar.")
                    .setView(input).setNegativeButton("CANCELAR", null)
                    .setPositiveButton("BORRAR") { _, _ ->
                        if (input.text.toString() == "BORRAR") runTermux("server-delete") else toast("Texto incorrecto.")
                    }.create()
                step2.show(); step1.dismiss()
            }
        }
        step1.show()
    }

    // ── helpers ──────────────────────────────────────────────────────
    private fun ramMB(): Int {
        val mi = java.io.RandomAccessFile("/proc/meminfo", "r").readLine().split(Regex("\\s+"))
        return (mi[1].toLong() / 1024).toInt()
    }

    private fun ramPreset(totalMB: Int): Pair<String, String> = when {
        totalMB >= 8192 -> "1G" to "4G"
        totalMB >= 6144 -> "1G" to "3G"
        totalMB >= 4096 -> "512M" to "2G"
        totalMB >= 3072 -> "512M" to "1500M"
        totalMB >= 2048 -> "256M" to "1G"
        else -> "256M" to "512M"
    }

    private fun mcAtLeast(version: String, min: String): Boolean {
        val a = version.split('.').map { it.toIntOrNull() ?: 0 }
        val b = min.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }; val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return true
    }
}
