package com.mcpanel

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
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
    private val shared get() = Embed.sharedDir(this)
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
        if (!Embed.isBootstrapped(this)) { startActivity(Intent(this, BootstrapActivity::class.java)); finish(); return }
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
        text = title; textSize = 11f; setTextColor(FG); letterSpacing = 0.08f; setPadding(0, 16, 0, 8)
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

    // ── state / exec ─────────────────────────────────────────────────
    private fun readState(): JSONObject? = try { JSONObject(stateFile.readText()) } catch (_: Exception) { null }

    /** org.json turns JSON null into the string "null"; treat both as empty. */
    private fun sval(st: JSONObject?, key: String): String {
        if (st == null) return ""
        val s = st.optString(key, "")
        return if (s == "null") "" else s
    }

    private fun hasError(st: JSONObject?): Boolean = sval(st, "last_error").isNotEmpty()

    /** Script sets this marker when bootstrap finishes successfully. */
    private fun bootstrapDone(): Boolean = File(Embed.prefix(this), "tmp/bootstrap-done").exists()

    /** Clear last_error before launching a new command (stale errors must not block). */
    private fun clearError() {
        val cur = readState() ?: return
        if (!hasError(cur)) return
        try {
            cur.put("last_error", JSONObject.NULL)
            stateFile.parentFile?.mkdirs()
            val tmp = File(stateFile.parentFile, ".state.app.tmp")
            tmp.writeText(cur.toString())
            tmp.renameTo(stateFile)
        } catch (_: Exception) {}
    }

    /** Re-render config when the user comes back from the permission settings. */
    private var resumer: (() -> Unit)? = null
    override fun onResume() { super.onResume(); resumer?.invoke() }

    /** Run mc_manager.sh subcommand inside the embedded prefix. */
    private fun runTermux(vararg args: String) {
        if (!Embed.isBootstrapped(this)) { toast("Entorno no preparado."); return }
        val i = Intent(this, ServerService::class.java)
            .putExtra(ServerService.EXTRA_CMD, args.first())
            .putExtra(ServerService.EXTRA_ARGS, args.drop(1).toTypedArray())
        ContextCompat.startForegroundService(this, i)
    }

    private fun download(url: String, name: String, afterCmd: String? = null, afterArgs: List<String>? = null) {
        val i = Intent(this, DownloadService::class.java)
            .putExtra(DownloadService.EXTRA_URL, url)
            .putExtra(DownloadService.EXTRA_NAME, name)
        if (afterCmd != null) {
            i.putExtra(DownloadService.EXTRA_AFTER_CMD, afterCmd)
            i.putExtra(DownloadService.EXTRA_AFTER_ARGS, afterArgs?.toTypedArray() ?: emptyArray())
        }
        ContextCompat.startForegroundService(this, i)
        toast("Descargando $name")
    }

    private fun copy(value: String) {
        (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("MCPanel", value))
        toast("Copiado.")
    }

    /** Tail a file into the given TextView at 1s cadence until replaced. */
    private fun watch(target: File, out: TextView) {
        pollJob?.cancel()
        pollFile = target
        pollJob = scope.launch {
            while (isActive && pollFile === target) {
                val s = withContext(Dispatchers.IO) { if (target.exists()) target.readText().takeLast(12000) else "" }
                if (out.text.toString() != s) { out.text = s; }
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
        val envOk = Embed.isBootstrapped(this)
        val storageOk = hasStorage()
        val st = readState()
        val pkgsOk = bootstrapDone()
        val allOk = envOk && storageOk && pkgsOk
        val stInstalled = st?.optBoolean("installed") ?: false
        fun dot(ok: Boolean) = if (ok) "●" else "○"
        fun dotColor(ok: Boolean) = if (ok) ACCENT else WARN

        val v = screen(
            header("CONFIGURACIÓN"),
            row(dot(envOk) + "  Entorno Linux", if (envOk) "LISTO" else "FALTA", dotColor(envOk)),
            mono("Incluido en la app. Se extrajo al primer arranque.", MUTED, 12),
            row(dot(storageOk) + "  Permiso de archivos", if (storageOk) "LISTO" else "PENDIENTE", dotColor(storageOk)),
            if (!storageOk) button("CONCEDER PERMISO") {
                if (android.os.Build.VERSION.SDK_INT >= 30) {
                    try { startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))) }
                    catch (_: Exception) { startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
                } else {
                    requestPermissions(arrayOf(
                        android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
                        android.Manifest.permission.READ_EXTERNAL_STORAGE), 1)
                }
            } else View(this),
            row(dot(pkgsOk) + "  Herramientas (jq, tmux, java…)", if (pkgsOk) "LISTO" else "PENDIENTE", dotColor(pkgsOk)),
            if (!pkgsOk) button("INSTALAR HERRAMIENTAS", primary = storageOk) {
                if (!storageOk) { toast("Primero concede el permiso de archivos (paso 2).") }
                else { clearError(); runTermux("bootstrap"); showBootstrapLog() }
            } else View(this),
            button("VER REGISTRO") { showBootstrapLog() },
            View(this),
            if (stInstalled) button("IR AL SERVIDOR", primary = true) { showServer() }
            else button("CONTINUAR →", primary = allOk) {
                if (allOk) showCreate() else toast("Completa los pasos pendientes.")
            },
        )
        setContentView(v)
        resumer = { showConfig() }
        // auto-refresh while bootstrap runs
        if (!pkgsOk) scope.launch {
            while (isActive) {
                delay(2000)
                if (bootstrapDone()) { showConfig(); break }
            }
        }
    }

    private fun showBootstrapLog() {
        resumer = null
        val out = mono("")
        val v = screen(
            header("REGISTRO DE INSTALACIÓN"),
            mono("Entorno: ${if (Embed.isBootstrapped(this)) "LISTO" else "FALTA"} · Permiso: ${if (hasStorage()) "LISTO" else "FALTA"}", MUTED, 12),
            out,
            button("REINTENTAR BOOTSTRAP") { clearError(); runTermux("bootstrap") },
            button("VOLVER") { showConfig() },
        )
        setContentView(v)
        // combined tail: last_run.log (captura de la app) + install.log (script)
        pollJob?.cancel()
        pollJob = scope.launch {
            while (isActive) {
                val s = withContext(Dispatchers.IO) {
                    val run = Embed.lastRunLog(this@MainActivity)
                    val a = if (run.exists()) run.readText().takeLast(8000) else ""
                    val b = if (installLog.exists()) installLog.readText().takeLast(6000) else ""
                    listOf(a, b).filter { it.isNotBlank() }.joinToString("\n───\n")
                }
                val shown = if (s.isBlank())
                    "(sin salida todavía — espera unos segundos; si persiste, falta el permiso del paso 2)"
                else s
                if (out.text.toString() != shown) out.text = shown
                delay(1000)
            }
        }
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
                versions.forEach { vr ->
                    val t = mono(vr)
                    t.setOnClickListener { selectedVersion = vr; versionInput.setText(vr); highlight(versionList, t) }
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
                for (i in 0 until loaderButtons.childCount) loaderButtons.getChildAt(i).setBackgroundColor(BLACK)
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
            View(this),
            button("INSTALAR", primary = true) {
                val ver = versionInput.text.toString().trim()
                if (!ver.matches(Regex("""1\.\d+(\.\d+)?"""))) { ramInfo.text = "Versión no válida."; ramInfo.setTextColor(ERROR); return@button }
                if (!mcAtLeast(ver, "1.17")) { ramInfo.text = "MC < 1.17 no funciona bien en este entorno."; ramInfo.setTextColor(ERROR); return@button }
                selectedVersion = ver
                showCreateSummary(ver)
            },
            button("VOLVER") { if (readState()?.optBoolean("installed") == true) showServer() else showConfig() },
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
                // app downloads artifacts into inbox/; script consumes inbox first.
                // If the app-side lookup fails, the script downloads by itself.
                scope.launch {
                    when (loader) {
                        "paper" -> {
                            val build = withContext(Dispatchers.IO) { Apis.paperLatestBuild(version) }
                            if (build != null) download(Apis.paperJarUrl(version, build), "paper-$version-$build.jar")
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
        val v = screen(header("INSTALANDO SERVIDOR"), out, button("IR AL SERVIDOR") { showServer() })
        setContentView(v); watch(installLog, out)
        scope.launch {
            while (isActive) {
                delay(2000)
                val s = readState()
                if (s?.optBoolean("installed") == true) { showServer(); break }
                if (hasError(s) && s?.optString("last_action") == "error") break
            }
        }
    }

    // ══════════════ SCREEN 3: SERVIDOR ══════════════
    private fun showServer(tab: String = "status") {
        pollJob?.cancel()
        val st = readState()
        if (st?.optBoolean("installed") != true) { showConfig(); return }
        val running = st.optBoolean("running")

        val statusLine = mono(
            "loader  ${sval(st, "loader")}\n" +
            "vers    ${sval(st, "version")}\n" +
            "estado  ${if (running) "● ACTIVO" else "○ DETENIDO"}\n" +
            "ram     ${sval(st, "ram_min")}/${sval(st, "ram_max")}\n" +
            "puerto  ${if (st.has("port")) st.optInt("port", 25565) else 25565}\n" +
            "playit  ${st.optJSONObject("playit")?.optString("address")?.takeIf { it.isNotEmpty() && it != "null" } ?: "—"}",
            if (running) ACCENT else FG
        )

        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val err = sval(st, "last_error")
        if (err.isNotEmpty()) body.addView(mono("error: $err", ERROR, 12))

        val v = screen(
            header("SERVIDOR"),
            statusLine,
            button(if (running) "DETENER" else "INICIAR", primary = true) {
                runTermux(if (running) "stop" else "start")
                scope.launch { delay(1500); showServer(tab) }
            },
            tabsRow(tab) { showServer(it) },
            body,
        )
        var consoleOut: TextView? = null
        when (tab) {
            "console" -> consoleOut = consoleTab(body)
            "mods" -> modsTab(body, sval(st, "loader"), sval(st, "version"))
            "tunnel" -> tunnelTab(body, st)
            "data" -> dataTab(body)
            else -> { /* status only */ }
        }
        setContentView(v)
        if (tab == "console") consoleOut?.let { watch(consoleLog, it) }
        // status tab: poll state.json so ● and playit stay fresh
        if (tab == "status") scope.launch {
            while (isActive) {
                delay(2000)
                val s2 = readState()
                if (s2 == null) continue
                val r2 = s2.optBoolean("running")
                statusLine.text =
                    "loader  ${sval(s2, "loader")}\n" +
                    "vers    ${sval(s2, "version")}\n" +
                    "estado  ${if (r2) "● ACTIVO" else "○ DETENIDO"}\n" +
                    "ram     ${sval(s2, "ram_min")}/${sval(s2, "ram_max")}\n" +
                    "puerto  ${if (s2.has("port")) s2.optInt("port", 25565) else 25565}\n" +
                    "playit  ${s2.optJSONObject("playit")?.optString("address")?.takeIf { it.isNotEmpty() && it != "null" } ?: "—"}"
                statusLine.setTextColor(if (r2) ACCENT else FG)
            }
        }
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

    private fun consoleTab(body: LinearLayout): TextView {
        val out = mono("")
        val input = EditText(this).apply { hint = "comando (sin /)"; setTextColor(FG); setHintTextColor(MUTED); typeface = Typeface.MONOSPACE; textSize = 13f }
        body.addView(out, LinearLayout.LayoutParams(-1, 600))
        body.addView(input)
        body.addView(button("ENVIAR") {
            val c = input.text.toString().trim()
            if (c.isNotEmpty()) { runTermux("send", c); input.setText("") }
        })
        return out
    }

    private fun modsTab(body: LinearLayout, loader: String, mcVersion: String) {
        if (mcVersion.isEmpty()) { body.addView(mono("Instala un servidor primero.", WARN, 12)); return }
        val query = EditText(this).apply { hint = "buscar en Modrinth"; setTextColor(FG); setHintTextColor(MUTED); textSize = 13f }
        val results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val dest = Embed.serverDir(this).let { if (loader == "paper") File(it, "plugins") else File(it, "mods") }

        body.addView(header("BÚSQUEDA MODRINTH"))
        body.addView(query)
        body.addView(button("BUSCAR") {
            results.removeAllViews(); results.addView(mono("Buscando…", MUTED, 12))
            scope.launch {
                val hits = withContext(Dispatchers.IO) { Apis.modrinthSearch(query.text.toString().trim(), mcVersion, loader) }
                results.removeAllViews()
                if (hits.isEmpty()) { results.addView(mono("Sin resultados.", MUTED, 12)); return@launch }
                hits.forEach { h ->
                    val r = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL; setBackgroundColor(BLACK); setPadding(0, 8, 0, 8) }
                    r.addView(LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        addView(mono(h.title, FG)); addView(mono(h.description.take(60), MUTED, 11))
                    }, LinearLayout.LayoutParams(0, -2, 1f))
                    r.addView(button("INSTALAR") {
                        scope.launch {
                            val url = withContext(Dispatchers.IO) { Apis.modrinthDownloadUrl(h.slug, mcVersion, loader) }
                            if (url == null) toast("Sin versión compatible.")
                            else download(url, url.substringAfterLast('/'), "mod-install", listOf(url.substringAfterLast('/')))
                        }
                    })
                    results.addView(r); results.addView(separator())
                }
            }
        })
        body.addView(header("RESULTADOS")); body.addView(results)

        body.addView(header("INSTALADOS"))
        val listText = StringBuilder()
        val files = if (dest.exists()) dest.listFiles()?.sortedBy { it.name } else null
        if (files.isNullOrEmpty()) listText.append("(vacío)")
        else files.forEach { listText.append(it.name).append('\n') }
        // inbox items pending mod-install
        if (inbox.exists()) {
            val pending = inbox.listFiles()?.filter { it.name.endsWith(".jar") } ?: emptyList()
            if (pending.isNotEmpty()) {
                listText.append("\nen cola (inbox):\n")
                pending.forEach { listText.append(it.name).append('\n') }
                body.addView(mono(listText.toString(), MUTED, 12))
                body.addView(button("INSTALAR COLA") {
                    pending.forEach { runTermux("mod-install", it.name) }
                    scope.launch { delay(2500); showServer("mods") }
                })
                return
            }
        }
        body.addView(mono(listText.toString(), MUTED, 12))
        body.addView(button("ACTUALIZAR") { showServer("mods") })
    }

    private fun tunnelTab(body: LinearLayout, st: JSONObject) {
        val playit = st.optJSONObject("playit")
        val pRunning = playit?.optBoolean("running") == true
        val addr = playit?.optString("address", "")?.takeIf { it.isNotEmpty() && it != "null" }
        body.addView(row("playit", if (pRunning) "● ACTIVO" else "○ DETENIDO", if (pRunning) ACCENT else MUTED))
        body.addView(mono("Dirección: ${addr ?: "—"}", MUTED, 12))
        body.addView(button(if (pRunning) "REINICIAR TÚNEL" else "INICIAR TÚNEL") { runTermux("playit-start") })
        body.addView(button("ACTUALIZAR ESTADO") { runTermux("playit-status"); scope.launch { delay(1500); showServer("tunnel") } })
        if (addr != null && addr.startsWith("http")) body.addView(button("ABRIR CLAIM EN NAVEGADOR") { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(addr))) })
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
    private fun ramMB(): Int = try {
        val mi = java.io.RandomAccessFile("/proc/meminfo", "r").readLine().split(Regex("\\s+"))
        (mi[1].toLong() / 1024).toInt()
    } catch (_: Exception) { 2048 }

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
