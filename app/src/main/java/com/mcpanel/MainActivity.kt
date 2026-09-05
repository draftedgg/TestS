package com.mcpanel

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
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
import java.io.RandomAccessFile

/**
 * MCPanel: controla tu servidor de Minecraft desde el móvil.
 *
 * Diseño propio (sin Material You): tema oscuro, navegación inferior con
 * 5 secciones, tarjetas con esquinas suaves y estados siempre visibles.
 * El usuario nunca necesita ver la terminal: los detalles técnicos quedan
 * ocultos detrás de un acordeón "Ver detalles".
 */
class MainActivity : Activity() {

    // ── rutas compartidas ────────────────────────────────────────────
    // Caché del directorio compartido: se recalcula al volver a la app
    // (por si el usuario acaba de conceder el permiso de archivos).
    private var sharedCache: File? = null
    private val shared get() = sharedCache ?: Embed.sharedDir(this).also { sharedCache = it }
    private val stateFile get() = File(shared, "state.json")
    private val consoleLog get() = File(shared, "console.log")
    private val installLog get() = File(shared, "install.log")
    private val tunnelLog get() = File(shared, "tunnel.log")
    private val lastRunLog get() = File(shared, "last_run.log")
    private val inbox get() = File(shared, "inbox")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var pollJob: Job? = null
    private var busyJob: Job? = null
    private val prefs by lazy { getSharedPreferences("mcpanel", MODE_PRIVATE) }

    // ── estado de la UI ──────────────────────────────────────────────
    private enum class Tab(val id: String, val label: String) {
        HOME("home", "Inicio"), CONSOLE("console", "Consola"), MODS("mods", "Mods"),
        CONNECT("connect", "Conectar"), SETTINGS("settings", "Ajustes")
    }
    private var tab: Tab = Tab.HOME
    private var actionBusy = false                       // transición INICIAR/DETENER en curso
    private var busyKind: String? = null                 // server | tunnel | delete
    private var busyText: String? = null                 // etiqueta mientras está ocupado
    private var wizard = "welcome"                       // welcome|loader|version|summary|installing
    private var wizardLoader = "paper"
    private var wizardVersion: String? = null
    private var installing = false

    // ── paleta (tema propio, no Material You) ────────────────────────
    private val BG = Color.rgb(11, 15, 20)
    private val SURFACE = Color.rgb(17, 22, 29)
    private val CARD = Color.rgb(24, 30, 39)
    private val STROKE = Color.rgb(44, 54, 66)
    private val TEXT = Color.rgb(237, 242, 247)
    private val MUTED = Color.rgb(148, 163, 184)
    private val FAINT = Color.rgb(100, 116, 139)
    private val ACCENT = Color.rgb(46, 229, 157)
    private val ACCENT_DK = Color.rgb(20, 83, 62)
    private val DANGER = Color.rgb(255, 107, 94)
    private val DANGER_DK = Color.rgb(83, 25, 22)
    private val WARN = Color.rgb(255, 194, 75)
    private val TERM_BG = Color.rgb(9, 12, 17)
    private val OK_BG = Color.rgb(13, 42, 31)
    private val OFF_BG = Color.rgb(30, 32, 36)
    private val RADIUS = 16f

