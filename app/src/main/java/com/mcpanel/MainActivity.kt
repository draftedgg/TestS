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
import android.os.PowerManager
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
import android.widget.Switch
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
 * 4 secciones. Inicio es el detalle del servidor (control + dirección);
 * la terminal queda en Consola.
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

    /** Mantener el teléfono despierto mientras el server/túnel corre (ajustable). */
    private fun keepAwakePref(): Boolean = prefs.getBoolean("keep_awake", true)

    /** Lee el último marcador de progreso "[PROG] done total" de un log. */
    private fun progFrom(logTail: String): Pair<Int, Int>? {
        var last: Pair<Int, Int>? = null
        for (line in logTail.lineSequence()) {
            val m = Regex("\\[PROG\\] (\\d+) (\\d+)").find(line) ?: continue
            val d = m.groupValues[1].toIntOrNull() ?: continue
            val t = m.groupValues[2].toIntOrNull() ?: continue
            last = d to t
        }
        return last
    }

    // ── estado de la UI ──────────────────────────────────────────────
    private enum class Tab(val id: String, val label: String) {
        HOME("home", "Inicio"), CONSOLE("console", "Consola"), MODS("mods", "Mods"),
        SETTINGS("settings", "Ajustes")
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

    private enum class Style { PRIMARY, SECONDARY, DANGER, DANGER_TEXT, GHOST, PLAIN }

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
            if (up && keepAwakePref()) KeepAliveService.want(this)
            else if (!up || !keepAwakePref()) KeepAliveService.cancel(this)
        }
    }

    private fun serverBody(st: JSONObject): View = when (tab) {
        Tab.HOME -> homeBody(st)
        Tab.CONSOLE -> consoleBody()
        Tab.MODS -> modsBody(st)
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
        Style.DANGER_TEXT -> Triple(Color.TRANSPARENT, DANGER, 0)
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
        if (!Embed.isBootstrapped(this)) { toast("Todavía se está preparando la app."); return }
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

    /** Etiqueta del juego (costura multi-juego: hoy solo Minecraft). */
    private fun gameLabel(version: String): String = "Minecraft $version"

    /** "2G"→"2GB", "512M"→"512MB". En UI solo se muestra el máximo. */
    private fun prettyRam(v: String): String {
        val t = v.trim().uppercase()
        if (t.isEmpty()) return ""
        return if (t.endsWith("B")) t else t + "B"
    }

    /** Puerto desde el estado (costura multi-juego: Terraria usará 7777). */
    private fun serverPort(st: JSONObject?): Int =
        if (st != null && st.has("port")) st.optInt("port", 25565) else 25565

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
            .setPositiveButton("Cerrar", null)
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
            while (isActive && waited < 180000) {
                delay(700); waited += 700
                if (done()) stable++ else stable = 0
                // fallo real (p. ej. playit no instalado): no esperar el timeout
                if (hasError(readState())) stable = 99
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
        runWithBusy("server", if (want) "Iniciando…" else "Deteniendo…",
            { runTermux(if (want) "start" else "stop") },
            { (readState()?.optBoolean("running") == true) == want })
    }

    // ═══════════════════════════ PÁGINA: INICIO ═════════════════════
    // Detalle del servidor: control + dirección en un solo sitio, sin tarjetas.
    private fun homeBody(st: JSONObject): View {
        val col = col()
        val running = st.optBoolean("running")
        val loader = prettyLoader(sval(st, "loader"))
        val version = sval(st, "version")
        val port = serverPort(st)
        val err = sval(st, "last_error")
        val playit = st.optJSONObject("playit")
        val pRunning = playit?.optBoolean("running") == true
        val pAddr = playit?.optString("address", "")?.takeIf { it.isNotEmpty() && it != "null" }
        val claimed = pAddr != null && !pAddr.startsWith("http")
        val claimUrl = pAddr?.takeIf { !claimed }

        // ── servidor ──
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(tv("MCPanel", 21f, TEXT, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(tv(if (running) "Encendido" else "Apagado", 12f, if (running) ACCENT else MUTED, bold = true),
            LinearLayout.LayoutParams(-2, -2))
        col.addView(top)
        col.addView(tv(loader, 18f, TEXT, bold = true),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(14f) })
        col.addView(tv(version, 12.5f, MUTED),
            LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = px(6f) })
        if (err.isNotEmpty()) {
            col.addView(tv(err, 12.5f, WARN), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = px(8f) })
            col.addBtn("Ver registro", Style.GHOST, height = 40f, marginTop = 0f) { showLogDialog("Última ejecución", lastRunLog) }
        }
        col.addBtn(if (serverBusy) (busyText ?: "…") else if (running) "Detener servidor" else "Iniciar servidor",
            if (running) Style.DANGER_TEXT else Style.PRIMARY,
            enabled = !serverBusy,
            marginTop = 14f) {
            toggleServer()
        }

        // ── dirección (una sola fila: pública, claim, LAN o nada) ──
        col.addView(tv("Dirección", 11f, FAINT, bold = true, ls = 0.06f),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(22f) })
        col.addView(View(this), LinearLayout.LayoutParams(-1, px(8f)))
        when {
            claimed -> {
                col.addView(tv(pAddr!!, 18f, ACCENT, bold = true, mono = true).apply { setTextIsSelectable(true) })
                col.addBtn("Copiar dirección", Style.SECONDARY, height = 44f, marginTop = 10f) { copy(pAddr!!) }
            }
            pRunning && claimUrl != null -> {
                col.addView(tv(claimUrl, 13f, ACCENT, mono = true).apply { setTextIsSelectable(true) },
                    LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = px(4f) })
                col.addBtn("Abrir enlace", Style.SECONDARY, height = 44f, marginTop = 8f) { open(claimUrl) }
            }
            pRunning -> {
                val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                row.addView(spinner(), LinearLayout.LayoutParams(px(20f), px(20f)))
                row.addView(tv("Conectando…", 13.5f, TEXT, bold = true),
                    LinearLayout.LayoutParams(-2, -2).apply { marginStart = px(10f) })
                col.addView(row)
                col.addBtn("Abrir playit.gg", Style.GHOST, height = 42f, marginTop = 10f) { open("https://playit.gg/account/tunnels") }
                col.addBtn("Ver registro", Style.GHOST, height = 42f, marginTop = 4f) { showLogDialog("Registro del túnel", tunnelLog) }
            }
            else -> {
                val lan = lanIp()
                if (lan != null) {
                    col.addView(tv("$lan:$port", 16f, ACCENT, bold = true, mono = true).apply { setTextIsSelectable(true) })
                    col.addView(tv("Solo esta Wi-Fi", 12f, MUTED),
                        LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(2f) })
                }
                col.addBtn(if (tunnelBusy) (busyText ?: "…") else "Iniciar túnel playit.gg", Style.GHOST, height = 44f,
                    marginTop = 8f, enabled = !tunnelBusy) {
                    if (actionBusy) { toast("Espera a que termine la acción actual."); return@addBtn }
                    val st = readState()
                    val hasSecret = st?.optJSONObject("playit")?.optBoolean("secret") == true
                    if (!hasSecret) { openPlayitSecretDialog(); return@addBtn }
                    runWithBusy("tunnel", "Iniciando túnel…", { runTermux("playit-start") },
                        { readState()?.optJSONObject("playit")?.optBoolean("running") == true })
                }
            }
        }
        if (pRunning) {
            col.addBtn(if (tunnelBusy) (busyText ?: "…") else "Detener túnel", Style.DANGER_TEXT, height = 44f,
                marginTop = 8f, enabled = !tunnelBusy) {
                if (actionBusy) { toast("Espera a que termine la acción actual."); return@addBtn }
                runWithBusy("tunnel", "Deteniendo túnel…", { runTermux("playit-stop") },
                    { readState()?.optJSONObject("playit")?.optBoolean("running") != true })
            }
        }

        pollJob?.cancel()
        pollJob = scope.launch { watchMerged() }
        return sv().apply { addView(col) }
    }

    /** Vigila servidor, túnel, dirección y errores; re-renderiza sólo al cambiar. */
    private suspend fun CoroutineScope.watchMerged() {
        fun curSig(): String {
            val st = readState()
            val r = st?.optBoolean("running") == true
            val p = st?.optJSONObject("playit")?.optBoolean("running") == true
            val a = st?.optJSONObject("playit")?.optString("address", "")?.takeIf { it.isNotEmpty() && it != "null" } ?: ""
            return "$r|$p|$a|${sval(st, "last_error")}|$actionBusy"
        }
        var last = curSig()
        while (isActive) {
            delay(1500)
            if (tab != Tab.HOME) break
            val sig = curSig()
            if (sig != last) {
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
        val pillTv = pill(if (running) "● En vivo" else "○ Apagado", if (running) OK_BG else OFF_BG, if (running) ACCENT else MUTED)
        head.addView(pillTv, LinearLayout.LayoutParams(-2, -2))
        root.addView(head, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = px(8f) })

        // Sin setTextIsSelectable: el texto seleccionable roba los gestos de
        // scroll del ScrollView padre (el scroll quedaba inutilizable).
        val logTv = TextView(this).apply {
            textSize = 12f; setTextColor(Color.rgb(203, 213, 225)); typeface = Typeface.MONOSPACE
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
        val followBtn = tv("↓ Ir al final", 12f, ACCENT, bold = true).apply {
            background = rounded(CARD, 100f)
            setPadding(px(12f), px(5f), px(12f), px(5f))
            visibility = View.GONE
            setOnClickListener { scroller.post { scroller.fullScroll(View.FOCUS_DOWN) } }
        }
        root.addView(followBtn, LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.END; topMargin = px(6f) })

        val input = EditText(this).apply {
            hint = "Escribe un comando (ej. op Steve o stop)"
            setTextColor(TEXT); setHintTextColor(FAINT)
            textSize = 13f
            setBackgroundColor(Color.TRANSPARENT)
            background = rounded(SURFACE, 12f, STROKE, 1)
            setPadding(px(12f), 0, px(12f), 0)
            imeOptions = EditorInfo.IME_ACTION_SEND
            setSingleLine(true)
        }
        val sendBtn = Button(this).apply { text = "Enviar"; isAllCaps = false; textSize = 13f; typeface = Typeface.DEFAULT_BOLD }
        styleBtn(sendBtn, Style.PRIMARY, "Enviar", true)
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
                    pillTv.text = if (rNow) "● En vivo" else "○ Apagado"
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
        col.addHeader(if (isPlugin) "Plugins" else "Mods")
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
            addView(tv("Añadir nuevo", 11f, FAINT, bold = true, ls = 0.06f))
            addView(View(this@MainActivity), LinearLayout.LayoutParams(-1, px(8f)))
            addView(query, LinearLayout.LayoutParams(-1, px(46f)))
            addBtn("Buscar en Modrinth", Style.PRIMARY, height = 44f, marginTop = 8f) {
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
                        val b = Button(this@MainActivity).apply { text = "Añadir"; textSize = 12f; typeface = Typeface.DEFAULT_BOLD }
                        styleBtn(b, Style.PRIMARY, "Añadir", true)
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
            addView(tv("Resultados", 11f, FAINT, bold = true, ls = 0.06f))
            addView(View(this@MainActivity), LinearLayout.LayoutParams(-1, px(8f)))
            addView(results)
        }

        // instalados
        col.addCard(marginTop = 12f) {
            addView(tv("Instalados", 11f, FAINT, bold = true, ls = 0.06f))
            addView(View(this@MainActivity), LinearLayout.LayoutParams(-1, px(8f)))
            val dest = if (isPlugin) File(Embed.serverDir(this@MainActivity), "plugins") else File(Embed.serverDir(this@MainActivity), "mods")
            val files = if (dest.exists()) dest.listFiles()?.sortedBy { it.name } else null
            if (files.isNullOrEmpty()) {
                addView(tv("Sin mods.", color = MUTED))
            } else {
                files.forEach { f ->
                    val row = LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                    }
                    row.addView(tv(f.name, 13.5f, TEXT).apply { maxLines = 1 }, LinearLayout.LayoutParams(0, -2, 1f))
                    val del = Button(this@MainActivity).apply { text = "Quitar"; textSize = 11.5f }
                    styleBtn(del, Style.GHOST, "Quitar", true)
                    del.setOnClickListener {
                        AlertDialog.Builder(this@MainActivity)
                            .setTitle("Quitar ${f.name}")
                            .setMessage("Se eliminará del servidor.")
                            .setNegativeButton("Cancelar", null)
                            .setPositiveButton("Quitar") { _, _ ->
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

    // ═══════════════════════════ PÁGINA: AJUSTES ════════════════════
    private fun settingsBody(st: JSONObject): View {
        val col = col()
        col.addHeader("Ajustes")
        val running = st.optBoolean("running")

        fun section(title: String) {
            col.addView(tv(title, 11f, FAINT, bold = true, ls = 0.06f),
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(20f) })
            col.addView(View(this), LinearLayout.LayoutParams(-1, px(8f)))
        }

        // ── ejecución en segundo plano ──
        section("Ejecución")
        val keepRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val keepLbl = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        keepLbl.addView(tv("Mantener activo", 15f, TEXT, bold = true))
        keepRow.addView(keepLbl, LinearLayout.LayoutParams(0, -2, 1f))
        val sw = Switch(this).apply { isChecked = keepAwakePref() }
        sw.setOnCheckedChangeListener { _, on ->
            prefs.edit().putBoolean("keep_awake", on).apply()
            if (on) KeepAliveService.want(this)
            else KeepAliveService.cancel(this)
        }
        keepRow.addView(sw, LinearLayout.LayoutParams(-2, -2).apply { marginStart = px(10f) })
        col.addView(keepRow)
        if (!isBatteryIgnored()) {
            col.addView(tv("Optimización de batería activa", 12.5f, WARN),
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(8f) })
            col.addBtn("Permitir segundo plano", Style.SECONDARY, height = 44f, marginTop = 8f) { requestIgnoreBattery() }
        }

        // ── memoria ──
        section("Memoria")
        val ramRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        ramRow.addView(tv("RAM del servidor", 15f, TEXT, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
        ramRow.addView(tv(prettyRam(sval(st, "ram_max")), 14f, ACCENT, bold = true, mono = true),
            LinearLayout.LayoutParams(-2, -2))
        col.addView(ramRow)
        if (running) col.addView(tv("Aplica al reiniciar.", 11.5f, FAINT),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(6f) })
        col.addBtn("Cambiar RAM", Style.SECONDARY, height = 44f, marginTop = 8f) { openRamDialog(st) }

        // ── propiedades ──
        section("Propiedades")
        col.addBtn("Editar propiedades", Style.SECONDARY, height = 44f, marginTop = 2f) { openPropsDialog() }

        // ── túnel playit.gg ──
        section("Túnel playit.gg")
        val hasSecret = st.optJSONObject("playit")?.optBoolean("secret") == true
        col.addView(tv(if (hasSecret) "Configurado" else "Sin configurar", 14f, if (hasSecret) ACCENT else MUTED, bold = true),
            LinearLayout.LayoutParams(-1, -2))
        if (!hasSecret) {
            col.addView(tv("Crea una cuenta en playit.gg y genera un agent (Account → Agents). Pega el secret_key abajo.", 12f, MUTED),
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(4f); bottomMargin = px(8f) })
            col.addBtn("Configurar secret_key", Style.SECONDARY, height = 44f, marginTop = 2f) { openPlayitSecretDialog() }
        } else {
            col.addView(tv("El daemon usará este secret. Tras iniciarlo, crea un Tunnel en playit.gg/account/tunnels apuntando al puerto ${serverPort(st)}.", 12f, MUTED),
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(4f); bottomMargin = px(8f) })
            col.addBtn("Cambiar secret_key", Style.SECONDARY, height = 44f, marginTop = 2f) { openPlayitSecretDialog() }
            col.addBtn("Quitar secret_key", Style.GHOST, height = 40f, marginTop = 4f) {
                AlertDialog.Builder(this).setTitle("Quitar secret_key")
                    .setMessage("El daemon no podrá iniciar hasta que pegues uno nuevo.")
                    .setNegativeButton("Cancelar", null)
                    .setPositiveButton("Quitar") { _, _ ->
                        runTermux("playit-secret-clear")
                        toast("Secret_key quitado.")
                        scope.launch { delay(1500); render() }
                    }.show()
            }
        }
        col.addBtn("Abrir playit.gg/account/agents", Style.GHOST, height = 40f, marginTop = 6f) {
            open("https://playit.gg/account/agents")
        }

        // ── respaldos ──
        section("Respaldos")
        col.addBtn("Crear respaldo", Style.SECONDARY, height = 44f, marginTop = 2f) {
            runTermux("backup")
            toast("Respaldo en proceso…")
            scope.launch { delay(5000); if (tab == Tab.SETTINGS) render() }
        }
        val backups = File(Embed.home(this), "mc_backups").listFiles()?.sortedByDescending { it.lastModified() } ?: emptyList()
        if (backups.isEmpty()) {
            col.addView(tv("Sin respaldos.", 12.5f, FAINT), LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(8f) })
        } else {
            backups.take(5).forEach { f ->
                val kb = f.length() / 1024
                col.addView(tv(f.name, 13f, TEXT, bold = true, mono = true), LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(8f) })
                col.addView(tv("$kb KB", 11f, FAINT), LinearLayout.LayoutParams(-1, -2))
            }
        }

        // ── aplicación ──
        section("Aplicación")
        col.addView(tv("Versión ${appVersion()}", 13.5f, TEXT), LinearLayout.LayoutParams(-1, -2))
        if (!hasStorage()) {
            col.addView(tv("Sin acceso a archivos.", 12f, WARN),
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(8f); bottomMargin = px(4f) })
            col.addBtn("Conceder acceso", Style.SECONDARY, height = 44f, marginTop = 2f) { requestStorage() }
        }

        // ── peligro ──
        col.addBtn("Borrar servidor", Style.DANGER_TEXT, height = 46f, marginTop = 28f) { confirmDelete() }
        return sv().apply { addView(col) }
    }

    // ── diálogo: cambiar RAM ──────────────────────────────────────────
    private fun openRamDialog(st: JSONObject) {
        val wrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, px(6f), 0, px(2f)) }
        fun field(value: String): EditText = EditText(this).apply {
            setText(value); setTextColor(TEXT); setHintTextColor(FAINT); textSize = 15f
            background = rounded(SURFACE, 12f, STROKE, 1)
            setPadding(px(12f), 0, px(12f), 0)
            setSingleLine(true)
        }
        val minE = field(sval(st, "ram_min"))
        val maxE = field(sval(st, "ram_max"))
        wrap.addView(tv("Mínima", 12f, MUTED))
        wrap.addView(minE, LinearLayout.LayoutParams(-1, px(46f)).apply { topMargin = px(4f); bottomMargin = px(10f) })
        wrap.addView(tv("Máxima", 12f, MUTED))
        wrap.addView(maxE, LinearLayout.LayoutParams(-1, px(46f)).apply { topMargin = px(4f) })
        wrap.addView(tv("Ejemplos: 512M, 1G, 2G, 1500M. La mínima es lo que reserva al arrancar; la máxima, el techo.", 11.5f, FAINT),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(10f) })
        AlertDialog.Builder(this)
            .setTitle("RAM del servidor")
            .setView(ScrollView(this).apply { addView(wrap) })
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Guardar") { _, _ ->
                val min = minE.text.toString().trim()
                val max = maxE.text.toString().trim()
                val ok = Regex("[0-9]+[MG]").matches(min) && Regex("[0-9]+[MG]").matches(max)
                if (!ok) { toast("Formato inválido (ej. 512M o 1G)."); return@setPositiveButton }
                runTermux("ram-set", min, max)
                toast("Guardado: aplica al reiniciar el servidor.")
                scope.launch { delay(1200); if (tab == Tab.SETTINGS) render() }
            }.show()
    }

    // ── diálogo: editar server.properties ─────────────────────────────
    private fun propsFile(): File = File(Embed.serverDir(this), "server.properties")

    private fun propValue(key: String): String = try {
        propsFile().readLines().firstOrNull { it.startsWith("$key=") }?.substringAfter('=')?.trim() ?: ""
    } catch (_: Exception) { "" }

    private fun openPropsDialog() {
        val wrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, px(6f), 0, px(2f)) }
        fun field(hint: String, key: String): EditText = EditText(this).apply {
            setText(propValue(key)); this.hint = hint; setTextColor(TEXT); setHintTextColor(FAINT); textSize = 14f
            background = rounded(SURFACE, 12f, STROKE, 1)
            setPadding(px(12f), 0, px(12f), 0)
            setSingleLine(true)
        }
        val gamemode = field("survival, creative, adventure, spectator", "gamemode")
        val difficulty = field("peaceful, easy, normal, hard", "difficulty")
        val maxPlayers = field("Número de jugadores", "max-players")
        val pvp = field("true o false", "pvp")
        val viewDistance = field("Distancia de visión", "view-distance")
        val motd = field("Mensaje que ven al buscar tu servidor", "motd")
        val online = field("true o false (true = solo cuentas premium)", "online-mode")
        val extra = EditText(this).apply {
            hint = "clave=valor (p. ej. spawn-protection=0)"; setTextColor(TEXT); setHintTextColor(FAINT); textSize = 14f
            background = rounded(SURFACE, 12f, STROKE, 1)
            setPadding(px(12f), 0, px(12f), 0)
            setSingleLine(true)
        }
        fun row(lbl: String, et: EditText) {
            wrap.addView(tv(lbl, 12f, MUTED).apply { setPadding(0, px(10f), 0, 0) })
            wrap.addView(et, LinearLayout.LayoutParams(-1, px(44f)).apply { topMargin = px(4f) })
        }
        row("Modo de juego", gamemode)
        row("Dificultad", difficulty)
        row("Jugadores máximos", maxPlayers)
        row("PvP", pvp)
        row("Distancia de visión", viewDistance)
        row("Mensaje (MOTD)", motd)
        row("Modo online", online)
        row("Cualquier otra propiedad", extra)
        AlertDialog.Builder(this)
            .setTitle("Propiedades del servidor")
            .setView(ScrollView(this).apply { addView(wrap) })
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Guardar") { _, _ ->
                val pairs = mutableListOf<String>()
                fun put(v: String, k: String) { if (v.isNotEmpty()) { pairs.add(k); pairs.add(v) } }
                put(gamemode.text.toString().trim(), "gamemode")
                put(difficulty.text.toString().trim(), "difficulty")
                put(maxPlayers.text.toString().trim(), "max-players")
                put(pvp.text.toString().trim(), "pvp")
                put(viewDistance.text.toString().trim(), "view-distance")
                put(motd.text.toString().trim(), "motd")
                put(online.text.toString().trim(), "online-mode")
                val ex = extra.text.toString().trim()
                if (ex.isNotEmpty()) {
                    val i = ex.indexOf('=')
                    if (i <= 0) { toast("Propiedad extra: usa formato clave=valor."); return@setPositiveButton }
                    pairs.add(ex.substring(0, i).trim()); pairs.add(ex.substring(i + 1).trim())
                }
                if (pairs.isEmpty()) { toast("No hay nada que guardar."); return@setPositiveButton }
                runTermux("prop", *pairs.toTypedArray())
                toast("Guardado: aplica al reiniciar el servidor.")
            }.show()
    }

    // ── diálogo: secret_key de playit.gg ──────────────────────────────
    private fun openPlayitSecretDialog() {
        val input = EditText(this).apply {
            hint = "playit_xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"
            setTextColor(TEXT); setHintTextColor(FAINT); textSize = 14f
            setBackgroundColor(Color.TRANSPARENT)
            background = rounded(SURFACE, 12f, STROKE, 1)
            setPadding(px(12f), 0, px(12f), 0)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setSingleLine(true)
        }
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(0, px(6f), 0, px(2f))
            addView(input, LinearLayout.LayoutParams(-1, px(46f)))
            addView(tv("Se guarda en playit.toml con permisos 600. Nunca se muestra completo en pantalla.", 11.5f, FAINT),
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(8f) })
        }
        AlertDialog.Builder(this)
            .setTitle("secret_key de playit.gg")
            .setView(ScrollView(this).apply { addView(wrap) })
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Guardar") { _, _ ->
                val key = input.text.toString().trim()
                if (key.length < 16) {
                    toast("secret_key demasiado corto (mínimo 16 caracteres).")
                    return@setPositiveButton
                }
                runTermux("playit-secret", key)
                toast("Secret guardado.")
                scope.launch { delay(1500); render() }
            }
            .setNeutralButton("Generar nuevo") { _, _ ->
                open("https://playit.gg/account/agents")
            }
            .show()
    }

    // ── batería ───────────────────────────────────────────────────────
    private fun isBatteryIgnored(): Boolean = try {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        pm.isIgnoringBatteryOptimizations(packageName)
    } catch (_: Exception) { false }

    private fun requestIgnoreBattery() {
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
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
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Borrar") { _, _ ->
                if (input.text.toString() == "BORRAR") {
                    if (actionBusy) { toast("Espera a que termine la acción actual."); return@setPositiveButton }
                    runWithBusy("delete", "Borrando…", { runTermux("server-delete") },
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

    private fun setupWelcome(col: LinearLayout) {
        col.addView(View(this@MainActivity), LinearLayout.LayoutParams(-1, px(8f)))
        col.addView(tv("MCPanel", 30f, TEXT, bold = true),
            LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = px(14f) })
        val storageOk = hasStorage()
        val toolsOk = bootstrapDone()
        val ready = storageOk && toolsOk && !installing

        if (!storageOk) {
            col.addCard {
                addView(tv("Acceso a archivos", 11f, FAINT, bold = true, ls = 0.06f))
                addView(View(this@MainActivity), LinearLayout.LayoutParams(-1, px(10f)))
                addView(tv("Tus mundos y respaldos viven en una carpeta del teléfono.", 13f, TEXT))
                addBtn("Conceder acceso a archivos", Style.PRIMARY, marginTop = 12f) {
                    requestStorage()
                }
            }
        }

        if (installing) {
            val txt = tv("Preparando…", 14f, ACCENT, bold = true)
            val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                isIndeterminate = true
                max = 100
                if (Build.VERSION.SDK_INT >= 21) {
                    progressTintList = android.content.res.ColorStateList.valueOf(ACCENT)
                    progressBackgroundTintList = android.content.res.ColorStateList.valueOf(STROKE)
                }
            }
            col.addCard(marginTop = 10f) {
                addView(txt)
                addView(bar, LinearLayout.LayoutParams(-1, px(6f)).apply { topMargin = px(10f) })
            }
            pollJob?.cancel()
            pollJob = scope.launch {
                while (isActive) {
                    delay(900)
                    if (bootstrapDone()) { installing = false; render(); break }
                    val tail = withContext(Dispatchers.IO) {
                        try { installLog.readText().takeLast(30000) } catch (_: Exception) { "" }
                    }
                    if (tail.isNotEmpty()) {
                        val p = progFrom(tail)
                        if (p != null && p.second > 0) {
                            bar.isIndeterminate = false
                            bar.max = p.second
                            bar.progress = p.first
                        } else if (p == null) {
                            bar.isIndeterminate = true
                        }
                    }
                }
            }
        } else if (!toolsOk) {
            col.addCard(marginTop = 10f) {
                addView(tv("Preparación inicial", 11f, FAINT, bold = true, ls = 0.06f))
                addView(View(this@MainActivity), LinearLayout.LayoutParams(-1, px(10f)))
                addBtn("Preparar", Style.SECONDARY, marginTop = 10f) {
                    clearError()
                    installing = true
                    runTermux("bootstrap")
                    render()
                }
            }
        }

        if (!ready) {
            val hint = when {
                !storageOk -> "Falta el acceso a archivos (botón de arriba)."
                else -> "Termina la preparación para continuar."
            }
            col.addView(tv(hint, 12.5f, WARN),
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(14f); gravity = Gravity.CENTER })
        }
        col.addBtn(if (ready) "Crear mi servidor" else "Esperando preparación…",
            Style.PRIMARY, enabled = ready, marginTop = 16f) {
            wizard = "loader"
            render()
        }
        if (!ready && hasError(readState())) {
            col.addBtn("Ver registro", Style.GHOST, height = 42f, marginTop = 4f) { showLogDialog("Última ejecución", lastRunLog) }
        }
    }

    private fun setupLoader(col: LinearLayout) {
        col.addHeader("¿Qué tipo de servidor?")
        val loaders = listOf(
            "paper" to "Paper",
            "fabric" to "Fabric",
            "forge" to "Forge",
            "neoforge" to "NeoForge"
        )
        loaders.forEach { (id, name) ->
            val sel = wizardLoader == id
            val c = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = rounded(if (sel) ACCENT_DK else CARD, RADIUS, if (sel) ACCENT else STROKE, if (sel) 2 else 1)
                setPadding(px(16f), px(14f), px(16f), px(14f))
                setOnClickListener { wizardLoader = id; render() }
            }
            val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            head.addView(tv(name, 17f, TEXT, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
            head.addView(tv(if (sel) "✓ Seleccionado" else if (id == "paper") "Recomendado" else "", 11f, if (sel) ACCENT else FAINT, bold = true),
                LinearLayout.LayoutParams(-2, -2))
            c.addView(head)
            col.addView(c, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(10f) })
        }
        col.addBtn("Continuar", Style.PRIMARY, marginTop = 18f) {
            wizard = "version"
            wizardVersion = null
            render()
        }
        col.addBtn("Atrás", Style.GHOST, height = 44f, marginTop = 4f) { wizard = "welcome"; render() }
    }

    private fun setupVersion(col: LinearLayout) {
        col.addHeader("Versión de Minecraft")
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
                if (rec) row.addView(tv("Más reciente", 10f, ACCENT, bold = true), LinearLayout.LayoutParams(-2, -2))
                list.addView(row, LinearLayout.LayoutParams(-1, px(46f)).apply { bottomMargin = px(6f) })
            }
        }
        col.addBtn("Continuar", Style.PRIMARY, marginTop = 16f) {
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
        col.addBtn("Atrás", Style.GHOST, height = 44f, marginTop = 4f) { wizard = "loader"; render() }
    }

    private fun setupSummary(col: LinearLayout) {
        val version = wizardVersion ?: ""
        val total = ramMB()
        val rmax = ramPreset(total).second
        col.addHeader("Resumen")
        col.addCard {
            addInfo("Cargador", prettyLoader(wizardLoader))
            addInfo("Juego", gameLabel(version))
            addInfo("RAM del teléfono", "$total MB", MUTED, monoValue = false)
            addInfo("RAM", prettyRam(rmax), ACCENT)
            addInfo("Tipo", loaderKind(wizardLoader))
        }
        if (total < 3072) {
            col.addView(tv("Poca RAM: puede ir lento.", 12.5f, WARN),
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(10f) })
        }
        col.addBtn("Instalar servidor", Style.PRIMARY, marginTop = 18f) {
            clearError()
            wizard = "installing"
            render()
            startInstall(wizardLoader, version)
        }
        col.addBtn("Atrás", Style.GHOST, height = 44f, marginTop = 4f) { wizard = "version"; render() }
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
        col.gravity = Gravity.CENTER_HORIZONTAL
        col.addView(View(this@MainActivity), LinearLayout.LayoutParams(-1, px(26f)))
        col.addView(tv("Instalando…", 24f, TEXT, bold = true).apply { gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(-1, -2))
        col.addView(View(this@MainActivity), LinearLayout.LayoutParams(-1, px(18f)))

        val done = TextView(this).apply { textSize = 17f; gravity = Gravity.CENTER; setTextColor(ACCENT); typeface = Typeface.DEFAULT_BOLD }
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            max = 100
            if (Build.VERSION.SDK_INT >= 21) {
                progressTintList = android.content.res.ColorStateList.valueOf(ACCENT)
                progressBackgroundTintList = android.content.res.ColorStateList.valueOf(STROKE)
            }
        }
        val detail = tv("Descargando e instalando…", 12.5f, MUTED).apply { gravity = Gravity.CENTER }
        col.addCard {
            addView(done, LinearLayout.LayoutParams(-1, -2))
            addView(detail, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(8f) })
            addView(bar, LinearLayout.LayoutParams(-1, px(6f)).apply { topMargin = px(18f) })
        }

        done.text = "Instalando…"
        detail.text = wizardVersion ?: ""
        pollJob?.cancel()
        pollJob = scope.launch {
            while (isActive) {
                delay(1100)
                val s = withContext(Dispatchers.IO) {
                    try {
                        installLog.readText().takeLast(60000)
                    } catch (_: Exception) { "" }
                }
                val st = readState()
                val p = progFrom(s)
                if (p != null && p.second > 0) {
                    bar.isIndeterminate = false
                    bar.max = p.second
                    bar.progress = p.first
                } else if (p == null) {
                    bar.isIndeterminate = true
                }
                if (st?.optBoolean("installed") == true) {
                    delay(800)
                    wizard = "welcome"
                    prefs.edit().putString("last_tab", Tab.HOME.id).apply()
                    tab = Tab.HOME
                    render()
                    break
                }
                if (st != null && hasError(st) && sval(st, "last_action") == "error") {
                    done.setTextColor(WARN)
                    done.text = "Error en la instalación"
                    delay(1800)
                    wizard = "summary"
                    render()
                    break
                }
            }
        }
    }

}
