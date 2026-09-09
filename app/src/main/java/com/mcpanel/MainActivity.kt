package com.mcpanel

import android.app.Activity
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
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
import android.view.ViewGroup
import android.view.Window
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
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
    private val debugLog get() = File(shared, "playit-debug.log")
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
    private var reinstalling = false   // reinstalación desde Software (mundo conservado)

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
    private val ACCENT_FAINT = Color.argb(38, 46, 229, 157)   // acento al 15%: pill del tab activo
    private val RADIUS = 16f
    private val RADIUS_SM = 12f

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
        addView(tv(title, 21f, TEXT, bold = true), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = px(4f) })
        if (sub != null) addView(tv(sub, 13f, MUTED))
        addView(View(this@MainActivity).apply { setBackgroundColor(Color.TRANSPARENT) }, LinearLayout.LayoutParams(-1, px(8f)))
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

    /** Fila de ajustes: etiqueta + valor opcional + chevron; toda la fila es el botón. */
    private fun LinearLayout.addRow(label: String, value: String? = null, valueColor: Int = TEXT,
                                    valueMono: Boolean = false, labelColor: Int = TEXT, marginTop: Float = 0f,
                                    onClick: (() -> Unit)? = null) {
        val r = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            if (onClick != null) {
                isClickable = true; isFocusable = true
                setOnClickListener { onClick() }
            }
        }
        r.addView(tv(label, 15f, labelColor, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
        if (value != null) {
            r.addView(tv(value, 13.5f, valueColor, bold = true, mono = valueMono).apply { maxLines = 1 },
                LinearLayout.LayoutParams(-2, -2).apply { marginStart = px(8f) })
        }
        if (onClick != null) {
            r.addView(tv("›", 17f, FAINT), LinearLayout.LayoutParams(-2, -2).apply { marginStart = px(8f) })
        }
        addView(r, LinearLayout.LayoutParams(-1, px(48f)).apply { topMargin = px(marginTop) })
    }

    /** Acciones secundarias en una sola línea de enlaces de texto (sin pila de botones). */
    private fun LinearLayout.addLinks(vararg links: Triple<String, Int, () -> Unit>) {
        if (links.isEmpty()) return
        val r = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL }
        links.forEachIndexed { i, link ->
            if (i > 0) {
                r.addView(View(this@MainActivity), LinearLayout.LayoutParams(px(14f), 1))
            }
            r.addView(tv(link.first, 13f, link.second, bold = true).apply {
                setPadding(0, px(12f), 0, px(12f))
                setOnClickListener { link.third() }
            }, LinearLayout.LayoutParams(-2, -2))
        }
        addView(r, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(10f) })
    }

    // ── panel inferior (bottom sheet propio): sustituye a los AlertDialog ──
    // Mismo tema de la app (fondo CARD, radio superior), agarre, título y
    // contenido; se cierra tocando fuera o con el botón atrás.
    private fun panel(title: String, build: LinearLayout.() -> Unit): Dialog {
        val grip = View(this).apply {
            setBackgroundColor(STROKE)
            background = rounded(STROKE, 2f)
        }
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(tv(title, 19f, TEXT, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(CARD, RADIUS, STROKE, 1)
            setPadding(px(20f), px(12f), px(20f), px(20f))
        }
        body.build()
        val sheet = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                floatArrayOf(px(20f).toFloat(), px(20f).toFloat(), px(20f).toFloat(), px(20f).toFloat(), 0f, 0f, 0f, 0f)
                setColor(SURFACE)
            }
            setPadding(px(16f), px(8f), px(16f), px(20f))
        }
        sheet.addView(grip, LinearLayout.LayoutParams(px(36f), px(4f)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = px(12f) })
        sheet.addView(header)
        sheet.addView(body, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(12f) })
        val dlg = Dialog(this)
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dlg.setContentView(sheet)
        dlg.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.BOTTOM)
        }
        dlg.show()
        return dlg
    }

    /** Fila dentro de un panel: texto a la izquierda + acción a la derecha. */
    private fun LinearLayout.panelRow(label: String, value: String? = null, valueColor: Int = MUTED,
                                      danger: Boolean = false, onClick: (() -> Unit)? = null) {
        val r = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = rounded(SURFACE, RADIUS_SM, STROKE, 1)
            setPadding(px(16f), 0, px(12f), 0)
            if (onClick != null) {
                isClickable = true; isFocusable = true
                setOnClickListener { onClick() }
            }
            minimumHeight = px(52f)
        }
        r.addView(tv(label, 14.5f, if (danger) DANGER else TEXT, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
        if (value != null) r.addView(tv(value, 13f, valueColor, bold = true, mono = true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE },
            LinearLayout.LayoutParams(-2, -2).apply { marginStart = px(8f) })
        if (onClick != null) r.addView(tv("›", 17f, FAINT), LinearLayout.LayoutParams(-2, -2).apply { marginStart = px(8f) })
        addView(r, LinearLayout.LayoutParams(-1, px(52f)).apply { topMargin = px(8f) })
    }

    /** Botón de panel a lo ancho. */
    private fun LinearLayout.panelBtn(label: String, style: Style = Style.PRIMARY, enabled: Boolean = true, onClick: () -> Unit) {
        val b = Button(this@MainActivity).apply { isAllCaps = false }
        styleBtn(b, style, label, enabled)
        b.textSize = 14.5f
        b.setOnClickListener { onClick() }
        addView(b, LinearLayout.LayoutParams(-1, px(48f)).apply { topMargin = px(12f) })
    }

    private fun navIcon(t: Tab): Int = when (t) {
        Tab.HOME -> R.drawable.ic_nav_home
        Tab.CONSOLE -> R.drawable.ic_nav_console
        Tab.MODS -> R.drawable.ic_nav_mods
        Tab.SETTINGS -> R.drawable.ic_nav_settings
    }

    private fun navBar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.rgb(13, 17, 23))
            setPadding(0, px(8f), 0, px(8f))
        }
        Tab.values().forEach { t ->
            val active = t == tab
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                // fila entera clicable: diana de toque de 48dp+, no solo el texto
                setOnClickListener { if (!active) goto(t) }
            }
            // indicador M3 Expressive: pill del color del acento al 15% detrás del icono activo
            val iconHolder = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                if (active) background = rounded(ACCENT_FAINT, 100f)
                setPadding(px(16f), px(4f), px(16f), px(4f))
            }
            iconHolder.addView(ImageView(this).apply {
                setImageResource(navIcon(t))
                imageTintList = android.content.res.ColorStateList.valueOf(if (active) ACCENT else FAINT)
                contentDescription = t.label
            }, LinearLayout.LayoutParams(px(22f), px(22f)))
            item.addView(iconHolder, LinearLayout.LayoutParams(-2, -2).apply { topMargin = px(2f) })
            item.addView(tv(t.label, 12f, if (active) ACCENT else FAINT, bold = active).apply { gravity = Gravity.CENTER },
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(4f); bottomMargin = px(4f) })
            bar.addView(item, LinearLayout.LayoutParams(0, -2, 1f))
        }
        return bar
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun copy(value: String) {
        (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("MCPanel", value))
        toast("Copied")
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

    /** "2G"→"2GB", "512M"→"512MB". En UI solo se muestra el máximo. */
    private fun prettyRam(v: String): String {
        val t = v.trim().uppercase()
        if (t.isEmpty()) return ""
        return if (t.endsWith("B")) t else t + "B"
    }

    /** Puerto desde el estado (costura multi-juego: Terraria usará 7777). */
    private fun serverPort(st: JSONObject?): Int =
        if (st != null && st.has("port")) st.optInt("port", 25565) else 25565

    @Suppress("DEPRECATION")
    private fun lanIp(): String? = try {
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val ip = wm.connectionInfo?.ipAddress ?: 0
        if (ip == 0) null else "${(ip and 0xFF)}.${(ip shr 8) and 0xFF}.${(ip shr 16) and 0xFF}.${(ip shr 24) and 0xFF}"
    } catch (_: Exception) { null }

    private fun appVersion(): String = try { packageManager.getPackageInfo(packageName, 0).versionName ?: "" } catch (_: Exception) { "" }

    private fun showLogDialog(title: String, file: File) {
        panel(title) {
            val body = TextView(this@MainActivity).apply {
                text = try { file.readText().takeLast(30000) } catch (_: Exception) { "(vacío)" }
                textSize = 11f; setTextColor(Color.rgb(160, 170, 185)); typeface = Typeface.MONOSPACE
            }
            addView(ScrollView(this@MainActivity).apply { addView(body) },
                LinearLayout.LayoutParams(-1, px(320f)).apply { topMargin = px(8f) })
            panelBtn("Cerrar", Style.SECONDARY) {}
        }
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
        if (actionBusy) { toast("Wait…"); return }
        val want = readState()?.optBoolean("running") != true
        runWithBusy("server", if (want) "Starting…" else "Stopping…",
            { runTermux(if (want) "start" else "stop") },
            { (readState()?.optBoolean("running") == true) == want })
    }

    // ═══════════════════════════ PÁGINA: INICIO ═════════════════════
    // Aternos adapted: hero address (tap=copy), full-width status banner
    // (Offline red / Starting grey / Online green), centered Start /
    // Stop+Restart, Address card with Copy, 2x2 management grid.
    // No Consola tile (already a tab). No tunnel essay: tunnel lives as
    // one tile + one Address card; full claim/admin lives in Ajustes.
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
            ?: playit?.optString("claim_url", "")?.takeIf { it.isNotEmpty() && it != "null" }
        val needsClaim = playit?.optBoolean("needs_claim") == true || claimUrl != null
        val linked = playit?.optBoolean("secret") == true
        val heroAddr = pAddr?.takeIf { claimed }
            ?: lanIp()?.let { "$it:$port" }

        // ── hero: address rules (Aternos centers it). Tap = copy. ──
        col.addView(tv(heroAddr ?: "No address yet", 22f,
            if (claimed) ACCENT else TEXT, bold = true, mono = true).apply {
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            gravity = Gravity.CENTER
            setOnClickListener { heroAddr?.let { a -> copy(a) } }
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(8f) })

        // ── status banner: full-width, Aternos colours ──
        val startedAt = st.optLong("started_at", 0L)
        val bannerBg = when {
            serverBusy -> Color.rgb(66, 72, 82)
            running -> Color.rgb(67, 160, 71)
            else -> Color.rgb(183, 28, 28)
        }
        val bannerFg = Color.WHITE
        val banner = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = rounded(bannerBg, RADIUS_SM)
            setPadding(px(14f), px(12f), px(14f), px(12f))
        }
        val statusTxt = when {
            serverBusy -> busyText ?: "Starting…"
            running -> "Online"
            else -> "Offline"
        }
        banner.addView(tv("● $statusTxt", 15f, bannerFg, bold = true),
            LinearLayout.LayoutParams(0, -2, 1f))
        if (running && startedAt > 0 && !serverBusy) {
            val up = (System.currentTimeMillis() / 1000) - startedAt
            banner.addView(tv(String.format("%d:%02d", up / 3600, (up % 3600) / 60),
                13f, bannerFg, bold = true, mono = true),
                LinearLayout.LayoutParams(-2, -2))
        }
        col.addView(banner, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(12f) })
        if (err.isNotEmpty()) {
            col.addView(tv(err, 12.5f, WARN).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END },
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(8f) })
        }

        // ── controls: centered, Aternos style ──
        fun bigBtn(label: String, fill: Int, fg: Int, enabled: Boolean, w: Int, block: () -> Unit): Button {
            val b = Button(this).apply { isAllCaps = false }
            b.text = label; b.isEnabled = enabled; b.alpha = if (enabled) 1f else 0.45f
            b.textSize = 16f; b.typeface = Typeface.DEFAULT_BOLD; b.setTextColor(fg)
            b.background = rounded(fill, RADIUS_SM); b.minHeight = 0
            b.setPadding(px(24f), 0, px(24f), 0)
            b.setOnClickListener { block() }
            return b
        }
        val START_GREEN = Color.rgb(67, 160, 71)
        val STOP_RED = Color.rgb(183, 28, 28)
        val RESTART_BLUE = Color.rgb(66, 133, 244)
        if (running) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
            row.addView(bigBtn(if (serverBusy) (busyText ?: "…") else "Stop", STOP_RED, Color.WHITE, !serverBusy, 0) { toggleServer() },
                LinearLayout.LayoutParams(px(140f), px(48f)).apply { marginEnd = px(8f); topMargin = px(16f) })
            val rb = bigBtn("Restart", RESTART_BLUE, Color.WHITE, !actionBusy, 0) {
                if (actionBusy) { toast("Wait…"); return@bigBtn }
                runWithBusy("server", "Restarting…", { runTermux("restart") },
                    { readState()?.optBoolean("running") == true })
            }
            row.addView(rb, LinearLayout.LayoutParams(px(140f), px(48f)).apply { topMargin = px(16f) })
            col.addView(row, LinearLayout.LayoutParams(-1, -2))
        } else {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
            row.addView(bigBtn(if (serverBusy) (busyText ?: "…") else "Start", START_GREEN, Color.WHITE, !serverBusy, 0) { toggleServer() },
                LinearLayout.LayoutParams(px(160f), px(48f)).apply { topMargin = px(16f) })
            col.addView(row, LinearLayout.LayoutParams(-1, -2))
        }

        // ── Address card (Aternos): blue tag + dark card + Copy ──
        col.addView(tv("Address", 12f, Color.WHITE, bold = true).apply {
            background = rounded(RESTART_BLUE, RADIUS_SM); setPadding(px(12f), px(4f), px(12f), px(4f))
        }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = px(20f) })
        val addrCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = rounded(SURFACE, RADIUS_SM, STROKE, 1)
            setPadding(px(14f), px(10f), px(8f), px(10f))
        }
        when {
            claimed -> {
                addrCard.addView(tv(pAddr!!, 14f, TEXT, bold = true, mono = true).apply {
                    maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
                }, LinearLayout.LayoutParams(0, -2, 1f))
                val cp = Button(this).apply { text = "Copy"; isAllCaps = false; textSize = 13f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE); background = rounded(RESTART_BLUE, RADIUS_SM); minHeight = 0; setPadding(px(14f), 0, px(14f), 0); setOnClickListener { copy(pAddr!!) } }
                addrCard.addView(cp, LinearLayout.LayoutParams(-2, px(36f)).apply { marginStart = px(8f) })
            }
            needsClaim && claimUrl != null -> {
                addrCard.addView(tv("Claim pending", 14f, WARN, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
                val open = Button(this).apply { text = "Open"; isAllCaps = false; textSize = 13f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE); background = rounded(RESTART_BLUE, RADIUS_SM); minHeight = 0; setPadding(px(12f), 0, px(12f), 0); setOnClickListener { open(claimUrl) } }
                addrCard.addView(open, LinearLayout.LayoutParams(-2, px(36f)).apply { marginStart = px(8f) })
            }
            else -> {
                addrCard.addView(tv(heroAddr ?: "—", 14f, MUTED, mono = true).apply {
                    maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
                }, LinearLayout.LayoutParams(0, -2, 1f))
                val cp = Button(this).apply { text = "Copy"; isAllCaps = false; textSize = 13f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE); background = rounded(RESTART_BLUE, RADIUS_SM); minHeight = 0; setPadding(px(14f), 0, px(14f), 0); setOnClickListener { heroAddr?.let { copy(it) } } }
                addrCard.addView(cp, LinearLayout.LayoutParams(-2, px(36f)).apply { marginStart = px(8f) })
            }
        }
        col.addView(addrCard, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(6f) })
        // Claim confirm lives in Ajustes now (single home for tunnel flow);
        // Inicio stays to one card, never a paragraph.

        // ── grid 2x2 (no Consola tile): Software / Worlds / Backups / Tunnel ──
        fun tile(label: String, value: String?, icon: String, onClick: () -> Unit): LinearLayout {
            val t = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = rounded(CARD, RADIUS, STROKE, 1)
                setPadding(px(14f), px(12f), px(14f), px(12f))
                setOnClickListener { onClick() }
            }
            t.addView(tv(icon, 16f, ACCENT, bold = true))
            t.addView(tv(label, 14.5f, TEXT, bold = true).apply { setPadding(0, px(6f), 0, 0) })
            if (!value.isNullOrEmpty()) t.addView(tv(value, 11.5f, FAINT, mono = true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(0, px(2f), 0, 0) })
            return t
        }
        fun toggleTunnel() {
            if (actionBusy) { toast("Wait…"); return }
            if (pRunning) runWithBusy("tunnel", "Stopping…", { runTermux("playit-stop") },
                { readState()?.optJSONObject("playit")?.optBoolean("running") != true })
            else runWithBusy("tunnel", "Connecting…", { runTermux("playit-start") },
                { readState()?.optJSONObject("playit")?.optBoolean("running") == true })
        }
        val tunnelVal = when {
            claimed -> "Linked"
            linked -> "Linked"
            needsClaim -> "Claim"
            pRunning -> "Starting…"
            else -> "Off"
        }
        val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val gridR1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val gridR2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val tiles = listOf(
            tile("Software", "$loader $version", "⚙") { openSoftwareDialog(st) },
            tile("Worlds", activeWorldLabel(st), "◉") { openWorldsPanel() },
            tile("Backups", backupsLabel(), "▣") { openBackupsPanel() },
            tile("Tunnel", tunnelVal, "⇄") {
                if (needsClaim) goto(Tab.SETTINGS) else toggleTunnel()
            }
        )
        tiles.forEachIndexed { i, t ->
            val lp = LinearLayout.LayoutParams(0, -2, 1f).apply { topMargin = px(12f); marginStart = px(if (i % 2 == 1) 6f else 0f); marginEnd = px(if (i % 2 == 0) 6f else 0f) }
            (if (i < 2) gridR1 else gridR2).addView(t, lp)
        }
        grid.addView(gridR1); grid.addView(gridR2)
        col.addView(grid, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(4f) })

        pollJob?.cancel()
        pollJob = scope.launch { watchMerged() }
        return sv().apply { addView(col) }
    }

    /** Vigila servidor, túnel, dirección y errores; re-renderiza sólo al cambiar. */
    private suspend fun CoroutineScope.watchMerged() {
        fun curSig(): String {
            val st = readState()
            val r = st?.optBoolean("running") == true
            val pj = st?.optJSONObject("playit")
            val p = pj?.optBoolean("running") == true
            val a = pj?.optString("address", "")?.takeIf { it.isNotEmpty() && it != "null" } ?: ""
            val c = pj?.optString("claim_url", "")?.takeIf { it.isNotEmpty() && it != "null" } ?: ""
            val n = pj?.optBoolean("needs_claim") == true
            val s = pj?.optBoolean("secret") == true
            return "$r|$p|$a|$c|$n|$s|${sval(st, "last_error")}|$actionBusy"
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
        val pillTv = pill(if (running) "● Online" else "● Offline", if (running) OK_BG else OFF_BG, if (running) ACCENT else MUTED)
        head.addView(pillTv, LinearLayout.LayoutParams(-2, -2))
        root.addView(head, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = px(8f) })

        // Sin setTextIsSelectable: el texto seleccionable roba los gestos de
        // scroll del ScrollView padre (el scroll quedaba inutilizable).
        // Padding inferior extra: la última línea no muere cortada en el borde.
        val logTv = TextView(this).apply {
            textSize = 12f; setTextColor(Color.rgb(203, 213, 225)); typeface = Typeface.MONOSPACE
            setPadding(px(12f), px(12f), px(12f), px(20f))
        }
        val scroller = ScrollView(this).apply {
            setBackgroundColor(TERM_BG)
            background = rounded(TERM_BG, RADIUS_SM, STROKE, 1)
            isFillViewport = false
        }
        scroller.addView(logTv, LinearLayout.LayoutParams(-1, -2))
        val cardWrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        cardWrap.addView(scroller, LinearLayout.LayoutParams(-1, -2, 1f))
        root.addView(cardWrap, LinearLayout.LayoutParams(-1, -2, 1f))

        // fila "seguir abajo" (aparece si el usuario hace scroll hacia arriba)
        val followBtn = tv("↓ Final", 12f, ACCENT, bold = true).apply {
            background = rounded(CARD, 100f)
            setPadding(px(12f), px(5f), px(12f), px(5f))
            visibility = View.GONE
            setOnClickListener { scroller.post { scroller.fullScroll(View.FOCUS_DOWN) } }
        }
        root.addView(followBtn, LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.END; topMargin = px(6f) })

        val input = EditText(this).apply {
            hint = "Comando"
            setTextColor(TEXT); setHintTextColor(FAINT)
            textSize = 13f
            setBackgroundColor(Color.TRANSPARENT)
            background = rounded(SURFACE, RADIUS_SM, STROKE, 1)
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
            if (!running) { toast("Servidor apagado."); return }
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
                    pillTv.text = if (rNow) "● Online" else "● Offline"
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
            col.addView(tv("Sin servidor.", color = MUTED))
            return sv().apply { addView(col) }
        }

        val query = EditText(this).apply {
            hint = "Buscar en Modrinth"
            setTextColor(TEXT); setHintTextColor(FAINT); textSize = 14f
            background = rounded(SURFACE, RADIUS_SM, STROKE, 1)
            setPadding(px(12f), 0, px(12f), 0)
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_SEARCH
        }
        val results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(query, LinearLayout.LayoutParams(-1, px(46f)).apply { topMargin = px(4f) })
        col.addView(results, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(12f) })

        fun search() {
            val q = query.text.toString().trim()
            if (q.isEmpty()) return
            results.removeAllViews()
            results.addView(tv("Buscando…", 13f, MUTED))
            scope.launch {
                val hits = withContext(Dispatchers.IO) { Apis.modrinthSearch(q, mcVersion, loader) }
                results.removeAllViews()
                if (hits.isEmpty()) {
                    results.addView(tv("Sin resultados", 12.5f, MUTED))
                    return@launch
                }
                hits.forEach { h ->
                    val row = LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                    }
                    val txt = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
                    txt.addView(tv(h.title, 14f, TEXT, bold = true))
                    txt.addView(tv(h.description, 11.5f, MUTED).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END })
                    row.addView(txt, LinearLayout.LayoutParams(0, -2, 1f))
                    val add = tv("Añadir", 13f, ACCENT, bold = true).apply {
                        setPadding(px(10f), px(12f), px(2f), px(12f))
                        setOnClickListener {
                            scope.launch {
                                val url = withContext(Dispatchers.IO) { Apis.modrinthDownloadUrl(h.slug, mcVersion, loader) }
                                if (url == null) toast("No compatible.")
                                else {
                                    val name = url.substringAfterLast('/')
                                    download(url, name, "mod-install", listOf(name))
                                    toast("Descargando…")
                                    delay(5000)
                                    if (tab == Tab.MODS) render()
                                }
                            }
                        }
                    }
                    row.addView(add, LinearLayout.LayoutParams(-2, -2))
                    results.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = px(10f) })
                }
            }
        }
        query.setOnEditorActionListener { _, _, _ -> search(); true }

        // instalados con contador
        val dest = if (isPlugin) File(Embed.serverDir(this@MainActivity), "plugins") else File(Embed.serverDir(this@MainActivity), "mods")
        val files = if (dest.exists()) dest.listFiles()?.sortedBy { it.name } else null
        col.addView(tv("Instalados (${files?.size ?: 0})", 11f, FAINT, bold = true, ls = 0.06f),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(24f); bottomMargin = px(4f) })
        if (files.isNullOrEmpty()) {
            col.addView(tv("Sin instalados.", 12.5f, MUTED))
        } else {
            files.forEach { f ->
                val row = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                row.addView(tv(f.name, 13.5f, TEXT, mono = true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE }, LinearLayout.LayoutParams(0, -2, 1f))
                val del = tv("Borrar", 13f, DANGER, bold = true).apply {
                    setPadding(px(10f), px(12f), px(2f), px(12f))
                    setOnClickListener {
                        panel("¿Borrar?") {
                            addView(tv(f.name, 13.5f, TEXT, mono = true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE })
                            panelBtn("Borrar", Style.DANGER) {
                                try { f.delete() } catch (_: Exception) { }
                                render()
                            }
                            panelBtn("Cancelar", Style.GHOST) {}
                        }
                    }
                }
                row.addView(del, LinearLayout.LayoutParams(-2, -2))
                col.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = px(8f) })
            }
        }
        return sv().apply { addView(col) }
    }

    // ═══════════════════════════ PÁGINA: AJUSTES ════════════════════
    // Filas con valor + chevron, como los ajustes nativos. Un diálogo por
    // sección; nada de botones de ancho completo apilados.
    private fun settingsBody(st: JSONObject): View {
        val col = col()
        col.addHeader("Ajustes")
        val playit = st.optJSONObject("playit")
        val linked = playit?.optBoolean("secret") == true
        val claimUrl = playit?.optString("claim_url", "")?.takeIf { it.isNotEmpty() && it != "null" }
            ?: playit?.optString("address", "")?.takeIf { it.startsWith("http") }
        val needsClaim = playit?.optBoolean("needs_claim") == true || claimUrl != null

        fun section(title: String) {
            col.addView(tv(title, 11f, FAINT, bold = true, ls = 0.06f),
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(20f); bottomMargin = px(2f) })
        }

        // ── servidor (Respaldos vive solo en Inicio, no duplicado) ──
        section("Servidor")
        col.addRow("RAM", prettyRam(sval(st, "ram_max")), ACCENT, valueMono = true, marginTop = 8f) { openRamDialog(st) }
        col.addRow("Propiedades") { openPropsDialog() }

        // ── túnel: único hogar del flujo claim/admin ──
        section("Túnel")
        if (needsClaim && claimUrl != null && !linked) {
            col.addView(tv(claimUrl, 13f, ACCENT, mono = true),
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(8f) })
            col.addLinks(
                Triple("Abrir enlace", ACCENT, { open(claimUrl) }),
                Triple("Confirmar", ACCENT, {
                    if (actionBusy) { toast("Wait…"); return@Triple }
                    runWithBusy("tunnel", "Linking…", { runTermux("playit-exchange") },
                        { readState()?.optJSONObject("playit")?.optBoolean("needs_claim") != true })
                }))
        } else {
            col.addRow("playit.gg", if (linked) "Linked" else "Off", if (linked) ACCENT else MUTED, marginTop = 8f) {
                if (linked) openTunnelDialog(st) else {
                    if (actionBusy) { toast("Wait…"); return@addRow }
                    runWithBusy("tunnel", "Connecting…", { runTermux("playit-start") },
                        { readState()?.optJSONObject("playit")?.optBoolean("running") == true })
                }
            }
            if (linked) {
                col.addRow("Dirección manual", "Escribir", MUTED, marginTop = 0f) { openAddressDialog() }
            }
        }

        // ── segundo plano ──
        section("Segundo plano")
        val keepRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        keepRow.addView(tv("Mantener activo", 15f, TEXT, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
        val sw = Switch(this).apply { isChecked = keepAwakePref() }
        sw.setOnCheckedChangeListener { _, on ->
            prefs.edit().putBoolean("keep_awake", on).apply()
            if (on) KeepAliveService.want(this)
            else KeepAliveService.cancel(this)
        }
        keepRow.addView(sw, LinearLayout.LayoutParams(-2, -2).apply { marginStart = px(10f) })
        col.addView(keepRow, LinearLayout.LayoutParams(-1, px(48f)).apply { topMargin = px(8f) })
        if (!isBatteryIgnored()) {
            col.addRow("Batería", "On", WARN) { requestIgnoreBattery() }
        }

        // ── aplicación ──
        section("Aplicación")
        col.addRow("App", appVersion(), MUTED, valueMono = true, marginTop = 8f)
        if (!hasStorage()) {
            col.addRow("Archivos", "Off", WARN) { requestStorage() }
        }

        // ── peligro ──
        col.addLinks(Triple("Borrar servidor", DANGER, { confirmDelete() }))
        return sv().apply { addView(col) }
    }

    /** Etiqueta para la fila Respaldos: nº de copias o vacío. */
    private fun backupsLabel(): String {
        val n = File(Embed.home(this), "mc_backups").listFiles()?.size ?: 0
        return if (n == 0) "" else "$n"
    }

    /** Túnel vinculado: diagnóstico + borrar vínculo. */
    private fun openTunnelDialog(st: JSONObject) {
        panel("playit.gg") {
            panelBtn("Diagnóstico", Style.SECONDARY) {
                runTermux("playit-debug")
                toast("Generando…")
                scope.launch { delay(2000); showLogDialog("Diagnóstico", debugLog) }
            }
            panelBtn("Borrar vínculo", Style.DANGER) {
                runTermux("playit-unlink")
                toast("Borrado.")
                scope.launch { delay(1500); render() }
            }
        }
    }

    // ── panel: software (loader + versión, estilo Aternos) ──────────
    /** Mundo activo para la fila de Inicio (level-name desde state.worlds). */
    private fun activeWorldLabel(st: JSONObject): String {
        val arr = st.optJSONArray("worlds") ?: return ""
        for (i in 0 until arr.length()) {
            val w = arr.optJSONObject(i) ?: continue
            if (w.optBoolean("active")) return w.optString("name")
        }
        return ""
    }

    private fun openSoftwareDialog(st: JSONObject) {
        val loader = prettyLoader(sval(st, "loader"))
        val version = sval(st, "version")
        panel("Software") {
            panelRow("Software", loader, ACCENT)
            panelRow("Version", version, ACCENT)
            panelRow("World", activeWorldLabel(st), MUTED) { openWorldsPanel() }
            addView(tv("Reinstalls. World kept.", 11.5f, FAINT),
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(12f) })
            panelBtn("Change", Style.PRIMARY) { openVersionPicker(sval(st, "loader"), version) }
        }
    }

    /** Selector de versión para el loader actual + reinstalación. */
    private fun openVersionPicker(loader: String, current: String) {
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        var picked: String? = null
        var dlgRef: Dialog? = null
        val reinstallBtn = Button(this).apply { isAllCaps = false; isEnabled = false; alpha = 0.45f }
        dlgRef = panel("Version") {
            addView(tv("Loading…", 13f, MUTED))
            addView(ScrollView(this@MainActivity).apply { addView(list) },
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(8f) })
            styleBtn(reinstallBtn, Style.PRIMARY, "Reinstalar", false)
            reinstallBtn.textSize = 14.5f
            reinstallBtn.setOnClickListener {
                val v = picked ?: return@setOnClickListener
                dlgRef?.dismiss()
                confirmReinstall(loader, v)
            }
            addView(reinstallBtn, LinearLayout.LayoutParams(-1, px(48f)).apply { topMargin = px(12f) })
        }
        scope.launch {
            val versions = withContext(Dispatchers.IO) {
                when (loader) {
                    "paper" -> Apis.paperVersions()
                    "fabric" -> Apis.fabricVersions()
                    "forge" -> Apis.forgeVersions()
                    else -> Apis.neoforgeVersions()
                }
            }.filter { mcAtLeast(it, "1.17") }.take(20)
            list.removeAllViews()
            if (versions.isEmpty()) {
                list.addView(tv("No connection.", 12.5f, WARN))
                return@launch
            }
            versions.forEach { v ->
                val sel = v == current
                val row = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    background = rounded(if (sel) ACCENT_FAINT else SURFACE, RADIUS_SM, if (sel) ACCENT else STROKE, if (sel) 2 else 1)
                    setPadding(px(12f), 0, px(12f), 0)
                    setOnClickListener {
                        picked = v
                        for (i in 0 until list.childCount) {
                            val c = list.getChildAt(i)
                            val isSel = c.tag == v
                            c.background = rounded(if (isSel) ACCENT_FAINT else SURFACE, RADIUS_SM, if (isSel) ACCENT else STROKE, if (isSel) 2 else 1)
                        }
                        styleBtn(reinstallBtn, Style.PRIMARY, "Reinstalar", true)
                        reinstallBtn.isEnabled = true; reinstallBtn.alpha = 1f
                    }
                }
                row.tag = v
                row.addView(tv(v, 14.5f, TEXT, bold = sel, mono = true), LinearLayout.LayoutParams(0, -2, 1f))
                if (sel) row.addView(tv("Current", 10.5f, ACCENT, bold = true), LinearLayout.LayoutParams(-2, -2))
                list.addView(row, LinearLayout.LayoutParams(-1, px(48f)).apply { bottomMargin = px(8f) })
            }
        }
    }

    private fun confirmReinstall(loader: String, version: String) {
        panel("Reinstall?") {
            addView(tv("$loader $version", 13.5f, TEXT).apply { setLineSpacing(px(2f).toFloat(), 0f) })
            panelBtn("Reinstall", Style.PRIMARY) {
                reinstalling = true
                wizard = "installing"
                render()
                startInstall(loader, version)
            }
            panelBtn("Cancelar", Style.GHOST) {}
        }
    }

    // ── panel: mundos (lista, cambiar, nuevo, borrar) ────────────────
    private fun openWorldsPanel() {
        runTermux("world-list")
        scope.launch {
            delay(900)
            val st = readState()
            val arr = st?.optJSONArray("worlds")
            val running = st?.optBoolean("running") == true
            runOnUiThread {
                panel("Worlds") {
                    if (running) addView(tv("Stop server to manage.", 12f, WARN),
                        LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = px(8f) })
                    if (arr == null || arr.length() == 0) {
                        addView(tv("No worlds.", 13f, MUTED))
                    } else {
                        for (i in 0 until arr.length()) {
                            val w = arr.optJSONObject(i) ?: continue
                            val name = w.optString("name")
                            val active = w.optBoolean("active")
                            val r = LinearLayout(context).apply {
                                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                                background = rounded(SURFACE, RADIUS_SM, if (active) ACCENT else STROKE, if (active) 2 else 1)
                                setPadding(px(16f), 0, px(12f), 0)
                            }
                            r.addView(tv(name, 14.5f, TEXT, bold = true, mono = true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE },
                                LinearLayout.LayoutParams(0, -2, 1f))
                            if (active) {
                                r.addView(tv("Active", 11f, ACCENT, bold = true), LinearLayout.LayoutParams(-2, -2))
                            } else {
                                r.addView(tv("Use", 13f, ACCENT, bold = true).apply {
                                    setPadding(px(12f), px(12f), px(8f), px(12f))
                                    setOnClickListener {
                                        if (running) { toast("Stop server."); return@setOnClickListener }
                                        runTermux("world-use", name)
                                        toast("Active on restart.")
                                        scope.launch { delay(800); if (tab == Tab.HOME) render() }
                                    }
                                }, LinearLayout.LayoutParams(-2, -2))
                                r.addView(tv("Delete", 13f, DANGER, bold = true).apply {
                                    setPadding(px(8f), px(12f), 0, px(12f))
                                    setOnClickListener { confirmWorldDelete(name) }
                                }, LinearLayout.LayoutParams(-2, -2))
                            }
                            addView(r, LinearLayout.LayoutParams(-1, px(52f)).apply { topMargin = px(8f) })
                        }
                    }
                    panelBtn("New world", Style.SECONDARY) { openNewWorldPanel() }
                }
            }
        }
    }

    private fun confirmWorldDelete(name: String) {
        panel("Delete world?") {
            addView(tv(name, 13.5f, TEXT, mono = true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE })
            panelBtn("Delete", Style.DANGER) {
                runTermux("world-delete", name)
                toast("Deleted.")
                scope.launch { delay(800); if (tab == Tab.HOME) render() }
            }
            panelBtn("Cancel", Style.GHOST) {}
        }
    }

    /** Un mundo nuevo = cambiar level-name; el servidor lo genera al arrancar. */
    private fun openNewWorldPanel() {
        val input = EditText(this).apply {
            hint = "Name"; setTextColor(TEXT); setHintTextColor(FAINT); textSize = 15f
            background = rounded(SURFACE, RADIUS_SM, STROKE, 1)
            setPadding(px(12f), 0, px(12f), 0)
            setSingleLine(true)
        }
        panel("New world") {
            addView(input, LinearLayout.LayoutParams(-1, px(48f)).apply { topMargin = px(8f) })
            panelBtn("Create", Style.PRIMARY) {
                val n = input.text.toString().trim()
                if (!n.matches(Regex("[A-Za-z0-9_ -]+")) || n.isEmpty()) { toast("Invalid name."); return@panelBtn }
                if (readState()?.optBoolean("running") == true) { toast("Stop server."); return@panelBtn }
                runTermux("prop", "level-name", n.replace(" ", "_"))
                toast("Active on restart.")
                scope.launch { delay(800); if (tab == Tab.HOME) render() }
            }
        }
    }

    // ── panel: respaldos (crear, restaurar, borrar) ─────────────────
    private fun openBackupsPanel() {
        val backups = File(Embed.home(this), "mc_backups").listFiles()?.sortedByDescending { it.lastModified() } ?: emptyList()
        panel("Backups") {
            if (backups.isEmpty()) {
                addView(tv("No backups.", 13f, MUTED))
            } else {
                backups.forEach { f ->
                    val kb = f.length() / 1024
                    val kbS = if (kb >= 1024) "${kb / 1024} MB" else "$kb KB"
                    val r = LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                        background = rounded(SURFACE, RADIUS_SM, STROKE, 1)
                        setPadding(px(16f), 0, px(8f), 0)
                    }
                    val txt = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
                    txt.addView(tv(f.name, 13f, TEXT, bold = true, mono = true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE })
                    txt.addView(tv(kbS, 11f, FAINT))
                    r.addView(txt, LinearLayout.LayoutParams(0, -2, 1f))
                    r.addView(tv("Restore", 13f, ACCENT, bold = true).apply {
                        setPadding(px(10f), px(12f), px(6f), px(12f))
                        setOnClickListener { confirmBackupRestore(f.name) }
                    }, LinearLayout.LayoutParams(-2, -2))
                    r.addView(tv("Delete", 13f, DANGER, bold = true).apply {
                        setPadding(px(6f), px(12f), 0, px(12f))
                        setOnClickListener { confirmBackupDelete(f.name) }
                    }, LinearLayout.LayoutParams(-2, -2))
                    addView(r, LinearLayout.LayoutParams(-1, px(56f)).apply { topMargin = px(8f) })
                }
            }
            panelBtn("Create backup", Style.PRIMARY) {
                runTermux("backup"); toast("Creating…")
            }
            addView(tv("Max 5. Restore replaces world.", 11f, FAINT),
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(8f) })
        }
    }

    private fun confirmBackupRestore(name: String) {
        panel("Restore?") {
            addView(tv(name, 13.5f, TEXT, mono = true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE })
            panelBtn("Restore", Style.PRIMARY) {
                runTermux("backup-restore", name)
                toast("Restoring…")
            }
            panelBtn("Cancel", Style.GHOST) {}
        }
    }

    private fun confirmBackupDelete(name: String) {
        panel("Delete backup?") {
            addView(tv(name, 13.5f, TEXT, mono = true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE })
            panelBtn("Delete", Style.DANGER) {
                runTermux("backup-delete", name)
                toast("Deleted.")
            }
            panelBtn("Cancel", Style.GHOST) {}
        }
    }

    // ── diálogo: dirección manual del túnel ──────────────────────────
    private fun openAddressDialog() {
        var dlgRef: Dialog? = null
        val input = EditText(this).apply {
            hint = "xxx.tun.ply.gg"; setTextColor(TEXT); setHintTextColor(FAINT); textSize = 15f
            background = rounded(SURFACE, RADIUS_SM, STROKE, 1)
            setPadding(px(12f), 0, px(12f), 0)
            setSingleLine(true)
        }
        dlgRef = panel("Tunnel address") {
            addView(input, LinearLayout.LayoutParams(-1, px(46f)).apply { topMargin = px(8f) })
            panelBtn("Save", Style.PRIMARY) {
                var v = input.text.toString().trim()
                v = v.substringAfter("://", v).substringBefore('/').substringBefore('?')
                val host: String
                val parts = v.split(':')
                if (parts.size > 1) {
                    host = parts[0]
                    val p = parts[1].toIntOrNull()
                    if (host.isEmpty() || p == null || p !in 1..65535 || !Regex("[A-Za-z0-9._-]+").matches(host)) {
                        toast("Host o host:puerto."); return@panelBtn
                    }
                    v = "$host:$p"
                } else {
                    host = v
                    if (host.isEmpty() || !Regex("[A-Za-z0-9._-]+").matches(host)) {
                        toast("Host o host:puerto."); return@panelBtn
                    }
                }
                dlgRef?.dismiss()
                runTermux("playit-address", v)
                toast("Saved.")
                scope.launch { delay(1200); render() }
            }
        }
    }

    // ── diálogo: cambiar RAM ──────────────────────────────────────────
    private fun openRamDialog(st: JSONObject) {
        fun field(value: String): EditText = EditText(this).apply {
            setText(value); setTextColor(TEXT); setHintTextColor(FAINT); textSize = 15f
            background = rounded(SURFACE, RADIUS_SM, STROKE, 1)
            setPadding(px(12f), 0, px(12f), 0)
            setSingleLine(true)
        }
        val minE = field(sval(st, "ram_min"))
        val maxE = field(sval(st, "ram_max"))
        panel("RAM") {
            addView(tv("Mín", 12f, MUTED))
            addView(minE, LinearLayout.LayoutParams(-1, px(48f)).apply { topMargin = px(4f); bottomMargin = px(12f) })
            addView(tv("Máx", 12f, MUTED))
            addView(maxE, LinearLayout.LayoutParams(-1, px(48f)).apply { topMargin = px(4f) })
            addView(tv("Ej: 512M, 1G, 2G.", 11.5f, FAINT),
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(12f) })
            panelBtn("Guardar", Style.PRIMARY) {
                val min = minE.text.toString().trim()
                val max = maxE.text.toString().trim()
                val ok = Regex("[0-9]+[MG]").matches(min) && Regex("[0-9]+[MG]").matches(max)
                if (!ok) { toast("Formato: 512M o 1G."); return@panelBtn }
                runTermux("ram-set", min, max)
                toast("Guardado.")
                scope.launch { delay(1200); if (tab == Tab.SETTINGS) render() }
            }
        }
    }

    // ── diálogo: editar server.properties ─────────────────────────────
    private fun propsFile(): File = File(Embed.serverDir(this), "server.properties")

    private fun propValue(key: String): String = try {
        propsFile().readLines().firstOrNull { it.startsWith("$key=") }?.substringAfter('=')?.trim() ?: ""
    } catch (_: Exception) { "" }

    private fun openPropsDialog() {
        fun field(hint: String, key: String): EditText = EditText(this).apply {
            setText(propValue(key)); this.hint = hint; setTextColor(TEXT); setHintTextColor(FAINT); textSize = 14f
            background = rounded(SURFACE, RADIUS_SM, STROKE, 1)
            setPadding(px(12f), 0, px(12f), 0)
            setSingleLine(true)
        }
        val gamemode = field("survival…", "gamemode")
        val difficulty = field("peaceful…", "difficulty")
        val maxPlayers = field("20", "max-players")
        val pvp = field("true/false", "pvp")
        val viewDistance = field("10", "view-distance")
        val motd = field("MOTD", "motd")
        val online = field("true/false", "online-mode")
        val extra = EditText(this).apply {
            hint = "clave=valor"; setTextColor(TEXT); setHintTextColor(FAINT); textSize = 14f
            background = rounded(SURFACE, RADIUS_SM, STROKE, 1)
            setPadding(px(12f), 0, px(12f), 0)
            setSingleLine(true)
        }
        panel("Propiedades") {
            fun row(lbl: String, et: EditText) {
                addView(tv(lbl, 12f, MUTED).apply { setPadding(0, px(10f), 0, 0) })
                addView(et, LinearLayout.LayoutParams(-1, px(44f)).apply { topMargin = px(4f) })
            }
            row("Gamemode", gamemode)
            row("Difficulty", difficulty)
            row("Max players", maxPlayers)
            row("PvP", pvp)
            row("View distance", viewDistance)
            row("MOTD", motd)
            row("Online mode", online)
            row("Extra", extra)
            panelBtn("Guardar", Style.PRIMARY) {
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
                    if (i <= 0) { toast("Usa clave=valor."); return@panelBtn }
                    pairs.add(ex.substring(0, i).trim()); pairs.add(ex.substring(i + 1).trim())
                }
                if (pairs.isEmpty()) return@panelBtn
                runTermux("prop", *pairs.toTypedArray())
                toast("Guardado.")
            }
        }
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
        var dlgRef: Dialog? = null
        val input = EditText(this).apply {
            hint = "BORRAR"
            setTextColor(TEXT); setHintTextColor(FAINT); textSize = 15f
            setBackgroundColor(Color.TRANSPARENT)
            background = rounded(SURFACE, RADIUS_SM, STROKE, 1)
            setPadding(px(12f), 0, px(12f), 0)
        }
        dlgRef = panel("Borrar servidor?") {
            addView(input, LinearLayout.LayoutParams(-1, px(48f)).apply { topMargin = px(8f) })
            panelBtn("Borrar", Style.DANGER) {
                if (input.text.toString() == "BORRAR") {
                    if (actionBusy) { toast("Wait…"); return@panelBtn }
                    dlgRef?.dismiss()
                    runWithBusy("delete", "Borrando…", { runTermux("server-delete") },
                        { readState()?.optBoolean("installed") != true })
                } else toast("Escribe BORRAR.")
            }
            panelBtn("Cancelar", Style.GHOST) {}
        }
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
                addView(tv("Archivos", 11f, FAINT, bold = true, ls = 0.06f))
                addView(View(this@MainActivity), LinearLayout.LayoutParams(-1, px(10f)))
                addBtn("Conceder acceso", Style.PRIMARY, marginTop = 12f) {
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
                addView(tv("Setup", 11f, FAINT, bold = true, ls = 0.06f))
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
                !storageOk -> "Falta acceso."
                else -> "Termina la preparación."
            }
            col.addView(tv(hint, 12.5f, WARN),
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(14f); gravity = Gravity.CENTER })
        }
        col.addBtn(if (ready) "Crear servidor" else "Preparando…",
            Style.PRIMARY, enabled = ready, marginTop = 16f) {
            wizard = "loader"
            render()
        }
        if (!ready && hasError(readState())) {
            col.addBtn("Ver log", Style.GHOST, height = 42f, marginTop = 4f) { showLogDialog("Log", lastRunLog) }
        }
    }

    private fun setupLoader(col: LinearLayout) {
        col.addHeader("Servidor")
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
            if (!sel && id == "paper") head.addView(tv("Recomendado", 11f, FAINT, bold = true),
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
        col.addHeader("Versión")
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val manual = EditText(this).apply {
            hint = "1.20.1"
            setTextColor(TEXT); setHintTextColor(FAINT); textSize = 14f
            background = rounded(SURFACE, RADIUS_SM, STROKE, 1)
            setPadding(px(12f), 0, px(12f), 0)
            setSingleLine(true)
        }
        list.addView(tv("Cargando…", 13f, MUTED))
        col.addView(list, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(8f) })
        col.addView(manual, LinearLayout.LayoutParams(-1, px(46f)).apply { topMargin = px(10f) })

        fun pick(v: String) {
            wizardVersion = v
            manual.setText(v)
            for (i in 0 until list.childCount) {
                val row = list.getChildAt(i)
                val isSel = row.tag == v
                row.background = rounded(if (isSel) ACCENT_FAINT else CARD, RADIUS_SM, if (isSel) ACCENT else STROKE, if (isSel) 2 else 1)
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
                list.addView(tv("Sin conexión.", color = WARN))
                return@launch
            }
            versions.forEachIndexed { i, v ->
                val rec = i == 0
                val row = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    background = rounded(CARD, RADIUS_SM, STROKE, 1)
                    setPadding(px(12f), 0, px(12f), 0)
                    setOnClickListener { pick(v) }
                }
                row.addView(tv(v, 15f, TEXT, bold = rec, mono = true), LinearLayout.LayoutParams(0, -2, 1f))
                if (rec) row.addView(tv("Reciente", 10f, ACCENT, bold = true), LinearLayout.LayoutParams(-2, -2))
                row.tag = v
                list.addView(row, LinearLayout.LayoutParams(-1, px(48f)).apply { bottomMargin = px(8f) })
            }
        }
        col.addBtn("Continuar", Style.PRIMARY, marginTop = 16f) {
            val typed = manual.text.toString().trim()
            val v = wizardVersion ?: typed
            if (!v.matches(Regex("""1\.\d+(\.\d+)?""")) || !mcAtLeast(v, "1.17")) {
                toast("Versión 1.17+.")
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
        // Tres datos, no cinco: cargador+versión son una identidad, no dos.
        col.addCard {
            addInfo("Servidor", "${prettyLoader(wizardLoader)} $version")
            addInfo("RAM", prettyRam(rmax), ACCENT)
            addInfo("Tipo", if (wizardLoader == "paper") "Plugins" else "Mods")
        }
        if (total < 3072) {
            col.addView(tv("Poca RAM.", 12.5f, WARN),
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(10f) })
        }
        col.addBtn("Instalar", Style.PRIMARY, marginTop = 18f) {
            clearError()
            reinstalling = false
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
        val detail = tv("", 12.5f, MUTED).apply { gravity = Gravity.CENTER }
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
                    reinstalling = false
                    prefs.edit().putString("last_tab", Tab.HOME.id).apply()
                    tab = Tab.HOME
                    render()
                    break
                }
                if (st != null && hasError(st) && sval(st, "last_action") == "error") {
                    done.setTextColor(WARN)
                    done.text = "Error en la instalación"
                    delay(1800)
                    wizard = if (reinstalling) { reinstalling = false; "welcome" } else "summary"
                    tab = Tab.HOME
                    render()
                    break
                }
            }
        }
    }

}