    private enum class Style { PRIMARY, SECONDARY, DANGER, GHOST, PLAIN }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!Embed.isBootstrapped(this)) { startActivity(Intent(this, BootstrapActivity::class.java)); finish(); return }
        val saved = prefs.getString("last_tab", Tab.HOME.id)
        tab = Tab.values().firstOrNull { it.id == saved } ?: Tab.HOME
        maybeAskNotifPermission()
        render()
    }

    override fun onResume() {
        super.onResume()
        sharedCache = null
        if (Embed.isBootstrapped(this)) render()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        render()
    }
    override fun onDestroy() { pollJob?.cancel(); busyJob?.cancel(); scope.cancel(); super.onDestroy() }

    private fun maybeAskNotifPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 7)
        }
    }

    // ═══════════════════════════ render ══════════════════════════════
    private fun render() {
        pollJob?.cancel()
        if (!Embed.isBootstrapped(this)) { startActivity(Intent(this, BootstrapActivity::class.java)); finish(); return }
        val st = readState()
        val installed = st?.optBoolean("installed") == true
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(BG) }
        root.addView(if (installed) serverBody(st) else setupBody(), LinearLayout.LayoutParams(-1, -2, 1f))
        if (installed) root.addView(navBar(), LinearLayout.LayoutParams(-1, -2))
        setContentView(root)
        // Al volver a la app con el server/túnel encendidos, garantiza que el
        // servicio de wake-lock siga activo (se auto-recupera tras un kill).
        if (installed) {
            val up = st?.optBoolean("running") == true || (st?.optJSONObject("playit")?.optBoolean("running") == true)
            if (up) KeepAliveService.want(this)
        }
    }

    private fun serverBody(st: JSONObject): View = when (tab) {
        Tab.HOME -> homeBody(st)
        Tab.CONSOLE -> consoleBody()
        Tab.MODS -> modsBody(st)
        Tab.CONNECT -> connectBody(st)
        Tab.SETTINGS -> settingsBody(st)
    }

    private fun goto(t: Tab) { tab = t; prefs.edit().putString("last_tab", t.id).apply(); render() }

    // ═══════════════════════ construcción UI ═════════════════════════
    private fun px(dp: Float): Int = (dp * resources.displayMetrics.density).toInt()

    private fun rounded(fill: Int, radius: Float, stroke: Int = 0, sw: Int = 1) =
        GradientDrawable().apply { cornerRadius = px(radius).toFloat(); setColor(fill); if (stroke != 0) setStroke(sw, stroke) }

    private fun dim(c: Int): Int = Color.rgb((Color.red(c) * 0.72f).toInt(), (Color.green(c) * 0.72f).toInt(), (Color.blue(c) * 0.72f).toInt())

    private fun sv(): ScrollView = ScrollView(this).apply { setBackgroundColor(BG); isFillViewport = false }

    private fun col(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setBackgroundColor(BG); setPadding(px(16f), px(8f), px(16f), px(28f))
    }

    private fun tv(text: String, size: Float = 14f, color: Int = TEXT, bold: Boolean = false,
                   caps: Boolean = false, mono: Boolean = false, ls: Float = 0f): TextView = TextView(this).apply {
        this.text = text; textSize = size; setTextColor(color)
        typeface = when { bold -> Typeface.DEFAULT_BOLD; mono -> Typeface.MONOSPACE; else -> Typeface.DEFAULT }
        if (caps) { isAllCaps = true } ; if (ls != 0f) letterSpacing = ls
        includeFontPadding = false
    }

    private fun styleColors(s: Style): Triple<Int, Int, Int> = when (s) {
        Style.PRIMARY -> Triple(ACCENT, Color.BLACK, 0)
        Style.SECONDARY -> Triple(CARD, TEXT, STROKE)
        Style.DANGER -> Triple(DANGER_DK, DANGER, 0)
        Style.GHOST -> Triple(Color.TRANSPARENT, MUTED, STROKE)
        Style.PLAIN -> Triple(Color.TRANSPARENT, ACCENT, 0)
    }

    private fun styleBtn(b: Button, s: Style, label: String, enabled: Boolean) {
        val (fill, fg, stroke) = styleColors(s)
        b.text = label
        b.isEnabled = enabled
        b.alpha = if (enabled) 1f else 0.45f
        b.minHeight = 0
        b.setTextColor(fg)
        b.setPadding(px(8f), 0, px(8f), 0)
        b.background = rounded(fill, RADIUS, stroke, 1)
    }

    private fun LinearLayout.addBtn(label: String, s: Style = Style.SECONDARY, height: Float = 52f,
                                    marginTop: Float = 10f, enabled: Boolean = true,
                                    onClick: () -> Unit): Button {
        val fill = styleColors(s).first
        val b = Button(this@MainActivity).apply {
            isAllCaps = false
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setOnClickListener { onClick() }
            setOnTouchListener { _, ev ->
                when (ev.action) {
                    MotionEvent.ACTION_DOWN -> if (isEnabled) background = rounded(dim(fill), RADIUS, 0)
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (isEnabled) styleBtn(this, s, label, true)
                }
                false
            }
        }
        styleBtn(b, s, label, enabled)
        addView(b, LinearLayout.LayoutParams(-1, px(height)).apply { topMargin = px(marginTop) })
        return b
    }

    private fun LinearLayout.addCard(marginTop: Float = 0f, padding: Int = 16,
                                     bg: Int = CARD, init: LinearLayout.() -> Unit) {
        val c = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(bg, RADIUS, STROKE, 1)
            setPadding(px(padding.toFloat()), px(padding.toFloat()), px(padding.toFloat()), px(padding.toFloat()))
        }
        init(c)
        addView(c, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(marginTop) })
    }

    private fun LinearLayout.addHeader(title: String, sub: String? = null) {
        addView(tv(title, 21f, TEXT, bold = true), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = px(2f) })
        if (sub != null) addView(tv(sub, 13f, MUTED))
        addView(View(this@MainActivity).apply { setBackgroundColor(Color.TRANSPARENT) }, LinearLayout.LayoutParams(-1, px(4f)))
    }

    private fun LinearLayout.addInfo(label: String, value: String, valueColor: Int = TEXT, monoValue: Boolean = true) {
        val r = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        r.addView(tv(label, 13f, MUTED), LinearLayout.LayoutParams(0, -2, 1f))
        r.addView(tv(value, 14f, valueColor, bold = true, mono = monoValue).apply { gravity = Gravity.END }, LinearLayout.LayoutParams(-2, -2))
        addView(r, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = px(10f) })
    }

    private fun pill(text: String, bg: Int, fg: Int): TextView = tv(text, 12f, fg, bold = true).apply {
        background = rounded(bg, 100f)
        setPadding(px(10f), px(4f), px(10f), px(4f))
    }

    private fun LinearLayout.addPillRow(p: TextView, alignEnd: Boolean = true) {
        if (alignEnd) { addView(p, LinearLayout.LayoutParams(-2, -2, 0f).apply { gravity = Gravity.END }) }
        else addView(p, LinearLayout.LayoutParams(-2, -2))
    }

    private fun navBar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.rgb(13, 17, 23))
            setPadding(0, px(6f), 0, px(8f))
        }
        Tab.values().forEach { t ->
            val active = t == tab
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(0, px(3f), 0, px(1f))
                setOnClickListener { if (!active) goto(t) }
            }
            item.addView(View(this).apply {
                background = rounded(if (active) ACCENT else Color.TRANSPARENT, 10f)
                alpha = if (active) 1f else 0f
            }, LinearLayout.LayoutParams(px(6f), px(6f)))
            item.addView(tv(t.label, 11f, if (active) ACCENT else FAINT, bold = active).apply { gravity = Gravity.CENTER },
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(3f) })
            bar.addView(item, LinearLayout.LayoutParams(0, -2, 1f))
        }
        return bar
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun copy(value: String) {
        (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("MCPanel", value))
        toast("Copiado: $value")
    }

    private fun open(url: String) {
        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        catch (_: Exception) { toast("No hay navegador disponible"); copy(url) }
    }

    private fun spinner(): ProgressBar = ProgressBar(this).apply {
        val c = Color.rgb(46, 229, 157)
        if (Build.VERSION.SDK_INT >= 21) indeterminateTintList = android.content.res.ColorStateList.valueOf(c)
    }

    // ═══════════════════════ estado / acciones ═══════════════════════
    private fun readState(): JSONObject? = try { JSONObject(stateFile.readText()) } catch (_: Exception) { null }

    private fun sval(st: JSONObject?, key: String): String {
        if (st == null) return ""
        val s = st.optString(key, "")
        return if (s == "null") "" else s
    }

    private fun hasError(st: JSONObject?): Boolean = sval(st, "last_error").isNotEmpty()

    private fun clearError() {
        val cur = readState() ?: return
        if (!hasError(cur)) return
        try {
            cur.put("last_error", JSONObject.NULL)
            stateFile.parentFile?.mkdirs()
            val tmp = File(stateFile.parentFile, ".state.app.tmp")
            tmp.writeText(cur.toString())
            tmp.renameTo(stateFile)
        } catch (_: Exception) { }
    }

    private fun bootstrapDone(): Boolean = File(Embed.prefix(this), "tmp/bootstrap-done").exists()

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
            i.putExtra(DownloadService.EXTRA_AFTER_ARGS, (afterArgs ?: listOf(name)).toTypedArray())
        }
        ContextCompat.startForegroundService(this, i)
    }

    private fun hasStorage(): Boolean = if (Build.VERSION.SDK_INT >= 30) {
        Environment.isExternalStorageManager()
    } else {
        checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestStorage() {
        if (Build.VERSION.SDK_INT >= 30) {
            try { startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))) }
            catch (_: Exception) { startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
        } else {
            requestPermissions(arrayOf(android.Manifest.permission.WRITE_EXTERNAL_STORAGE, android.Manifest.permission.READ_EXTERNAL_STORAGE), 1)
        }
    }

    private fun ramMB(): Int = try {
        val mi = RandomAccessFile("/proc/meminfo", "r").readLine().split(Regex("\\s+"))
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

    private fun prettyLoader(l: String): String = when (l) {
        "paper" -> "Paper"; "fabric" -> "Fabric"; "forge" -> "Forge"; "neoforge" -> "NeoForge"; else -> if (l.isEmpty()) "Servidor" else l
    }

    private fun loaderKind(l: String): String = if (l == "paper") "Plugins" else "Mods"

    @Suppress("DEPRECATION")
    private fun lanIp(): String? = try {
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val ip = wm.connectionInfo?.ipAddress ?: 0
        if (ip == 0) null else "${(ip and 0xFF)}.${(ip shr 8) and 0xFF}.${(ip shr 16) and 0xFF}.${(ip shr 24) and 0xFF}"
    } catch (_: Exception) { null }

    private fun appVersion(): String = try { packageManager.getPackageInfo(packageName, 0).versionName ?: "" } catch (_: Exception) { "" }

    private fun showLogDialog(title: String, file: File) {
        val body = TextView(this).apply {
            text = try { file.readText().takeLast(30000) } catch (_: Exception) { "(sin contenido)" }
            textSize = 11f; setTextColor(Color.rgb(160, 170, 185)); typeface = Typeface.MONOSPACE
        }
        val sc = ScrollView(this).apply { addView(body) }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(sc)
            .setPositiveButton("CERRAR", null)
            .show()
    }

    private val serverBusy: Boolean get() = actionBusy && busyKind == "server"
    private val tunnelBusy: Boolean get() = actionBusy && busyKind == "tunnel"

    /** Ejecuta un comando y deja la UI en "ocupado" hasta que el estado
     *  real cambie al esperado (2 lecturas estables). El seguimiento usa su
     *  propio job: navegar entre pestañas no deja botones muertos. */
    private fun runWithBusy(kind: String, text: String, start: () -> Unit, done: () -> Boolean) {
        if (actionBusy) return
        actionBusy = true
        busyKind = kind
        busyText = text
        clearError()
        render()
        start()
        busyJob?.cancel()
        busyJob = scope.launch {
            var stable = 0
            var waited = 0
            while (isActive && waited < 60000) {
                delay(700); waited += 700
                if (done()) stable++ else stable = 0
                if (stable >= 2) break
            }
            delay(300)
            actionBusy = false
            busyKind = null
            busyText = null
            if (isActive) render()
        }
    }

    private fun toggleServer() {
        if (serverBusy) return
        if (actionBusy) { toast("Espera a que termine la acción actual."); return }
        val want = readState()?.optBoolean("running") != true
        runWithBusy("server", if (want) "INICIANDO…" else "DETENIENDO…",
            { runTermux(if (want) "start" else "stop") },
            { (readState()?.optBoolean("running") == true) == want })
    }

    // ═══════════════════════════ PÁGINA: INICIO ═════════════════════
    private fun homeBody(st: JSONObject): View {
        val col = col()
        val running = st.optBoolean("running")
        val loader = prettyLoader(sval(st, "loader"))
        val version = sval(st, "version")
        val err = sval(st, "last_error")
        val playit = st.optJSONObject("playit")
        val pRunning = playit?.optBoolean("running") == true
        val pAddr = playit?.optString("address", "")?.takeIf { it.isNotEmpty() && it != "null" }
        val claimed = pAddr != null && !pAddr.startsWith("http")

        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(tv("MCPanel", 21f, TEXT, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
        val pillTv = pill(if (running) "● ENCENDIDO" else "○ APAGADO", if (running) OK_BG else OFF_BG, if (running) ACCENT else MUTED)
        top.addView(pillTv, LinearLayout.LayoutParams(-2, -2))
        col.addView(top)

        col.addCard(marginTop = 14f) {
            addView(tv("${loader}  ·  Minecraft $version", 17f, TEXT, bold = true))
            addView(tv(if (running) "Tu mundo está corriendo ahora mismo." else "Tu mundo está guardado y listo para arrancar.", 13f, MUTED),
                LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = px(4f) })
            if (err.isNotEmpty()) {
                addView(tv("⚠  $err", 12.5f, WARN), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = px(8f) })
                addBtn("VER QUÉ PASÓ", Style.GHOST, height = 40f, marginTop = 0f) { showLogDialog("Última ejecución", lastRunLog) }
            }
            addBtn(if (serverBusy) (busyText ?: "…") else if (running) "DETENER SERVIDOR" else "INICIAR SERVIDOR",
                if (running) Style.DANGER else Style.PRIMARY,
                enabled = !serverBusy,
                marginTop = if (err.isNotEmpty()) 10f else 14f) {
                toggleServer()
            }
        }

        // cómo conectarse
        col.addCard(marginTop = 12f) {
            addView(tv("CÓMO SE CONECTAN TUS AMIGOS", 11f, FAINT, bold = true, ls = 0.06f))
            addView(View(this@MainActivity), LinearLayout.LayoutParams(-1, px(6f)))
            if (claimed) {
                addView(tv("Dirección pública (playit)", 14f, TEXT, bold = true))
                addView(tv(pAddr!!, 15f, ACCENT, mono = true).apply { setTextIsSelectable(true) },
                    LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = px(2f) })
                addView(tv("Compártela con quien quieras que entre a tu mundo.", 12.5f, MUTED))
                addBtn("COPIAR DIRECCIÓN", Style.SECONDARY, height = 44f, marginTop = 10f) { copy(pAddr!!) }
            } else {
                val lan = lanIp()
                if (lan != null) {
                    addView(tv("Misma red Wi-Fi", 14f, TEXT, bold = true))
                    addView(tv("$lan:25565", 14f, ACCENT, mono = true).apply { setTextIsSelectable(true) },
                        LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = px(8f) })
                }
                addView(tv("Para que entren desde cualquier lugar activa el túnel gratuito:", 12.5f, MUTED),
                    LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = px(4f) })
                addBtn("CONFIGURAR TÚNEL  ·  " + if (pRunning) "ACTIVO" else "APAGADO", Style.SECONDARY, height = 44f, marginTop = 2f) { goto(Tab.CONNECT) }
            }
        }

        // datos rápidos
        col.addCard(marginTop = 12f) {
            addView(tv("DATOS", 11f, FAINT, bold = true, ls = 0.06f))
            addView(View(this@MainActivity), LinearLayout.LayoutParams(-1, px(10f)))
            addInfo("Versión", version)
            addInfo("RAM asignada", "${sval(st, "ram_min")} – ${sval(st, "ram_max")}")
            addInfo("Puerto", (if (st.has("port")) st.optInt("port", 25565) else 25565).toString())
            addInfo("Cargador", loader)
        }

        if (running) { pollJob?.cancel(); pollJob = scope.launch { watchHome(pillTv) } }
        return sv().apply { addView(col) }
    }

    /** Vigila running/playit/errores y re-renderiza sólo al cambiar. */
    private suspend fun CoroutineScope.watchHome(pillTv: TextView) {
        fun curSig(): String {
            val st = readState()
            val r = st?.optBoolean("running") == true
            val p = st?.optJSONObject("playit")?.optBoolean("running") == true
            return "$r|$p|${sval(st, "last_error")}|$actionBusy"
        }
        var last = curSig()
        while (isActive) {
            delay(1500)
            val sig = curSig()
            if (sig != last) {
                last = sig
                val r = readState()?.optBoolean("running") == true
                pillTv.text = if (r) "● ENCENDIDO" else "○ APAGADO"
                pillTv.setTextColor(if (r) ACCENT else MUTED)
                pillTv.background = rounded(if (r) OK_BG else OFF_BG, 100f)
                render()
                break
            }
        }
    }

    // ═══════════════════════════ PÁGINA: CONSOLA ════════════════════
    private fun consoleBody(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(BG); setPadding(px(16f), px(8f), px(16f), px(6f))
        }
        val st = readState()
        var running = st?.optBoolean("running") == true
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(tv("Consola", 21f, TEXT, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
        val pillTv = pill(if (running) "● EN VIVO" else "○ APAGADO", if (running) OK_BG else OFF_BG, if (running) ACCENT else MUTED)
        head.addView(pillTv, LinearLayout.LayoutParams(-2, -2))
        root.addView(head, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = px(8f) })

        val logTv = TextView(this).apply {
            textSize = 12f; setTextColor(Color.rgb(203, 213, 225)); typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(px(10f), px(10f), px(10f), px(10f))
        }
        val scroller = ScrollView(this).apply {
            setBackgroundColor(TERM_BG)
            background = rounded(TERM_BG, 12f, STROKE, 1)
            isFillViewport = false
        }
        scroller.addView(logTv, LinearLayout.LayoutParams(-1, -2))
        val cardWrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        cardWrap.addView(scroller, LinearLayout.LayoutParams(-1, -2, 1f))
        root.addView(cardWrap, LinearLayout.LayoutParams(-1, -2, 1f))

        // fila "seguir abajo" (aparece si el usuario hace scroll hacia arriba)
        val followBtn = tv("↓ IR AL FINAL", 12f, ACCENT, bold = true).apply {
            background = rounded(CARD, 100f)
            setPadding(px(12f), px(5f), px(12f), px(5f))
            visibility = View.GONE
            setOnClickListener { scroller.post { scroller.fullScroll(View.FOCUS_DOWN) } }
        }
        root.addView(followBtn, LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.END; topMargin = px(6f) })

        val input = EditText(this).apply {
            hint = "Escribe un comando… ej. op Steve  ·  stop"
            setTextColor(TEXT); setHintTextColor(FAINT)
            textSize = 13f
            setBackgroundColor(Color.TRANSPARENT)
            background = rounded(SURFACE, 12f, STROKE, 1)
            setPadding(px(12f), 0, px(12f), 0)
            imeOptions = EditorInfo.IME_ACTION_SEND
            setSingleLine(true)
        }
        val sendBtn = Button(this).apply { text = "ENVIAR"; isAllCaps = false; textSize = 13f; typeface = Typeface.DEFAULT_BOLD }
        styleBtn(sendBtn, Style.PRIMARY, "ENVIAR", true)
        val inRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        inRow.addView(input, LinearLayout.LayoutParams(0, px(48f), 1f))
        inRow.addView(sendBtn, LinearLayout.LayoutParams(px(92f), px(48f)).apply { marginStart = px(8f) })
        root.addView(inRow, LinearLayout.LayoutParams(-1, px(48f)).apply { topMargin = px(8f) })

        fun send() {
            if (!running) { toast("El servidor está apagado: inícialo desde Inicio."); return }
            val c = input.text.toString().trim()
            if (c.isEmpty()) return
            runTermux("send", c)
            input.setText("")
        }
        sendBtn.setOnClickListener { send() }
        input.setOnEditorActionListener { _, _, _ -> send(); true }

        // seguimiento del log: append diferencial (scroll estable)
        var autoFollow = true
        scroller.setOnScrollChangeListener { _, _, scrollY, _, _ ->
            val child = scroller.getChildAt(0) ?: return@setOnScrollChangeListener
            val atBottom = scrollY + scroller.height >= child.height - 60
            if (atBottom) autoFollow = true else if (scrollY > 10) autoFollow = false
            followBtn.visibility = if (autoFollow) View.GONE else View.VISIBLE
        }
        fun scrollBottom() { scroller.post { scroller.fullScroll(View.FOCUS_DOWN) } }
        var lastLen = -1L
        pollJob?.cancel()
        pollJob = scope.launch {
            while (isActive) {
                // estado en vivo: actualiza la pastilla sin re-crear la pantalla
                val stNow = readState()
                val rNow = stNow?.optBoolean("running") == true
                if (rNow != running) {
                    running = rNow
                    pillTv.text = if (rNow) "● EN VIVO" else "○ APAGADO"
                    pillTv.setTextColor(if (rNow) ACCENT else MUTED)
                    pillTv.background = rounded(if (rNow) OK_BG else OFF_BG, 100f)
                }
                val change = withContext(Dispatchers.IO) {
                    try {
                        val len = consoleLog.length()
                        if (len < 0) return@withContext null
                        if (lastLen < 0 || len < lastLen) {
                            lastLen = len
                            Triple("reset", consoleLog.readText().takeLast(400_000), len)
                        } else if (len > lastLen) {
                            RandomAccessFile(consoleLog, "r").use { raf ->
                                raf.seek(lastLen)
                                val buf = ByteArray((len - lastLen).toInt())
                                raf.readFully(buf)
                                lastLen = len
                                Triple("append", String(buf, Charsets.UTF_8), len)
                            }
                        } else null
                    } catch (_: Exception) { null }
                }
                if (change != null) {
                    val (kind, chunk, _) = change
                    if (kind == "reset") {
                        logTv.text = chunk
                        scrollBottom()
                    } else if (chunk.isNotEmpty()) {
                        logTv.append(chunk)
                        if (logTv.length() > 600_000) {
                            logTv.text = logTv.text.toString().takeLast(450_000)
                        }
                        if (autoFollow) scrollBottom()
                    }
                }
                delay(900)
            }
        }
        return root
    }

    // ═══════════════════════════ PÁGINA: MODS ═══════════════════════
    private fun modsBody(st: JSONObject): View {
        val loader = sval(st, "loader")
        val mcVersion = sval(st, "version")
        val isPlugin = loader == "paper"
        val col = col()
        col.addHeader(if (isPlugin) "Plugins" else "Mods",
            "Se instalan al instante y sin tocar nada más.")
        if (mcVersion.isEmpty()) {
            col.addView(tv("Instala un servidor primero.", color = MUTED))
            return sv().apply { addView(col) }
        }

        val query = EditText(this).apply {
            hint = if (isPlugin) "Buscar plugin (Modrinth)…" else "Buscar mod (Modrinth)…"
            setTextColor(TEXT); setHintTextColor(FAINT); textSize = 14f
            background = rounded(SURFACE, 12f, STROKE, 1)
            setPadding(px(12f), 0, px(12f), 0)
            setSingleLine(true)
        }
        val results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addCard {
            addView(tv("AÑADIR NUEVO", 11f, FAINT, bold = true, ls = 0.06f))
            addView(View(this@MainActivity), LinearLayout.LayoutParams(-1, px(8f)))
            addView(query, LinearLayout.LayoutParams(-1, px(46f)))
            addBtn("BUSCAR EN MODRINTH", Style.PRIMARY, height = 44f, marginTop = 8f) {
                val q = query.text.toString().trim()
                if (q.isEmpty()) { toast("Escribe qué buscas."); return@addBtn }
                results.removeAllViews()
                results.addView(tv("Buscando…", 13f, MUTED))
                scope.launch {
                    val hits = withContext(Dispatchers.IO) { Apis.modrinthSearch(q, mcVersion, loader) }
                    results.removeAllViews()
                    if (hits.isEmpty()) {
                        results.addView(tv("Sin resultados compatibles con $mcVersion.", color = MUTED))
                        return@launch
                    }
                    hits.forEach { h ->
                        val row = LinearLayout(this@MainActivity).apply {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = Gravity.CENTER_VERTICAL
                        }
                        val txt = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
                        txt.addView(tv(h.title, 14f, TEXT, bold = true))
                        txt.addView(tv(h.description.take(70), 11.5f, MUTED))
                        row.addView(txt, LinearLayout.LayoutParams(0, -2, 1f))
                        val b = Button(this@MainActivity).apply { text = "AÑADIR"; textSize = 12f; typeface = Typeface.DEFAULT_BOLD }
                        styleBtn(b, Style.PRIMARY, "AÑADIR", true)
                        b.setOnClickListener {
                            scope.launch {
                                val url = withContext(Dispatchers.IO) { Apis.modrinthDownloadUrl(h.slug, mcVersion, loader) }
                                if (url == null) toast("Sin versión compatible con $mcVersion.")
                                else {
                                    val name = url.substringAfterLast('/')
                                    download(url, name, "mod-install", listOf(name))
                                    toast("Descargando ${h.title}…")
                                    delay(5000)
                                    if (tab == Tab.MODS) render()
                                }
                            }
                        }
                        row.addView(b, LinearLayout.LayoutParams(px(84f), px(40f)))
                        results.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = px(10f) })
                    }
                }
            }
            addView(View(this@MainActivity).apply { background = rounded(STROKE, 1f) }, LinearLayout.LayoutParams(-1, 1).apply { topMargin = px(12f); bottomMargin = px(10f) })
            addView(tv("RESULTADOS", 11f, FAINT, bold = true, ls = 0.06f))
            addView(View(this@MainActivity), LinearLayout.LayoutParams(-1, px(8f)))
            addView(results)
        }

        // instalados
        col.addCard(marginTop = 12f) {
            addView(tv("INSTALADOS", 11f, FAINT, bold = true, ls = 0.06f))
            addView(View(this@MainActivity), LinearLayout.LayoutParams(-1, px(8f)))
            val dest = if (isPlugin) File(Embed.serverDir(this@MainActivity), "plugins") else File(Embed.serverDir(this@MainActivity), "mods")
            val files = if (dest.exists()) dest.listFiles()?.sortedBy { it.name } else null
            if (files.isNullOrEmpty()) {
                addView(tv("Nada por aquí todavía. Busca arriba y añade el primero.", color = MUTED))
            } else {
                files.forEach { f ->
                    val row = LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                    }
                    row.addView(tv(f.name, 13.5f, TEXT).apply { maxLines = 1 }, LinearLayout.LayoutParams(0, -2, 1f))
                    val del = Button(this@MainActivity).apply { text = "QUITAR"; textSize = 11.5f }
                    styleBtn(del, Style.GHOST, "QUITAR", true)
                    del.setOnClickListener {
                        AlertDialog.Builder(this@MainActivity)
                            .setTitle("Quitar ${f.name}")
                            .setMessage("Se eliminará del servidor. Puedes volver a añadirlo cuando quieras.")
                            .setNegativeButton("CANCELAR", null)
                            .setPositiveButton("QUITAR") { _, _ ->
                                try { f.delete() } catch (_: Exception) { }
                                render()
                            }.show()
                    }
                    row.addView(del, LinearLayout.LayoutParams(px(84f), px(36f)))
                    addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = px(6f) })
                }
            }
        }
        return sv().apply { addView(col) }
    }

    // ═══════════════════════════ PÁGINA: CONECTAR ═══════════════════
    private fun connectBody(st: JSONObject): View {
        val col = col()
        col.addHeader("Conectar a tu mundo", "Comparte tu servidor con amigos desde cualquier lugar (gratis).")
        val errConnect = sval(st, "last_error")
        if (errConnect.isNotEmpty()) {
            col.addCard { addView(tv("⚠  $errConnect", 12.5f, WARN)) }
        }
        val playit = st.optJSONObject("playit")
        val pRunning = playit?.optBoolean("running") == true
        val pAddr = playit?.optString("address", "")?.takeIf { it.isNotEmpty() && it != "null" }
        val pClaimed = pAddr != null && !pAddr.startsWith("http")

        val steps = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val stepTexts = listOf(
            "1.  Toca “Iniciar túnel”.",
            "2.  Se abrirá playit.gg: crea tu cuenta gratis y vincula el dispositivo.",
            "3.  Vuelve aquí: aparecerá tu dirección pública para compartir."
        )
        stepTexts.forEach { s -> steps.addView(tv(s, 13.5f, MUTED), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = px(6f) }) }

        col.addCard {
            addView(tv("CÓMO FUNCIONA", 11f, FAINT, bold = true, ls = 0.06f))
            addView(View(this@MainActivity), LinearLayout.LayoutParams(-1, px(10f)))
            addView(steps)
            addBtn(if (tunnelBusy) (busyText ?: "…") else if (pRunning) "DETENER TÚNEL" else "INICIAR TÚNEL",
                if (pRunning) Style.DANGER else Style.PRIMARY,
                enabled = !tunnelBusy, marginTop = 12f) {
                if (actionBusy) { toast("Espera a que termine la acción actual."); return@addBtn }
                val want = !pRunning
                runWithBusy("tunnel", if (pRunning) "DETENIENDO TÚNEL…" else "INICIANDO TÚNEL…",
                    { runTermux(if (pRunning) "playit-stop" else "playit-start") },
                    { (readState()?.optJSONObject("playit")?.optBoolean("running") == true) == want })
            }
        }

        // claim pendiente: enlace a abrir en el navegador
        if (pRunning && !pClaimed && pAddr != null && pAddr.startsWith("http")) {
            col.addCard(marginTop = 12f) {
                addView(tv("PASO 2 — VINCULA TU DISPOSITIVO", 11f, WARN, bold = true, ls = 0.06f))
                addView(View(this@MainActivity), LinearLayout.LayoutParams(-1, px(8f)))
                addView(tv("Abre este enlace, crea tu cuenta (o entra) y listo:", 13.5f, TEXT))
                addView(tv(pAddr, 13f, ACCENT, mono = true).apply { setTextIsSelectable(true) },
                    LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(6f); bottomMargin = px(2f) })
                addBtn("ABRIR ENLACE EN EL NAVEGADOR", Style.PRIMARY, height = 48f, marginTop = 8f) { open(pAddr) }
            }
        }
        if (pRunning && pClaimed) {
            col.addCard(marginTop = 12f) {
                addView(tv("TU DIRECCIÓN PÚBLICA", 11f, ACCENT, bold = true, ls = 0.06f))
                addView(View(this@MainActivity), LinearLayout.LayoutParams(-1, px(8f)))
                addView(tv(pAddr!!, 17f, ACCENT, bold = true, mono = true).apply { setTextIsSelectable(true) })
                addView(tv("Pásala a tus amigos: la escriben en “Multijugador → Añadir servidor”.", 12.5f, MUTED),
                    LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(4f) })
                addBtn("COPIAR DIRECCIÓN", Style.SECONDARY, height = 46f, marginTop = 8f) { copy(pAddr!!) }
                addBtn("VER EN PLAYIT.GG", Style.GHOST, height = 42f, marginTop = 6f) { open("https://playit.gg/account/tunnels") }
            }
        }

        // salida del agente (feedback honesto, sin ruido de terminal)
        val logTv = tv("", 11.5f, Color.rgb(150, 160, 175), mono = true).apply { setTextIsSelectable(true) }
        val scroll = ScrollView(this@MainActivity).apply {
            setBackgroundColor(TERM_BG); background = rounded(TERM_BG, 12f, STROKE, 1)
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        scroll.addView(logTv, LinearLayout.LayoutParams(-1, -2))
        val tunnelCard = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(CARD, RADIUS, STROKE, 1)
            setPadding(px(16f), px(16f), px(16f), px(16f))
            addView(tv("ESTADO DEL TÚNEL", 11f, FAINT, bold = true, ls = 0.06f))
            addView(View(this@MainActivity), LinearLayout.LayoutParams(-1, px(8f)))
            addView(scroll, LinearLayout.LayoutParams(-1, px(170f)))
            addView(tv("Es la salida del agente playit. Si algo falla, aquí se ve el motivo.", 11.5f, FAINT),
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(6f) })
        }
        col.addView(tunnelCard, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(12f) })
        tunnelCard.visibility = if (pRunning) View.VISIBLE else View.GONE
        // vigila playit: refresca la página si cambia el estado/dirección y
        // va mostrando la salida del agente mientras el túnel corre
        pollJob?.cancel()
        var shownTail = ""
        var lastSig = "$pRunning|${pAddr ?: ""}|${sval(st, "last_error")}"
        pollJob = scope.launch {
            while (isActive) {
                delay(1200)
                val st2 = readState()
                val run = st2?.optJSONObject("playit")?.optBoolean("running") == true
                val addr = st2?.optJSONObject("playit")?.optString("address", "")?.takeIf { it.isNotEmpty() && it != "null" }
                val sig = "$run|${addr ?: ""}|${sval(st2, "last_error")}"
                if (sig != lastSig) { render(); break }
                if (run) {
                    val tail = withContext(Dispatchers.IO) {
                        try { tunnelLog.readText().takeLast(4000) } catch (_: Exception) { "" }
                    }
                    if (tail != shownTail) { shownTail = tail; logTv.text = tail }
                }
            }
        }
        return sv().apply { addView(col) }
    }

    // ═══════════════════════════ PÁGINA: AJUSTES ════════════════════
    private fun settingsBody(st: JSONObject): View {
        val col = col()
        col.addHeader("Ajustes", "Todo lo demás, en un solo sitio.")

        col.addCard {
            addView(tv("TU SERVIDOR", 11f, FAINT, bold = true, ls = 0.06f))
            addView(View(this@MainActivity), LinearLayout.LayoutParams(-1, px(10f)))
            addInfo("Cargador", prettyLoader(sval(st, "loader")))
            addInfo("Versión de Minecraft", sval(st, "version"))
            addInfo("RAM asignada", "${sval(st, "ram_min")} – ${sval(st, "ram_max")}")
            addInfo("Puerto", (if (st.has("port")) st.optInt("port", 25565) else 25565).toString())
            addInfo("Estado", if (st.optBoolean("running")) "Encendido" else "Apagado")
        }

        col.addCard(marginTop = 12f) {
            addView(tv("RESPALDOS", 11f, FAINT, bold = true, ls = 0.06f))
            addView(View(this@MainActivity), LinearLayout.LayoutParams(-1, px(8f)))
            addView(tv("Copia tu mundo en un archivo. Se conservan los 5 más recientes.", 12.5f, MUTED))
            addBtn("CREAR RESPALDO AHORA", Style.SECONDARY, height = 44f, marginTop = 10f) {
                runTermux("backup")
                toast("Respaldo en proceso…")
                scope.launch { delay(5000); if (tab == Tab.SETTINGS) render() }
            }
            val backups = File(Embed.home(this@MainActivity), "mc_backups").listFiles()?.sortedByDescending { it.lastModified() } ?: emptyList()
            if (backups.isEmpty()) {
                addView(tv("(todavía no hay respaldos)", 12.5f, FAINT), LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(8f) })
            } else {
                backups.take(5).forEach { f ->
                    val kb = f.length() / 1024
                    addView(tv("${f.name}   ·   $kb KB", 12.5f, MUTED, mono = true),
                        LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(6f) })
                }
            }
        }

        col.addCard(marginTop = 12f) {
            addView(tv("APLICACIÓN", 11f, FAINT, bold = true, ls = 0.06f))
            addView(View(this@MainActivity), LinearLayout.LayoutParams(-1, px(10f)))
            addInfo("Versión", "0.8 (${appVersion()})")
            addInfo("Arquitectura", Embed.deviceAbi())
            addInfo("Almacenamiento", if (hasStorage()) "Concedido" else "Falta permiso", if (hasStorage()) TEXT else WARN)
            if (!hasStorage()) addBtn("CONCEDER PERMISO DE ARCHIVOS", Style.SECONDARY, height = 44f, marginTop = 4f) { requestStorage() }
        }

        col.addCard(marginTop = 12f) {
            addView(tv("PELIGRO", 11f, DANGER, bold = true, ls = 0.06f))
            addView(View(this@MainActivity), LinearLayout.LayoutParams(-1, px(8f)))
            addView(tv("Borra el servidor, su mundo, los mods y los ajustes. No se puede deshacer.", 12.5f, MUTED))
            addBtn("BORRAR SERVIDOR", Style.DANGER, height = 46f, marginTop = 10f) { confirmDelete() }
        }
        return sv().apply { addView(col) }
    }

    private fun confirmDelete() {
        val input = EditText(this).apply {
            hint = "Escribe BORRAR"
            setTextColor(TEXT); setHintTextColor(FAINT); textSize = 15f
            setBackgroundColor(Color.TRANSPARENT)
            background = rounded(SURFACE, 12f, STROKE, 1)
            setPadding(px(12f), 0, px(12f), 0)
        }
        AlertDialog.Builder(this)
            .setTitle("¿Borrar el servidor?")
            .setMessage("Se eliminará todo el mundo. Para confirmar, escribe BORRAR.")
            .setView(input)
            .setNegativeButton("CANCELAR", null)
            .setPositiveButton("BORRAR") { _, _ ->
                if (input.text.toString() == "BORRAR") {
                    if (actionBusy) { toast("Espera a que termine la acción actual."); return@setPositiveButton }
                    runWithBusy("delete", "BORRANDO…", { runTermux("server-delete") },
                        { readState()?.optBoolean("installed") != true })
                } else toast("Texto incorrecto.")
            }
            .show()
    }

    // ═══════════════════════ ASISTENTE (sin servidor) ═══════════════
    private fun setupBody(): View {
        val col = col()
        when (wizard) {
            "welcome" -> setupWelcome(col)
            "loader" -> setupLoader(col)
            "version" -> setupVersion(col)
            "summary" -> setupSummary(col)
            "installing" -> setupInstalling(col)
        }
        return sv().apply { addView(col) }
    }

    private fun stepDot(ok: Boolean): String = if (ok) "●" else "○"

    private fun setupWelcome(col: LinearLayout) {
        col.addView(View(this@MainActivity), LinearLayout.LayoutParams(-1, px(10f)))
        col.addView(tv("MCPanel", 30f, TEXT, bold = true))
        col.addView(tv("Tu servidor de Minecraft en tu teléfono.", 15f, MUTED),
            LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = px(16f) })
        val storageOk = hasStorage()
        val toolsOk = bootstrapDone()
        col.addCard {
            addView(tv("PREPARACIÓN", 11f, FAINT, bold = true, ls = 0.06f))
            addView(View(this@MainActivity), LinearLayout.LayoutParams(-1, px(12f)))
            addInfo("${stepDot(true)}  Entorno", "Listo", ACCENT, monoValue = false)
            addView(tv("El motor ya está incluido en la app.", 12f, FAINT),
                LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = px(12f) })
            addInfo("${stepDot(storageOk)}  Permiso de archivos", if (storageOk) "Otorgado" else "Opcional", if (storageOk) ACCENT else FAINT, monoValue = false)
            addView(tv("Opcional: solo para ver los archivos del mundo desde un gestor de archivos.", 12f, FAINT),
                LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = px(8f) })
            if (!storageOk) addBtn("CONCEDER PERMISO (OPCIONAL)", Style.SECONDARY, height = 44f, marginTop = 0f) { requestStorage() }
            addInfo("${stepDot(toolsOk)}  Herramientas", if (toolsOk) "Listo" else "Pendiente", if (toolsOk) ACCENT else WARN, monoValue = false)
            addView(tv("Se instalan una sola vez (Java, tmux y utilidades).", 12f, FAINT),
                LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = px(8f) })
            if (!toolsOk && !installing) addBtn("INSTALAR HERRAMIENTAS", Style.SECONDARY, height = 44f, marginTop = 0f) {
                clearError()
                installing = true
                runTermux("bootstrap")
                render()
                pollJob?.cancel()
                pollJob = scope.launch {
                    while (isActive) {
                        delay(1500)
                        if (bootstrapDone()) { installing = false; render(); break }
                    }
                }
            }
            if (installing) {
                addView(tv("Instalando herramientas… puede tardar unos minutos en la primera vez.", 12.5f, ACCENT),
                    LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(6f) })
                val row = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                row.addView(spinner(), LinearLayout.LayoutParams(px(22f), px(22f)))
                row.addView(tv("Descargando paquetes…", 12.5f, MUTED), LinearLayout.LayoutParams(-2, -2).apply { marginStart = px(10f) })
                addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(10f) })
            }
        }
        val allOk = toolsOk && !installing
        if (!allOk) {
            col.addView(tv("Primero instala las herramientas (botón de arriba).", 12.5f, WARN),
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(14f); gravity = Gravity.CENTER })
        }
        col.addBtn(if (allOk) "CREAR MI SERVIDOR" else "ESPERANDO PREPARACIÓN…",
            Style.PRIMARY, enabled = allOk, marginTop = 16f) {
            wizard = "loader"
            render()
        }
        if (!allOk && hasError(readState())) {
            col.addBtn("VER QUÉ PASÓ", Style.GHOST, height = 42f, marginTop = 4f) { showLogDialog("Última ejecución", lastRunLog) }
        }
    }

    private fun setupLoader(col: LinearLayout) {
        col.addHeader("¿Qué tipo de servidor?", "Elige según lo que quieras jugar. Puedes cambiarlo después.")
        val loaders = listOf(
            Triple("paper", "Paper", "El más popular. Rápido y estable, con plugins."),
            Triple("fabric", "Fabric", "Mods ligeros y modernos (rendimiento, optimización)."),
            Triple("forge", "Forge", "La casa de los mods clásicos."),
            Triple("neoforge", "NeoForge", "El heredero moderno de Forge.")
        )
        loaders.forEach { (id, name, desc) ->
            val sel = wizardLoader == id
            val c = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = rounded(if (sel) ACCENT_DK else CARD, RADIUS, if (sel) ACCENT else STROKE, if (sel) 2 else 1)
                setPadding(px(16f), px(14f), px(16f), px(14f))
                setOnClickListener { wizardLoader = id; render() }
            }
            val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            head.addView(tv(name, 17f, TEXT, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
            head.addView(tv(if (sel) "✓ SELECCIONADO" else if (id == "paper") "RECOMENDADO" else "", 11f, if (sel) ACCENT else FAINT, bold = true),
                LinearLayout.LayoutParams(-2, -2))
            c.addView(head)
            c.addView(tv(desc, 12.5f, MUTED), LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(4f) })
            col.addView(c, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(10f) })
        }
        col.addBtn("CONTINUAR", Style.PRIMARY, marginTop = 18f) {
            wizard = "version"
            wizardVersion = null
            render()
        }
        col.addBtn("ATRÁS", Style.GHOST, height = 44f, marginTop = 4f) { wizard = "welcome"; render() }
    }

    private fun setupVersion(col: LinearLayout) {
        col.addHeader("Versión de Minecraft", "La más reciente es casi siempre la mejor opción.")
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val manual = EditText(this).apply {
            hint = "…o escribe una versión exacta (ej. 1.20.1)"
            setTextColor(TEXT); setHintTextColor(FAINT); textSize = 14f
            background = rounded(SURFACE, 12f, STROKE, 1)
            setPadding(px(12f), 0, px(12f), 0)
            setSingleLine(true)
        }
        list.addView(tv("Cargando versiones…", 13f, MUTED))
        col.addCard { addView(list) }
        col.addCard(marginTop = 10f) { addView(manual) }

        fun pick(v: String) {
            wizardVersion = v
            manual.setText(v)
            for (i in 0 until list.childCount) {
                list.getChildAt(i).background = rounded(CARD, 10f, STROKE, 1)
            }
        }
        val loader = wizardLoader
        scope.launch {
            val versions = withContext(Dispatchers.IO) {
                when (loader) {
                    "paper" -> Apis.paperVersions()
                    "fabric" -> Apis.fabricVersions()
                    "forge" -> Apis.forgeVersions()
                    else -> Apis.neoforgeVersions()
                }
            }.filter { mcAtLeast(it, "1.17") }.take(60)
            list.removeAllViews()
            if (versions.isEmpty()) {
                list.addView(tv("Sin conexión ahora mismo. Escribe la versión manualmente.", color = WARN))
                return@launch
            }
            versions.forEachIndexed { i, v ->
                val rec = i == 0
                val row = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    background = rounded(CARD, 10f, STROKE, 1)
                    setPadding(px(12f), 0, px(12f), 0)
                    setOnClickListener { pick(v) }
                }
                row.addView(tv(v, 15f, TEXT, bold = rec, mono = true), LinearLayout.LayoutParams(0, -2, 1f))
                if (rec) row.addView(tv("MÁS RECIENTE", 10f, ACCENT, bold = true), LinearLayout.LayoutParams(-2, -2))
                list.addView(row, LinearLayout.LayoutParams(-1, px(46f)).apply { bottomMargin = px(6f) })
            }
        }
        col.addBtn("CONTINUAR", Style.PRIMARY, marginTop = 16f) {
            val typed = manual.text.toString().trim()
            val v = wizardVersion ?: typed
            if (!v.matches(Regex("""1\.\d+(\.\d+)?""")) || !mcAtLeast(v, "1.17")) {
                toast("Elige o escribe una versión válida (1.17 o superior).")
                return@addBtn
            }
            wizardVersion = v
            wizard = "summary"
            render()
        }
        col.addBtn("ATRÁS", Style.GHOST, height = 44f, marginTop = 4f) { wizard = "loader"; render() }
    }

    private fun setupSummary(col: LinearLayout) {
        val version = wizardVersion ?: ""
        val total = ramMB()
        val (rmin, rmax) = ramPreset(total)
        col.addHeader("Resumen", "Revisa antes de instalar (tarda unos minutos).")
        col.addCard {
            addInfo("Cargador", prettyLoader(wizardLoader))
            addInfo("Minecraft", version)
            addInfo("RAM del teléfono", "$total MB", MUTED, monoValue = false)
            addInfo("RAM para el servidor", "$rmin – $rmax", ACCENT)
            addInfo("Tipo", loaderKind(wizardLoader))
        }
        if (total < 3072) {
            col.addCard(marginTop = 10f) {
                addView(tv("⚠  Pocos recursos", 13f, WARN, bold = true))
                addView(tv("Este teléfono tiene poca RAM: el servidor puede ir lento o no arrancar con versiones recientes. Te recomendamos un mundo en una versión más antigua o en Paper.", 12.5f, MUTED),
                    LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(4f) })
            }
        }
        col.addBtn("INSTALAR SERVIDOR", Style.PRIMARY, marginTop = 18f) {
            clearError()
            wizard = "installing"
            render()
            startInstall(wizardLoader, version)
        }
        col.addBtn("ATRÁS", Style.GHOST, height = 44f, marginTop = 4f) { wizard = "version"; render() }
    }

    private fun startInstall(loader: String, version: String) {
        scope.launch {
            when (loader) {
                "paper" -> {
                    val build = withContext(Dispatchers.IO) { Apis.paperLatestBuild(version) }
                    if (build != null) download(Apis.paperJarUrl(version, build), "paper-$version-$build.jar")
                }
                "fabric" -> {
                    val url = withContext(Dispatchers.IO) { Apis.fabricInstallerUrl() }
                    if (url != null) download(url, "fabric-installer.jar")
                }
                "forge" -> {
                    val b = withContext(Dispatchers.IO) { Apis.forgeBuild(version) }
                    if (b != null) download(Apis.forgeInstallerUrl(version, b), "forge-$version-$b-installer.jar")
                }
                else -> {
                    val vs = withContext(Dispatchers.IO) { Apis.neoforgeVersions() }
                    val neo = vs.firstOrNull { it.startsWith(version.substringBeforeLast('.') + ".") } ?: vs.firstOrNull()
                    if (neo != null) download(Apis.neoforgeInstallerUrl(neo), "neoforge-$neo-installer.jar")
                }
            }
            runTermux("install", "--loader", loader, "--version", version)
        }
    }

    private fun setupInstalling(col: LinearLayout) {
        col.addHeader("Instalando tu servidor", "Esto tarda unos minutos. No cierres la app.")
        val (rmin, rmax) = ramPreset(ramMB())
        val done = TextView(this).apply { textSize = 14f; gravity = Gravity.CENTER; setTextColor(ACCENT); typeface = Typeface.DEFAULT_BOLD }
        val showDetails = arrayOf(false)
        col.addCard {
            val row = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            row.addView(spinner(), LinearLayout.LayoutParams(px(26f), px(26f)))
            row.addView(done, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = px(14f) })
            addView(row, LinearLayout.LayoutParams(-1, px(44f)))
            addView(tv("${prettyLoader(wizardLoader)} · Minecraft ${wizardVersion ?: ""} · RAM $rmin–$rmax", 12f, MUTED, mono = true),
                LinearLayout.LayoutParams(-1, -2).apply { gravity = Gravity.CENTER; topMargin = px(4f) })
        }
        val details = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
        val logTv = tv("", 11f, Color.rgb(160, 170, 185), mono = true).apply { setTextIsSelectable(true) }
        details.addView(logTv)
        val detailsWrap = ScrollView(this@MainActivity).apply {
            setBackgroundColor(TERM_BG); background = rounded(TERM_BG, 12f, STROKE, 1)
        }
        detailsWrap.addView(details, LinearLayout.LayoutParams(-1, -2))
        col.addCard(marginTop = 10f) {
            addView(tv("SABÍAS QUE…", 11f, FAINT, bold = true, ls = 0.06f))
            addView(tv("Mientras se instala, tu teléfono puede calentarse un poco. Es normal: está trabajando a fondo.", 12.5f, MUTED),
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(4f) })
        }
        var toggleBtn: Button? = null
        toggleBtn = col.addBtn("VER DETALLES TÉCNICOS", Style.GHOST, height = 42f, marginTop = 12f) {
            showDetails[0] = !showDetails[0]
            detailsWrap.visibility = if (showDetails[0]) View.VISIBLE else View.GONE
            toggleBtn?.text = if (showDetails[0]) "OCULTAR DETALLES TÉCNICOS" else "VER DETALLES TÉCNICOS"
        }
        detailsWrap.visibility = View.GONE
        col.addView(detailsWrap, LinearLayout.LayoutParams(-1, px(240f)).apply { topMargin = px(8f) })

        done.text = "Descargando e instalando…"
        pollJob?.cancel()
        pollJob = scope.launch {
            var lastLog = ""
            while (isActive) {
                delay(1200)
                val s = withContext(Dispatchers.IO) {
                    try {
                        installLog.readText().takeLast(60000)
                    } catch (_: Exception) { "" }
                }
                val st = readState()
                if (s != lastLog) {
                    lastLog = s
                    if (showDetails[0]) logTv.text = s
                    val phrase = friendlyPhase(s)
                    if (phrase != null) done.text = phrase
                }
                if (st?.optBoolean("installed") == true) {
                    done.text = "¡Listo! Tu servidor está instalado."
                    delay(1200)
                    wizard = "welcome"
                    prefs.edit().putString("last_tab", Tab.HOME.id).apply()
                    tab = Tab.HOME
                    render()
                    break
                }
                if (st != null && hasError(st) && sval(st, "last_action") == "error") {
                    done.setTextColor(WARN)
                    done.text = "Algo salió mal en la instalación"
                    if (showDetails[0]) logTv.text = s
                    delay(1800)
                    wizard = "summary"
                    render()
                    break
                }
            }
        }
    }

    /** Convierte las líneas del log de instalación en frases amables. */
    private fun friendlyPhase(tail: String): String? {
        val t = tail.takeLast(12000)
        return when {
            t.contains("install: complete") -> "Finalizando…"
            t.contains("installer") && t.contains("running") -> "Configurando el cargador…"
            t.contains("download") || t.contains("Downloading") || t.contains("wget") -> "Descargando los archivos del servidor…"
            t.contains("java") && (t.contains("install") || t.contains("Setting up")) -> "Instalando Java…"
            t.contains("bootstrap: base packages") -> "Herramientas listas"
            t.contains("pkg ") || t.contains("apt") || t.contains("dpkg") -> "Instalando paquetes…"
            t.contains("fabric") || t.contains("forge") || t.contains("paper") || t.contains("neoforge") -> "Preparando el cargador…"
            else -> null
        }
    }
}
