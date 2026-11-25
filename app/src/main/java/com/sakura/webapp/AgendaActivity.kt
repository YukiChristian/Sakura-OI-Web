package com.sakura.webapp

import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.app.Dialog
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.text.style.ImageSpan
import android.text.style.StyleSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.UUID
import com.sakura.webapp.AgendaEstilo.Estado

/**
 * «Agenda» kept on the phone, for when the PC does not answer. With the PC answering the app opens the web Agenda
 * instead (pedido de Christian, 24-nov-2026: «Web con conexión»; before it was always native, variante B), so
 * the design is changed in one place; this screen does not have to follow every later change of the web.
 * Drawn after the boards «AGE · Agenda 1 de 2» and «2 de 2» of Hanami Mockups:
 *  - Tareas | Calendario (AGE-21), opening on the last one used; a red number on Tareas counts the overdue ones.
 *  - One piece per state everywhere (AGE-13): the bar of its calendar's colour (dotted grey without one), the
 *    task's circle and its label Pendiente / Vencida / Ahora / Hecha, ☆ Importante and «···» on every row.
 *  - Labels are the calendars (AGE-16 to AGE-18): «Todas» is divided by label, «Sin clasificar» last.
 *  - Mi día mixes events and tasks: Vencidas, Hoy, Próximos by day and Completadas (AGE-14, AGE-22, AGE-23).
 *  - Calendario with Día, Semana (three days) and Mes; «···» of an event edits, marks as done or deletes it
 *    (AGE-15), also without a network: the change waits in the queue like the tasks.
 * The capsule by the title says «Al día» or «N por enviar»; an amber dot marks what has not been sent yet.
 */
class AgendaActivity : Activity() {
    companion object {
        private const val EXTRA_TAREA = "com.sakura.webapp.extra.TAREA"
        /** From the widget's «+ Tarea»: opens «Agregar una tarea» at once. */
        const val EXTRA_NUEVA = "com.sakura.webapp.extra.NUEVA"
        /** The phone's own Agenda even with the PC answering (the connection screen's «Abrir mi agenda»). */
        private const val EXTRA_NATIVA = "com.sakura.webapp.extra.NATIVA"
        fun intent(context: Context, tareaId: String? = null, nativa: Boolean = false): Intent =
            Intent(context, AgendaActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .apply { if (tareaId != null) putExtra(EXTRA_TAREA, tareaId); if (nativa) putExtra(EXTRA_NATIVA, true) }

        /**
         * Pedido de Christian (24-nov-2026, «Web con conexión»): with the PC answering, the Agenda is the web one, so its
         * design lives in one place; without it, this screen. The last known state of the PC (the widgets' one) and
         * a network on the phone decide at once; a stale «answers» ends on the connection screen, whose «Abrir mi
         * agenda» comes back here for good.
         */
        fun hayConexion(context: Context): Boolean {
            PcStatus.check(context)
            // only a recent answer opens the web at once; otherwise this screen opens and its first sync decides
            if (!PcStatus.answeredRecently(context)) return false
            val cm = context.getSystemService(ConnectivityManager::class.java) ?: return true
            return if (Build.VERSION.SDK_INT >= 23) cm.activeNetwork != null else true
        }
        private const val DIAS_PROX = 30
        private const val INTERNO_TAREAS = "Tareas"
        private const val INTERNO_AVISOS = "Avisos de tareas"
    }

    private lateinit var ui: SetupUi
    private val es = Locale("es", "MX")
    private lateinit var chip: LinearLayout
    private lateinit var chipIcon: ImageView
    private lateinit var chipText: TextView
    private lateinit var tabs: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var content: LinearLayout
    private val prefs by lazy { getSharedPreferences("agenda_ui", MODE_PRIVATE) }
    private var tab = "tareas"
    private var lista = "mi_dia"
    private var vista = "mes"
    private var completadasAbiertas = false
    private val seccionesCerradas = mutableSetOf<String>()
    private var mes: Calendar = AgendaStore.calendario()
    private var diaElegido = AgendaStore.hoy()
    private var irAHora = true
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private val alCambiar: () -> Unit = { runOnUiThread { if (!isFinishing) render() } }
    // the states change with the time (Ahora → Vencida): redrawn every minute while the screen is open
    private val reloj = Handler(Looper.getMainLooper())
    private val tic = object : Runnable {
        override fun run() { if (!isFinishing) render(); reloj.postDelayed(this, 60_000) }
    }

    // Hanami colours (light / dark), AGE-13
    private val blue get() = if (ui.night) 0xFF8FA9DA.toInt() else 0xFF3E5F99.toInt()
    private val amberBg get() = if (ui.night) 0xFF3A2A0A.toInt() else 0xFFFEF3C7.toInt()
    private val amberInk get() = if (ui.night) 0xFFF5C46B.toInt() else 0xFF78350F.toInt()
    private val amberDot get() = if (ui.night) 0xFFF59E0B.toInt() else 0xFFB45309.toInt()
    private val starOn get() = if (ui.night) AgendaEstilo.AMBAR else 0xFFD99A00.toInt()
    private val green get() = AgendaEstilo.verde(ui.night)
    private val red get() = AgendaEstilo.rojo(ui.night)
    private val line get() = if (ui.night) 0x1FFFFFFF else 0x1F18181B
    private val sep get() = if (ui.night) 0x17FFFFFF else 0x1418181B
    private val page get() = if (ui.night) 0xFF121216.toInt() else 0xFFF6F6F8.toInt()
    private val cardBg get() = if (ui.night) 0xFF1C1C21.toInt() else Color.WHITE
    private val grafito get() = ui.ink
    private val sobreGrafito get() = if (ui.night) 0xFF18181B.toInt() else Color.WHITE
    private val faintCircle get() = AgendaEstilo.gris(ui.night)
    /** The circle of a pending task and of an event: black (light grey in dark), as the web since 24-nov. */
    private val aro get() = if (ui.night) 0xFFE4E4E7.toInt() else 0xFF18181B.toInt()

    /** Everything a drawing needs, read once. */
    private class Datos(
        val tareas: List<AgendaStore.Tarea>,
        val secciones: List<AgendaStore.Seccion>,
        val cals: Map<String, JSONObject>,
        val eventos: List<AgendaStore.Evento>,
    ) {
        val secMap = secciones.associateBy { it.id }
    }

    private fun datos(): Datos {
        val cals = AgendaEstilo.calendarios(this)
        val internos = cals.values.filter { it.optString("name") in setOf(INTERNO_TAREAS, INTERNO_AVISOS) }.map { it.optString("id") }.toSet()
        return Datos(
            AgendaStore.tareas(this),
            AgendaStore.secciones(this),
            cals,
            AgendaStore.eventos(this).filter { !it.esDeTarea && !it.json.optBoolean("is_cancelled") && it.calendarId !in internos }
                .sortedWith(compareBy({ !it.todoElDia }, { it.inicioMs })),
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // the widget's «+ Tarea» stays a quick sheet on the phone: with the PC answering it closes after adding
        val nueva = intent?.getBooleanExtra(EXTRA_NUEVA, false) == true
        if (savedInstanceState == null && nueva && intent?.getBooleanExtra(EXTRA_NATIVA, false) != true && hayConexion(this)) cerrarAlAgregar = true
        else if (savedInstanceState == null && intent?.getBooleanExtra(EXTRA_NATIVA, false) != true && hayConexion(this)) {
            abrirWeb()
            finish()
            @Suppress("DEPRECATION") overridePendingTransition(0, 0)
            return
        }
        abiertaEn = android.os.SystemClock.uptimeMillis()
        AppLock.install(this)
        ui = SetupUi(this)
        tab = prefs.getString("tab", "tareas") ?: "tareas"
        vista = prefs.getString("vista", "mes") ?: "mes"
        WindowCompat.setDecorFitsSystemWindows(window, false)
        ui.styleWindow()
        window.decorView.setBackgroundColor(page)

        val root = FrameLayout(this).apply { setBackgroundColor(page) }
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(column, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // header: back · Agenda · capsule · web
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(6), dp(12), dp(6))
        }
        header.addView(iconButton(R.drawable.ic_ui_chevron_left, "Volver", ui.ink) { finish() })
        header.addView(ui.text("Agenda", 28f, ui.ink, bold = true).apply { letterSpacing = -0.01f },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(2) })
        chipIcon = ImageView(this)
        chipText = ui.text("", 12.5f, ui.muted, semibold = true)
        chip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), 0, dp(11), 0)
            isClickable = true
            isFocusable = true
            addView(chipIcon, LinearLayout.LayoutParams(dp(15), dp(15)).apply { marginEnd = dp(6) })
            addView(chipText)
            setOnClickListener { alTocarCapsula() }
        }
        header.addView(chip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(32)).apply { marginEnd = dp(8) })
        // «+ Nuevo», as in the web Agenda (here it adds a task: events are created on the web)
        header.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(16), 0, dp(18), 0)
            background = round(grafito, 999)
            isClickable = true
            isFocusable = true
            contentDescription = "Nuevo: agregar una tarea"
            addView(ImageView(this@AgendaActivity).apply { setImageResource(R.drawable.ic_ag_plus); imageTintList = ColorStateList.valueOf(sobreGrafito) },
                LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginEnd = dp(8) })
            addView(ui.text("Nuevo", 15.5f, sobreGrafito, semibold = true).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO })
            setOnClickListener { hojaAgregar() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)))
        column.addView(header)

        tabs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(3), dp(3), dp(3), dp(3))
            background = round(if (ui.night) 0x0DFFFFFF else 0x0D18181B, 999, line)
        }
        column.addView(tabs, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(42)).apply {
            marginStart = dp(14); marginEnd = dp(14); bottomMargin = dp(12)
        })

        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, 0, dp(32)) }
        scroll = ScrollView(this).apply { addView(content); isFillViewport = true }
        column.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))


        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            column.setPadding(bars.left, bars.top, bars.right, 0)
            content.setPadding(0, 0, 0, dp(32) + bars.bottom)
            insets
        }
        setContentView(root)
        render()
        intent?.getStringExtra(EXTRA_TAREA)?.let { id -> root.post { AgendaStore.tarea(this, id)?.let { detalle(it) } } }
        if (intent?.getBooleanExtra(EXTRA_NUEVA, false) == true) root.post { cambiarPestana("tareas"); hojaAgregar() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getStringExtra(EXTRA_TAREA)?.let { id -> AgendaStore.tarea(this, id)?.let { detalle(it) } }
        if (intent.getBooleanExtra(EXTRA_NUEVA, false)) { cambiarPestana("tareas"); hojaAgregar() }
    }

    /** Opened only to add a task (widget «+ Tarea») with the PC answering: closes when the sheet does. */
    private var cerrarAlAgregar = false

    /** The server answered the last sync of this screen: the web Agenda can open again. */
    private var conectado = false
    private val alSincronizar: (Boolean) -> Unit = { ok ->
        // a «false» can also mean «another sync was already running»: only a failed one hides the notice
        val ahora = ok || (conectado && AgendaSync.estado != AgendaSync.Estado.SIN_CONEXION && AgendaSync.estado != AgendaSync.Estado.SIN_SESION)
        if (ok && pasarALaWeb()) Unit
        else if (ahora != conectado) { conectado = ahora; if (!isFinishing) render() }
    }

    // Opened on a stale «no answer» of the PC (the widgets' state is checked every two minutes): when the first
    // sync answers within a few seconds and nothing was touched yet, the web Agenda takes over by itself.
    private var abiertaEn = 0L
    private var tocada = false

    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        tocada = true
        return super.dispatchTouchEvent(ev)
    }

    private fun pasarALaWeb(): Boolean {
        if (tocada || cerrarAlAgregar || isFinishing || android.os.SystemClock.uptimeMillis() - abiertaEn > 8_000) return false
        if (intent?.getBooleanExtra(EXTRA_NATIVA, false) == true || intent?.getStringExtra(EXTRA_TAREA) != null) return false
        abrirWeb()
        finish()
        @Suppress("DEPRECATION") overridePendingTransition(0, 0)
        return true
    }

    override fun onResume() {
        super.onResume()
        AgendaStore.listen(alCambiar)
        AgendaSync.programarPeriodica(this)
        AgendaSync.ahora(this, alSincronizar)
        if (Build.VERSION.SDK_INT >= 24) {
            val cm = getSystemService(ConnectivityManager::class.java)
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) { runOnUiThread { AgendaSync.ahora(applicationContext, alSincronizar) } }
            }
            runCatching { cm?.registerDefaultNetworkCallback(cb) }.onSuccess { networkCallback = cb }
        }
        render()
        reloj.removeCallbacks(tic)
        reloj.postDelayed(tic, 60_000)
    }

    override fun onPause() {
        AgendaStore.unlisten(alCambiar)
        reloj.removeCallbacks(tic)
        networkCallback?.let { cb -> runCatching { getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(cb) } }
        networkCallback = null
        super.onPause()
    }

    private fun abrirWeb() {
        // CLEAR_TOP: back to the app's own page (never a second copy of it), this screen closes
        startActivity(Intent(this, MainActivity::class.java).setAction(IncomingIntents.ACTION_CALENDAR_WEB)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
    }

    // ── Drawing ──

    private fun render() {
        val d = datos()
        pintarCapsula()
        pintarPestanas(d)
        val y = scroll.scrollY
        content.removeAllViews()
        var ancla: View? = null
        if (conectado) content.addView(avisoConexion())
        if (tab == "tareas") pintarTareas(d) else ancla = pintarCalendario(d)
        if (ancla != null && irAHora) {
            irAHora = false
            val a = ancla
            scroll.post { scroll.scrollTo(0, maxOf(0, offsetEn(a) - dp(8))) }
        } else scroll.post { scroll.scrollTo(0, y) }
    }

    /** «Hay conexión con tu PC · Abrir la Agenda web»: the connection came back while this screen was open. */
    private fun avisoConexion(): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(14), dp(10), dp(8), dp(10))
        background = round(conAlfa(green, if (ui.night) 0.16f else 0.10f), 14)
        addView(ImageView(this@AgendaActivity).apply { setImageResource(R.drawable.ic_ui_check); imageTintList = ColorStateList.valueOf(green) },
            LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginEnd = dp(10) })
        addView(ui.text("Hay conexión con tu PC", 14f, ui.ink, semibold = true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(pastilla("Abrir la Agenda web", true) { abrirWeb(); finish() }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            marginStart = dp(14); marginEnd = dp(14); bottomMargin = dp(12)
        }
    }

    /** [v]'s top inside [content]. */
    private fun offsetEn(v: View): Int {
        var y = 0
        var x: View? = v
        while (x != null && x !== content) { y += x.top; x = x.parent as? View }
        return y
    }

    private fun pintarCapsula() {
        val pendientes = AgendaStore.pendientes(this)
        val (texto, icono, fondo, tinta, iconoColor) = when (AgendaSync.estadoActual(this)) {
            AgendaSync.Estado.SINCRONIZANDO -> Quint(if (pendientes > 0) "Enviando…" else "Al día", R.drawable.ic_ui_refresh, ui.tile, ui.muted, ui.muted)
            AgendaSync.Estado.PENDIENTES -> Quint("$pendientes por enviar", R.drawable.ic_ag_cloud_off, amberBg, amberInk, amberInk)
            AgendaSync.Estado.SIN_CONEXION -> Quint("Sin conexión", R.drawable.ic_ag_cloud_off, ui.tile, ui.muted, ui.muted)
            AgendaSync.Estado.SIN_SESION -> Quint("Inicia sesión", R.drawable.ic_ui_alert, amberBg, amberInk, amberInk)
            AgendaSync.Estado.AL_DIA -> Quint("Al día", R.drawable.ic_ui_check, ui.tile, ui.muted, green)
        }
        chipText.text = texto
        chipText.setTextColor(tinta)
        chipText.typeface = Typeface.create("sans-serif-medium", if (fondo == amberBg) Typeface.BOLD else Typeface.NORMAL)
        chipIcon.setImageResource(icono)
        chipIcon.imageTintList = ColorStateList.valueOf(iconoColor)
        chip.background = round(fondo, 999)
        chip.contentDescription = if (texto == "Al día") "Agenda al día. Toca para sincronizar." else "$texto. Toca para sincronizar."
        chip.visibility = if (texto == "Al día") View.GONE else View.VISIBLE
    }

    private data class Quint(val a: String, val b: Int, val c: Int, val d: Int, val e: Int)

    private fun alTocarCapsula() {
        if (AgendaSync.estadoActual(this) == AgendaSync.Estado.SIN_SESION) {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            return
        }
        AgendaSync.ahora(this)
    }

    private fun cambiarPestana(id: String) {
        if (tab != id) { tab = id; prefs.edit().putString("tab", id).apply(); irAHora = true }
        scroll.scrollTo(0, 0)
        render()
    }

    /** AGE-21: Tareas | Calendario (without the red number of AGE-23: Christian took it out of the web on 24-nov). */
    private fun pintarPestanas(d: Datos) {
        tabs.removeAllViews()
        for ((id, nombre) in listOf("tareas" to "Tareas", "calendario" to "Calendario")) {
            val on = tab == id
            val pestana = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                if (on) { background = round(cardBg, 999); elevation = dp(1).toFloat() }
                isClickable = true
                isFocusable = true
                contentDescription = nombre + if (on) ", seleccionada" else ""
                setOnClickListener { cambiarPestana(id) }
                addView(ui.text(nombre, 14f, if (on) ui.ink else ui.muted, semibold = on).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO })
            }
            tabs.addView(pestana, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        }
    }

    // ── Tareas ──

    private fun enLista(t: AgendaStore.Tarea, l: String): Boolean = when (l) {
        "mi_dia" -> t.miDia
        "importante" -> t.importante
        "planeado" -> t.fecha != null
        else -> true
    }

    private val ordenar = compareBy<AgendaStore.Tarea>({ !it.importante }, { it.orden })

    private fun pintarTareas(d: Datos) {
        val (icono, color, titulo) = when (lista) {
            "importante" -> Triple(R.drawable.ic_ag_star, starOn, "Importante")
            "planeado" -> Triple(R.drawable.ic_ui_calendar, blue, "Planeado")
            "todas" -> Triple(R.drawable.ic_ag_list, ui.muted, "Todas")
            else -> Triple(R.drawable.ic_ag_sun, blue, "Mi día")
        }
        content.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), 0, dp(16), 0)
            addView(ImageView(this@AgendaActivity).apply { setImageResource(icono); imageTintList = ColorStateList.valueOf(color) },
                LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginEnd = dp(8) })
            addView(ui.text(titulo, 22f, ui.ink, semibold = true))
        })
        if (lista == "mi_dia") content.addView(ui.text(fechaLarga(AgendaStore.calendario()), 13f, ui.muted).apply {
            setPadding(dp(46), dp(2), dp(16), 0)
        })

        // list chips with counts
        val fila = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(14), dp(12), dp(14), dp(4)) }
        for ((id, nombre) in listOf("mi_dia" to "Mi día", "importante" to "Importante", "planeado" to "Planeado", "todas" to "Todas")) {
            val n = d.tareas.count { !it.completada && enLista(it, id) }
            fila.addView(pastilla("$nombre  $n", lista == id) { lista = id; completadasAbiertas = false; render() },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)).apply { marginEnd = dp(8) })
        }
        content.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(fila) })

        when (lista) {
            "mi_dia" -> pintarMiDia(d)
            "todas" -> pintarTodas(d)
            else -> pintarImportanteOPlaneado(d)
        }
    }

    private fun vencidasDe(ts: List<AgendaStore.Tarea>) =
        ts.filter { AgendaEstilo.estado(it) == Estado.VENCIDA }.sortedBy { AgendaEstilo.vence(it) ?: 0L }

    /** AGE-14 / AGE-23: Vencidas (the oldest first), Hoy (events and tasks by time), Próximos by day, Completadas. */
    private fun pintarMiDia(d: Datos) {
        val hoy = AgendaStore.hoy()
        val vencidas = vencidasDe(d.tareas)
        val idsV = vencidas.map { it.id }.toSet()
        if (vencidas.isNotEmpty()) {
            content.addView(grupo("Vencidas", vencidas.size, red))
            vencidas.forEach { content.addView(filaTarea(it, d)) }
        }
        val deHoy = mutableListOf<Pair<String, View>>()
        d.eventos.filter { fechaEv(it) == hoy }.forEach { deHoy.add((if (it.todoElDia) "" else hhmm(it.inicioMs)) to filaEvento(it, d)) }
        d.tareas.filter { !it.completada && enLista(it, "mi_dia") && it.id !in idsV }.sortedWith(ordenar)
            .forEach { deHoy.add((it.hora ?: "99") to filaTarea(it, d)) }
        if (deHoy.isNotEmpty()) {
            content.addView(grupo("Hoy", deHoy.size, ui.ink))
            deHoy.sortedBy { it.first }.forEach { content.addView(it.second) }
        } else content.addView(vacio("Nada para hoy. Toca «+ Nuevo» para agregar una tarea."))

        // AGE-10: the next days, by day; within the day, first what has a time
        val lim = sumarDias(hoy, DIAS_PROX)
        val prox = mutableListOf<Triple<String, String, View>>()
        d.tareas.filter { !it.completada && it.fecha != null && it.fecha!! > hoy && it.fecha!! <= lim }
            .forEach { prox.add(Triple(it.fecha!!, it.hora ?: "99", filaTarea(it, d))) }
        d.eventos.filter { val f = fechaEv(it); f > hoy && f <= lim }
            .forEach { prox.add(Triple(fechaEv(it), if (it.todoElDia) "" else hhmm(it.inicioMs), filaEvento(it, d))) }
        if (prox.isNotEmpty()) {
            content.addView(grupo("Próximos", prox.size, ui.ink))
            var dia = ""
            for ((f, _, v) in prox.sortedWith(compareBy({ it.first }, { if (it.second == "99") 1 else 0 }, { it.second }))) {
                if (f != dia) {
                    dia = f
                    content.addView(ui.text(nombreDia(f), 12.5f, ui.muted, semibold = true).apply { setPadding(dp(18), dp(8), dp(16), dp(6)) })
                }
                content.addView(v)
            }
        }
        val hechas = d.tareas.filter { it.completada && (enLista(it, "mi_dia") || fechaDeMs(it.json.optLong("completada_en")) == hoy) }
        pintarCompletadas(hechas, d)
    }

    /** Importante and Planeado: the overdue first; Importante by label (and its starred events), Planeado by day. */
    private fun pintarImportanteOPlaneado(d: Datos) {
        val elegidas = d.tareas.filter { enLista(it, lista) }
        val abiertas = elegidas.filter { !it.completada }
        val vencidas = vencidasDe(abiertas)
        val idsV = vencidas.map { it.id }.toSet()
        val resto = abiertas.filter { it.id !in idsV }
        if (vencidas.isNotEmpty()) {
            content.addView(grupo("Vencidas", vencidas.size, red))
            vencidas.forEach { content.addView(filaTarea(it, d)) }
        }
        val hoy = AgendaStore.hoy()
        val evImp = if (lista == "importante") d.eventos.filter { val f = fechaEv(it); f >= hoy && f <= sumarDias(hoy, DIAS_PROX) && AgendaStore.eventoImportante(this, it.id) } else emptyList()
        evImp.forEach { content.addView(filaEvento(it, d)) }
        if (lista == "planeado") {
            val manana = sumarDias(hoy, 1)
            val grupos = resto.sortedWith(compareBy({ it.fecha }, { it.hora ?: "99" })).groupBy {
                when (it.fecha!!) { hoy -> "Hoy"; manana -> "Mañana"; else -> "Más adelante" }
            }
            for (g in listOf("Hoy", "Mañana", "Más adelante")) {
                val ts = grupos[g] ?: continue
                content.addView(grupo(g, ts.size, ui.ink))
                ts.forEach { content.addView(filaTarea(it, d)) }
            }
        } else porEtiqueta(resto.sortedWith(ordenar), d)
        if (abiertas.isEmpty() && evImp.isEmpty()) content.addView(vacio(
            if (lista == "importante") "Sin tareas importantes. Toca la estrella de una tarea." else "Sin tareas con fecha."
        ))
        pintarCompletadas(elegidas.filter { it.completada }, d)
    }

    /** AGE-18: divided by label with its colour; the ones without go last in «Sin clasificar»; no titles if none has one. */
    private fun porEtiqueta(ts: List<AgendaStore.Tarea>, d: Datos) {
        val secs = d.secciones.filter { s -> ts.any { it.seccionId == s.id } }
        if (secs.isEmpty()) { ts.forEach { content.addView(filaTarea(it, d)) }; return }
        for (s in secs) {
            val suyas = ts.filter { it.seccionId == s.id }
            content.addView(grupoEtiqueta(s, suyas.size, d, null))
            suyas.forEach { content.addView(filaTarea(it, d)) }
        }
        val sin = ts.filter { it.seccionId == null || d.secMap[it.seccionId] == null }
        if (sin.isNotEmpty()) {
            content.addView(grupoEtiqueta(null, sin.size, d, null))
            sin.forEach { content.addView(filaTarea(it, d)) }
        }
    }

    private fun pintarTodas(d: Datos) {
        val abiertas = d.tareas.filter { !it.completada }.sortedWith(ordenar)
        val sinSec = abiertas.filter { it.seccionId == null || d.secMap[it.seccionId] == null }
        if (d.secciones.isEmpty()) {
            if (abiertas.isEmpty()) content.addView(vacio("Todavía no hay tareas. Toca «+ Nuevo» para agregar la primera."))
            sinSec.forEach { content.addView(filaTarea(it, d)) }
        }
        for (s in d.secciones) {
            val suyas = abiertas.filter { it.seccionId == s.id }
            val cerrada = s.id in seccionesCerradas
            content.addView(grupoEtiqueta(s, suyas.size, d, cerrada) { if (!seccionesCerradas.add(s.id)) seccionesCerradas.remove(s.id); render() })
            if (!cerrada) {
                if (suyas.isEmpty()) content.addView(ui.text("Sin tareas pendientes.", 13f, ui.muted).apply { setPadding(dp(18), 0, dp(16), dp(8)) })
                suyas.forEach { content.addView(filaTarea(it, d)) }
            }
        }
        if (sinSec.isNotEmpty() && d.secciones.isNotEmpty()) {
            content.addView(grupoEtiqueta(null, sinSec.size, d, null))
            sinSec.forEach { content.addView(filaTarea(it, d)) }
        }
        content.addView(ui.text("+  Nueva etiqueta", 14.5f, blue, semibold = true).apply {
            setPadding(dp(18), dp(12), dp(16), dp(12))
            isClickable = true
            isFocusable = true
            setOnClickListener { nuevaEtiqueta { } }
        })
        pintarCompletadas(d.tareas.filter { it.completada }, d)
    }

    private fun pintarCompletadas(hechas: List<AgendaStore.Tarea>, d: Datos) {
        if (hechas.isEmpty()) return
        content.addView(etiqueta((if (completadasAbiertas) "▾  " else "▸  ") + "Completadas · ${hechas.size}", ui.ink).apply {
            isClickable = true
            contentDescription = "Completadas, ${hechas.size}, " + if (completadasAbiertas) "desplegadas" else "plegadas"
            setOnClickListener { completadasAbiertas = !completadasAbiertas; render() }
        })
        if (completadasAbiertas) hechas.sortedByDescending { it.json.optLong("completada_en") }.take(50).forEach { content.addView(filaTarea(it, d)) }
    }

    // ── The piece (AGE-13) ──

    /** The card every row shares: 4 dp bar, then [cuerpo]; the important one with an amber wash from the right. */
    private fun pieza(barra: Int?, importante: Boolean): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(58)
        setPadding(dp(12), dp(7), dp(2), dp(7))
        background = fondoPieza(importante)
        isClickable = true
        isFocusable = true
        addView(View(this@AgendaActivity).apply {
            background = if (barra != null) round(barra, 2) else BarraPunteada(faintCircle, dp(3f).toFloat())
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(4), ViewGroup.LayoutParams.MATCH_PARENT))
    }

    private fun fondoPieza(importante: Boolean): Drawable {
        val base = round(cardBg, 12, line)
        if (!importante) return base
        val lavado = GradientDrawable(GradientDrawable.Orientation.RIGHT_LEFT, intArrayOf(conAlfa(AgendaEstilo.AMBAR, if (ui.night) 0.06f else 0.10f), Color.TRANSPARENT, Color.TRANSPARENT, Color.TRANSPARENT)).apply {
            cornerRadius = dp(12).toFloat()
        }
        return LayerDrawable(arrayOf<Drawable>(base, lavado))
    }

    private fun filaTarea(t: AgendaStore.Tarea, d: Datos): View {
        val st = AgendaEstilo.estado(t)
        val cal = AgendaEstilo.calDeTarea(t, d.secMap, d.cals)
        val color = cal?.color?.de(ui.night)
        val row = pieza(color, t.importante).apply {
            setOnClickListener { detalle(t) }
            setOnLongClickListener { menuTarea(t); true }
        }
        row.addView(circulo(t, st, color), LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginStart = dp(1) })
        val textos = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(3), 0, dp(4), 0) }
        val pendiente = AgendaStore.pendiente(this, t.id)
        textos.addView(tituloFila(t.titulo, st == Estado.HECHA, pendiente))
        val (metaVista, metaTexto) = metaTarea(t, st, cal)
        textos.addView(metaVista)
        row.addView(textos, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(estrella(t.importante, t.titulo) {
            AgendaStore.cambiarTarea(this, t.id, JSONObject().put("importante", !t.importante)); despuesDeCambiar()
        })
        row.addView(masOpciones(t.titulo) { menuTarea(t) })
        row.contentDescription = buildString {
            append(t.titulo).append(", ").append(metaTexto)
            if (t.importante) append(", importante")
            if (pendiente) append(", por enviar")
        }
        return row.wrap()
    }

    private fun filaEvento(e: AgendaStore.Evento, d: Datos): View {
        val cal = AgendaEstilo.calDeEvento(e, d.cals)
        val color = cal.color.de(ui.night)
        val fecha = fechaEv(e)
        val imp = AgendaStore.eventoImportante(this, e.id)
        val hecho = AgendaStore.eventoHecho(this, e.id, fecha)
        val ahora = AgendaEstilo.eventoAhora(e)
        val pasado = AgendaEstilo.eventoPasado(e)
        val row = pieza(color, imp).apply {
            setOnClickListener { menuEvento(e) }
            setOnLongClickListener { menuEvento(e); true }
            if (pasado && !ahora) alpha = 0.55f
        }
        row.addView(circuloEvento(e, fecha, hecho), LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginStart = dp(1) })
        val textos = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(3), 0, dp(4), 0) }
        textos.addView(tituloFila(e.titulo, hecho, AgendaStore.pendiente(this, e.id)))
        val sb = SpannableStringBuilder()
        val hora = when {
            e.todoElDia -> "Todo el día"
            ahora && e.finMs > e.inicioMs -> hhmm(e.inicioMs) + "–" + hhmm(e.finMs)
            else -> hhmm(e.inicioMs)
        }
        val dia = if (fecha == AgendaStore.hoy() || ahora) "" else fechaRel(fecha) + " "
        sb.conColor(dia + hora, color, bold = true)
        sb.sep(); sb.append("Evento")
        if (cal.nombre.isNotEmpty()) { sb.sep(); sb.conColor("● ", color); sb.append(cal.nombre) }
        textos.addView(lineaMeta(if (ahora && !hecho) Estado.AHORA else null, sb))
        row.addView(textos, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(estrella(imp, e.titulo) { AgendaStore.marcarEventoImportante(this, e.id, !imp) })
        row.addView(masOpciones(e.titulo) { menuEvento(e) })
        row.contentDescription = buildString {
            append(e.titulo).append(", ")
            if (ahora) append("ahora, ")
            append(dia).append(hora).append(", evento")
            if (cal.nombre.isNotEmpty()) append(", ").append(cal.nombre)
            if (hecho) append(", terminado")
            if (imp) append(", importante")
        }
        return row.wrap()
    }

    private fun tituloFila(texto: String, tachado: Boolean, pendiente: Boolean): View {
        val titulo = ui.text(texto, 15.5f, if (tachado) faintCircle else ui.ink).apply {
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            if (tachado) paintFlags = paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
        }
        if (!pendiente) return titulo
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(titulo, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { weight = 0f; width = ViewGroup.LayoutParams.WRAP_CONTENT })
            addView(View(this@AgendaActivity).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(amberDot) }
                contentDescription = "Por enviar"
            }, LinearLayout.LayoutParams(dp(8), dp(8)).apply { marginStart = dp(7) })
        }
    }

    /** «Pendiente Jue 26 · 17:00 · ● Personal»: the state's label, then when, steps, reminder, repeat and calendar. */
    private fun metaTarea(t: AgendaStore.Tarea, st: Estado, cal: AgendaEstilo.Cal?): Pair<View, String> {
        val sb = SpannableStringBuilder()
        val cuando = if (st == Estado.HECHA) {
            fechaRel(fechaDeMs(t.json.optLong("completada_en").takeIf { it > 0 } ?: System.currentTimeMillis())).lowercase(es)
        } else t.fecha?.let { fechaRel(it) + (t.hora?.let { h -> " $h" } ?: "") }
        if (cuando != null) {
            if (st == Estado.VENCIDA) sb.conColor(cuando, red, bold = true)
            else { if (st != Estado.HECHA) sb.icono(R.drawable.ic_ui_calendar, ui.muted); sb.append(cuando) }
        }
        val pasos = t.pasos
        if (pasos.isNotEmpty()) { sb.sep(); sb.append("${pasos.count { it.hecho }}/${pasos.size} subtareas") }
        t.recordar?.takeIf { st != Estado.HECHA }?.let { sb.sep(); sb.icono(R.drawable.ic_ag_bell, blue); sb.conColor(recordarCorto(it), blue) }
        t.repetir?.let { sb.sep(); sb.icono(R.drawable.ic_ag_repeat, ui.muted); sb.append(textoRepetir(it)) }
        sb.sep()
        if (cal != null) { sb.conColor("● ", cal.color.de(ui.night)); sb.append(cal.nombre) } else sb.append("Sin calendario")
        if (t.notas.isNotBlank()) { sb.sep(); sb.icono(R.drawable.ic_ag_note, ui.muted); sb.append("Nota") }
        val estadoTxt = when (st) { Estado.PENDIENTE -> "Pendiente"; Estado.VENCIDA -> "Vencida"; Estado.AHORA -> "Ahora"; Estado.HECHA -> "Hecha" }
        return lineaMeta(st, sb) to "$estadoTxt, ${sb.toString().replace("●", "").trim()}"
    }

    private fun lineaMeta(st: Estado?, sb: CharSequence): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(3), 0, 0)
        if (st != null) addView(pastillaEstado(st), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(19)).apply { marginEnd = dp(6) })
        addView(ui.text("", 12f, ui.muted).apply {
            text = sb
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
    }

    /** The soft label of each state: grey Pendiente, red Vencida with a clock, amber Ahora with a beating dot, green Hecha. */
    private fun pastillaEstado(st: Estado): View {
        val n = ui.night
        val (fondo, tinta, icono, texto) = when (st) {
            Estado.PENDIENTE -> Quad(if (n) 0x14FFFFFF else 0x0F18181B, ui.muted, R.drawable.ic_ag_circle, "Pendiente")
            Estado.VENCIDA -> Quad(conAlfa(red, if (n) 0.18f else 0.10f), AgendaEstilo.rojoTexto(n), R.drawable.ic_ag_clock, "Vencida")
            Estado.AHORA -> Quad(conAlfa(AgendaEstilo.AMBAR, if (n) 0.16f else 0.24f), AgendaEstilo.ambarTexto(n), 0, "Ahora")
            Estado.HECHA -> Quad(conAlfa(green, if (n) 0.18f else 0.12f), AgendaEstilo.verdeTexto(n), R.drawable.ic_ag_check_bold, "Hecha")
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(7), 0, dp(8), 0)
            background = round(fondo, 999)
            if (icono != 0) addView(ImageView(this@AgendaActivity).apply {
                setImageResource(icono); imageTintList = ColorStateList.valueOf(tinta)
            }, LinearLayout.LayoutParams(dp(11), dp(11)).apply { marginEnd = dp(4) })
            else addView(View(this@AgendaActivity).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(AgendaEstilo.ambarPunto(n)) }
                startAnimation(AlphaAnimation(1f, 0.25f).apply { duration = 900; repeatMode = Animation.REVERSE; repeatCount = Animation.INFINITE })
            }, LinearLayout.LayoutParams(dp(6), dp(6)).apply { marginEnd = dp(5) })
            addView(ui.text(texto, 11.5f, tinta, bold = true))
        }
    }

    private data class Quad(val a: Int, val b: Int, val c: Int, val d: String)

    private fun View.wrap(): View = FrameLayout(this@AgendaActivity).apply {
        setPadding(dp(14), 0, dp(14), dp(6))
        addView(this@wrap)
    }

    /** The task's circle: empty and black, red when overdue, green with ✓ when done. */
    private fun circulo(t: AgendaStore.Tarea, st: Estado, color: Int?): View = FrameLayout(this).apply {
        isClickable = true
        isFocusable = true
        contentDescription = if (st == Estado.HECHA) "Marcar como pendiente: ${t.titulo}" else "Marcar como hecha: ${t.titulo}"
        addView(View(this@AgendaActivity).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                when (st) {
                    Estado.HECHA -> setColor(green)
                    Estado.VENCIDA -> setStroke(dp(2), red)
                    else -> setStroke(dp(2), aro)
                }
            }
        }, FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
        if (st == Estado.HECHA) addView(ImageView(this@AgendaActivity).apply {
            setImageResource(R.drawable.ic_ag_check_bold)
            imageTintList = ColorStateList.valueOf(Color.WHITE)
        }, FrameLayout.LayoutParams(dp(13), dp(13), Gravity.CENTER))
        setOnClickListener { AgendaStore.completar(this@AgendaActivity, t.id, st != Estado.HECHA); despuesDeCambiar() }
    }

    private fun circuloEvento(e: AgendaStore.Evento, fecha: String, hecho: Boolean): View = FrameLayout(this).apply {
        isClickable = true
        isFocusable = true
        contentDescription = if (hecho) "Marcar como pendiente: ${e.titulo}" else "Marcar como terminado: ${e.titulo}"
        addView(View(this@AgendaActivity).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; if (hecho) setColor(green) else setStroke(dp(2), aro) }
        }, FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
        if (hecho) addView(ImageView(this@AgendaActivity).apply {
            setImageResource(R.drawable.ic_ag_check_bold); imageTintList = ColorStateList.valueOf(Color.WHITE)
        }, FrameLayout.LayoutParams(dp(13), dp(13), Gravity.CENTER))
        setOnClickListener { AgendaStore.marcarEventoHecho(this@AgendaActivity, e.id, fecha, !hecho) }
    }

    private fun estrella(on: Boolean, titulo: String, accion: () -> Unit): View = FrameLayout(this).apply {
        isClickable = true
        isFocusable = true
        contentDescription = "Importante: $titulo, " + if (on) "activado" else "desactivado"
        addView(ImageView(this@AgendaActivity).apply {
            setImageResource(if (on) R.drawable.ic_ag_star_fill else R.drawable.ic_ag_star)
            imageTintList = ColorStateList.valueOf(if (on) starOn else faintCircle)
        }, FrameLayout.LayoutParams(dp(19), dp(19), Gravity.CENTER))
        setOnClickListener { accion() }
        layoutParams = LinearLayout.LayoutParams(dp(36), dp(44))
    }

    private fun masOpciones(titulo: String, accion: () -> Unit): View = FrameLayout(this).apply {
        isClickable = true
        isFocusable = true
        contentDescription = "Más opciones: $titulo"
        addView(ImageView(this@AgendaActivity).apply {
            setImageResource(R.drawable.ic_ag_dots); imageTintList = ColorStateList.valueOf(faintCircle)
        }, FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER))
        setOnClickListener { accion() }
        layoutParams = LinearLayout.LayoutParams(dp(34), dp(44))
    }

    private fun despuesDeCambiar() {
        AgendaSync.pronto(this)
        AgendaAlarms.programar(this)
        QuickVoice.refreshAgenda(this)
    }

    // ── Calendario (AGE-01 mes, AGE-02 semana de tres días, AGE-03 día) ──

    /** Draws the calendar; returns the view to scroll to the first time (the hours of Día and Semana). */
    private fun pintarCalendario(d: Datos): View? {
        val hoy = AgendaStore.hoy()
        val cab = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), 0, dp(14), dp(6))
        }
        val (antes, despues) = when (vista) { "dia" -> "Día anterior" to "Día siguiente"; "semana" -> "Días anteriores" to "Días siguientes"; else -> "Mes anterior" to "Mes siguiente" }
        cab.addView(ui.text(tituloCalendario(), 19f, ui.ink, bold = true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(10) })
        cab.addView(iconButton(R.drawable.ic_ui_chevron_left, antes, ui.ink) { mover(-1) }, LinearLayout.LayoutParams(dp(52), dp(48)))
        cab.addView(iconButton(R.drawable.ic_ui_chevron_right, despues, ui.ink) { mover(1) }, LinearLayout.LayoutParams(dp(52), dp(48)).apply { marginStart = dp(36) })
        content.addView(cab)

        // Día · Semana · Mes
        val seg = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(3), dp(3), dp(3), dp(3))
            background = round(if (ui.night) 0x0DFFFFFF else 0x0D18181B, 999, line)
        }
        for ((id, nombre) in listOf("dia" to "Día", "semana" to "Semana", "mes" to "Mes")) {
            val on = vista == id
            seg.addView(ui.text(nombre, 13.5f, if (on) ui.ink else ui.muted, semibold = on).apply {
                gravity = Gravity.CENTER
                if (on) { background = round(cardBg, 999); elevation = dp(1).toFloat() }
                isClickable = true
                isFocusable = true
                contentDescription = "Vista $nombre" + if (on) ", seleccionada" else ""
                setOnClickListener {
                    vista = id; prefs.edit().putString("vista", id).apply(); irAHora = true
                    if (id == "mes") mes = AgendaStore.calendarioDe(diaElegido) ?: AgendaStore.calendario()
                    scroll.scrollTo(0, 0)
                    render()
                }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        }
        content.addView(seg, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(36)).apply {
            marginStart = dp(14); marginEnd = dp(14); bottomMargin = dp(10)
        })

        return when (vista) {
            "dia" -> rejillaHoras(d, listOf(diaElegido))
            "semana" -> rejillaHoras(d, listOf(-1, 0, 1).map { sumarDias(diaElegido, it) })
            else -> { pintarMes(d); null }
        }
    }

    private fun mover(paso: Int) {
        when (vista) {
            "dia" -> diaElegido = sumarDias(diaElegido, paso)
            "semana" -> diaElegido = sumarDias(diaElegido, paso * 3)
            else -> {
                mes.add(Calendar.MONTH, paso)
                // the chosen day follows the month: the same day number, clipped to the month
                val c = (mes.clone() as Calendar)
                val dia = (AgendaStore.calendarioDe(diaElegido)?.get(Calendar.DAY_OF_MONTH) ?: 1).coerceAtMost(c.getActualMaximum(Calendar.DAY_OF_MONTH))
                c.set(Calendar.DAY_OF_MONTH, dia)
                diaElegido = AgendaStore.fechaDe(c)
            }
        }
        irAHora = true
        render()
    }

    private fun tituloCalendario(): String = when (vista) {
        "dia" -> AgendaStore.calendarioDe(diaElegido)?.let { SimpleDateFormat("EEEE d 'de' MMMM", es).apply { timeZone = AgendaStore.TZ }.format(it.time).replaceFirstChar { c -> c.uppercase() } } ?: diaElegido
        "semana" -> {
            val a = AgendaStore.calendarioDe(sumarDias(diaElegido, -1))!!
            val b = AgendaStore.calendarioDe(sumarDias(diaElegido, 1))!!
            val f = SimpleDateFormat("d MMM", es).apply { timeZone = AgendaStore.TZ }
            if (a.get(Calendar.MONTH) == b.get(Calendar.MONTH)) "${a.get(Calendar.DAY_OF_MONTH)} – ${f.format(b.time).replace(".", "")}"
            else "${f.format(a.time).replace(".", "")} – ${f.format(b.time).replace(".", "")}"
        }
        else -> SimpleDateFormat("LLLL yyyy", es).apply { timeZone = AgendaStore.TZ }.format(mes.time).replaceFirstChar { it.uppercase() }
    }

    /** AGE-01 / AGE-14: the month (Sunday first) in a card, dots per day, and the chosen day's list below. */
    private fun pintarMes(d: Datos) {
        val hoy = AgendaStore.hoy()
        // dots: overdue (red), events (filled, its calendar) and pending tasks (ring, its calendar); three at most
        val puntos = HashMap<String, MutableList<Pair<Int, Boolean>>>()
        for (t in d.tareas) {
            val f = t.fecha ?: continue
            val st = AgendaEstilo.estado(t)
            if (st == Estado.HECHA) continue
            val c = if (st == Estado.VENCIDA) red to true else (AgendaEstilo.calDeTarea(t, d.secMap, d.cals)?.color?.de(ui.night) ?: faintCircle) to true
            puntos.getOrPut(f) { mutableListOf() }.let { if (st == Estado.VENCIDA) it.add(0, c) else it.add(c) }
        }
        for (e in d.eventos) puntos.getOrPut(fechaEv(e)) { mutableListOf() }.add(AgendaEstilo.calDeEvento(e, d.cals).color.de(ui.night) to true)

        val tarjeta = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(8), dp(6), dp(8))
            background = round(cardBg, 18, line)
        }
        val semana = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (n in listOf("D", "L", "M", "M", "J", "V", "S")) semana.addView(ui.text(n, 11.5f, ui.muted, semibold = true).apply {
            gravity = Gravity.CENTER; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(0, dp(22), 1f))
        tarjeta.addView(semana)
        val primero = (mes.clone() as Calendar).apply { set(Calendar.DAY_OF_MONTH, 1) }
        val inicio = (primero.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, -(get(Calendar.DAY_OF_WEEK) - Calendar.SUNDAY)) }
        val mesActual = primero.get(Calendar.MONTH)
        val c = inicio.clone() as Calendar
        do {
            val fila = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            repeat(7) {
                val f = AgendaStore.fechaDe(c)
                val delMes = c.get(Calendar.MONTH) == mesActual
                val esHoy = f == hoy
                val elegido = f == diaElegido && !esHoy
                val celda = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    isClickable = true
                    isFocusable = true
                    if (f == diaElegido) background = round(conAlfa(blue, if (ui.night) 0.16f else 0.10f), 14)
                }
                celda.addView(ui.text(c.get(Calendar.DAY_OF_MONTH).toString(), 14.5f, when { esHoy -> sobreGrafito; !delMes -> faintCircle; else -> ui.ink }, semibold = esHoy || elegido).apply {
                    gravity = Gravity.CENTER
                    background = when {
                        esHoy -> GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(grafito) }
                        else -> null
                    }
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }, LinearLayout.LayoutParams(dp(34), dp(34)))
                val fila3 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
                val ps = puntos[f].orEmpty()
                ps.take(3).forEach { (col, lleno) -> fila3.addView(punto(col, lleno)) }
                celda.addView(fila3, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(8)).apply { topMargin = dp(1) })
                celda.contentDescription = fechaLarga(AgendaStore.calendarioDe(f)!!) + (if (esHoy) ", hoy" else "") +
                    (if (ps.isNotEmpty()) ", ${ps.size} " + (if (ps.size == 1) "cosa" else "cosas") else "") + if (f == diaElegido) ", elegido" else ""
                celda.setOnClickListener {
                    diaElegido = f
                    if (!delMes) mes = AgendaStore.calendarioDe(f) ?: mes
                    render()
                }
                fila.addView(celda, LinearLayout.LayoutParams(0, dp(48), 1f).apply { setMargins(dp(2), dp(1), dp(2), dp(1)) })
                c.add(Calendar.DAY_OF_MONTH, 1)
            }
            tarjeta.addView(fila)
        } while (c.get(Calendar.MONTH) == mesActual)
        content.addView(tarjeta, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            marginStart = dp(14); marginEnd = dp(14); bottomMargin = dp(12)
        })

        // the chosen day: «Martes 24 · hoy · 3»
        val evs = d.eventos.filter { fechaEv(it) == diaElegido }
        // today, as on the web: also the overdue ones (first) and what is in Mi día, and what was done today
        val ts = d.tareas.filter {
            it.fecha == diaElegido || (diaElegido == hoy && (AgendaEstilo.estado(it) == Estado.VENCIDA || (it.miDia && !it.completada) ||
                (it.completada && fechaDeMs(it.json.optLong("completada_en")) == hoy)))
        }.sortedWith(compareBy({ it.completada }, { it.hora ?: "99" }, { it.orden }))
        val n = evs.size + ts.size
        val nombre = AgendaStore.calendarioDe(diaElegido)?.let { SimpleDateFormat("EEEE d", es).apply { timeZone = AgendaStore.TZ }.format(it.time).replaceFirstChar { x -> x.uppercase() } } ?: diaElegido
        content.addView(grupoTexto(nombre + (if (diaElegido == hoy) " · hoy" else ""), n, ui.muted))
        if (n == 0) content.addView(vacio("Nada este día. Toca + Nuevo para agregar una tarea."))
        val items = evs.map { (if (it.todoElDia) "" else hhmm(it.inicioMs)) to filaEvento(it, d) } +
            ts.map { (when { it.completada -> "~"; AgendaEstilo.estado(it) == Estado.VENCIDA -> " "; else -> it.hora ?: "99" }) to filaTarea(it, d) }
        items.sortedBy { it.first }.forEach { content.addView(it.second) }
    }

    private fun punto(color: Int, lleno: Boolean) = View(this).apply {
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; if (lleno) setColor(color) else setStroke(dp(1.5f), color) }
        layoutParams = LinearLayout.LayoutParams(dp(5), dp(5)).apply { marginStart = dp(1.5f); marginEnd = dp(1.5f) }
    }

    /** AGE-02 / AGE-03: the hours of one or three days, with the all-day row on top. Returns the hours' grid. */
    private fun rejillaHoras(d: Datos, dias: List<String>): View? {
        val hoy = AgendaStore.hoy()
        val horaAlto = dp(48)
        val canal = dp(34)
        val tarjeta = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = round(cardBg, 18, line)
            clipToOutline = true
        }
        // day heads
        val cabeza = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(8), 0, dp(8)) }
        cabeza.addView(View(this), LinearLayout.LayoutParams(canal, 1))
        for (f in dias) {
            val c = AgendaStore.calendarioDe(f)!!
            val esHoy = f == hoy
            val elegido = f == diaElegido && !esHoy && dias.size > 1
            cabeza.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                isClickable = dias.size > 1
                isFocusable = dias.size > 1
                contentDescription = fechaLarga(c) + (if (esHoy) ", hoy" else "") + if (dias.size > 1) ". Toca para ver el día." else ""
                if (dias.size > 1) setOnClickListener { diaElegido = f; vista = "dia"; prefs.edit().putString("vista", "dia").apply(); irAHora = true; render() }
                addView(ui.text(SimpleDateFormat("EEE", es).apply { timeZone = AgendaStore.TZ }.format(c.time).replace(".", "").replaceFirstChar { it.uppercase() }, 12f, ui.muted).apply {
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                })
                addView(ui.text(c.get(Calendar.DAY_OF_MONTH).toString(), 15f, if (esHoy) sobreGrafito else ui.ink, semibold = true).apply {
                    gravity = Gravity.CENTER
                    background = when {
                        esHoy -> GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(grafito) }
                        elegido -> GradientDrawable().apply { shape = GradientDrawable.OVAL; setStroke(dp(1.6f), grafito) }
                        else -> null
                    }
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }, LinearLayout.LayoutParams(dp(30), dp(30)).apply { topMargin = dp(4) })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        tarjeta.addView(cabeza)
        tarjeta.addView(View(this).apply { setBackgroundColor(sep) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))

        // all day: all-day events and the tasks without a time
        val todoDia = dias.map { f -> d.eventos.filter { it.todoElDia && fechaEv(it) == f } to d.tareas.filter { it.fecha == f && it.hora == null } }
        if (todoDia.any { it.first.isNotEmpty() || it.second.isNotEmpty() }) {
            val fila = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(4), 0, dp(4)) }
            fila.addView(ui.text("Todo\nel día", 10f, ui.faint).apply { gravity = Gravity.CENTER; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO },
                LinearLayout.LayoutParams(canal, ViewGroup.LayoutParams.WRAP_CONTENT))
            for ((evs, ts) in todoDia) {
                val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(2), 0, dp(2), 0) }
                evs.forEach { col.addView(bloqueEvento(it, d, chico = true), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(26)).apply { bottomMargin = dp(3) }) }
                ts.forEach { col.addView(bloqueTarea(it, d), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(26)).apply { bottomMargin = dp(3) }) }
                fila.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
            tarjeta.addView(fila)
            tarjeta.addView(View(this).apply { setBackgroundColor(sep) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))
        }

        // the hours
        val rejilla = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val horas = FrameLayout(this)
        for (h in 1 until 24) horas.addView(ui.text(horaCorta(h), 10.5f, ui.faint).apply {
            gravity = Gravity.END
            setPadding(0, 0, dp(6), 0)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, FrameLayout.LayoutParams(canal, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = h * horaAlto - dp(7) })
        rejilla.addView(horas, LinearLayout.LayoutParams(canal, horaAlto * 24))
        val ahoraMs = System.currentTimeMillis()
        for (f in dias) {
            val col = Columna(this, horaAlto * 24).apply { background = LineasHoras(sep, horaAlto) }
            val inicioDia = AgendaStore.calendarioDe(f)!!.timeInMillis
            val bloques = mutableListOf<Triple<Int, Int, View>>()
            for (e in d.eventos.filter { !it.todoElDia && fechaEv(it) == f }) {
                val ini = ((e.inicioMs - inicioDia) / 60_000L).toInt().coerceIn(0, 24 * 60 - 15)
                val fin = ((AgendaEstilo.finDe(e) - inicioDia) / 60_000L).toInt().coerceIn(ini + 15, 24 * 60)
                bloques.add(Triple(ini, fin, bloqueEvento(e, d, chico = false)))
            }
            for (t in d.tareas.filter { it.fecha == f && it.hora != null }) {
                val p = t.hora!!.split(":").mapNotNull { it.toIntOrNull() }
                val ini = (p.getOrElse(0) { 0 } * 60 + p.getOrElse(1) { 0 }).coerceIn(0, 24 * 60 - 30)
                bloques.add(Triple(ini, ini + 30, bloqueTarea(t, d)))
            }
            // overlapping ones share the width (greedy lanes per group)
            val orden = bloques.sortedBy { it.first }
            var i = 0
            while (i < orden.size) {
                var j = i
                var finGrupo = orden[i].second
                while (j + 1 < orden.size && orden[j + 1].first < finGrupo) { j++; finGrupo = maxOf(finGrupo, orden[j].second) }
                val grupoB = orden.subList(i, j + 1)
                val carriles = mutableListOf<Int>()
                val carrilDe = grupoB.map { b ->
                    val k = carriles.indexOfFirst { it <= b.first }.takeIf { it >= 0 } ?: carriles.size.also { carriles.add(0) }
                    carriles[k] = b.second
                    k
                }
                val total = carriles.size
                grupoB.forEachIndexed { idx, (ini, fin, v) ->
                    val alto = maxOf((fin - ini) * horaAlto / 60, dp(24))
                    col.addView(v, Columna.LP(ini * horaAlto / 60, alto, carrilDe[idx].toFloat() / total, (carrilDe[idx] + 1).toFloat() / total))
                }
                i = j + 1
            }
            if (f == hoy) {
                val min = ((ahoraMs - inicioDia) / 60_000L).toInt()
                col.addView(View(this).apply { setBackgroundColor(AgendaEstilo.ambarPunto(ui.night)); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO },
                    Columna.LP(min * horaAlto / 60, dp(2), 0f, 1f))
            }
            rejilla.addView(col, LinearLayout.LayoutParams(0, horaAlto * 24, 1f))
        }
        val alto = maxOf(dp(360), resources.displayMetrics.heightPixels - dp(440))
        val dentro = ScrollView(this).apply {
            isNestedScrollingEnabled = true
            isVerticalScrollBarEnabled = false
            addView(rejilla)
        }
        tarjeta.addView(dentro, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, alto))
        content.addView(tarjeta, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            marginStart = dp(14); marginEnd = dp(14); bottomMargin = dp(12)
        })
        // first look: an hour before now on today, else 7:00 (inside the card; the page stays on top)
        if (irAHora) {
            irAHora = false
            val horaIr = if (hoy in dias) (AgendaStore.calendario().get(Calendar.HOUR_OF_DAY) - 1).coerceIn(0, 20) else 7
            horasY = horaIr * horaAlto
        }
        val y = horasY
        dentro.post { dentro.scrollTo(0, y) }
        dentro.setOnScrollChangeListener { _, _, nuevo, _, _ -> if (dentro.isAttachedToWindow) horasY = nuevo }
        return null
    }

    /** Where the hours were scrolled to, kept across redraws (every minute and after each change). */
    private var horasY = 0

    private fun horaCorta(h: Int) = h.toString()

    /** An event in the hours or the all-day row: tinted with its calendar's colour, the bar on the left. */
    private fun bloqueEvento(e: AgendaStore.Evento, d: Datos, chico: Boolean): View {
        val color = AgendaEstilo.calDeEvento(e, d.cals).color.de(ui.night)
        val fecha = fechaEv(e)
        val hecho = AgendaStore.eventoHecho(this, e.id, fecha)
        val ahora = AgendaEstilo.eventoAhora(e)
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = GradientDrawable().apply {
                cornerRadius = dp(7).toFloat()
                setColor(conAlfa(color, if (ui.night) 0.26f else 0.14f))
                if (ahora && !hecho) setStroke(dp(1.5f), AgendaEstilo.AMBAR)
            }
            if (AgendaEstilo.eventoPasado(e) && !ahora) alpha = 0.55f
            isClickable = true
            isFocusable = true
            setOnClickListener { menuEvento(e) }
            contentDescription = e.titulo + ", " + (if (e.todoElDia) "todo el día" else hhmm(e.inicioMs) + " a " + hhmm(AgendaEstilo.finDe(e))) + (if (hecho) ", terminado" else "") + ", evento"
            addView(View(this@AgendaActivity).apply { background = round(color, 2) }, LinearLayout.LayoutParams(dp(3), ViewGroup.LayoutParams.MATCH_PARENT))
            addView(LinearLayout(this@AgendaActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(5), dp(if (chico) 0 else 3), dp(4), 0)
                gravity = if (chico) Gravity.CENTER_VERTICAL else Gravity.TOP
                addView(ui.text(e.titulo, 12f, ui.ink, semibold = true).apply {
                    maxLines = if (chico) 1 else 2; ellipsize = TextUtils.TruncateAt.END
                    if (hecho) paintFlags = paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
                })
                if (!chico) addView(ui.text(hhmm(e.inicioMs), 10.5f, ui.muted).apply { maxLines = 1 })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }
    }

    /** A task in the hours (half an hour tall) or in the all-day row: its circle and its title. */
    private fun bloqueTarea(t: AgendaStore.Tarea, d: Datos): View {
        val st = AgendaEstilo.estado(t)
        val color = AgendaEstilo.calDeTarea(t, d.secMap, d.cals)?.color?.de(ui.night)
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(5), 0, dp(4), 0)
            background = GradientDrawable().apply {
                cornerRadius = dp(7).toFloat()
                setColor(cardBg)
                setStroke(dp(1), if (st == Estado.VENCIDA) red else if (st == Estado.AHORA) AgendaEstilo.AMBAR else line)
            }
            isClickable = true
            isFocusable = true
            setOnClickListener { detalle(t) }
            setOnLongClickListener { menuTarea(t); true }
            val estadoTxt = when (st) { Estado.PENDIENTE -> "pendiente"; Estado.VENCIDA -> "vencida"; Estado.AHORA -> "ahora"; Estado.HECHA -> "hecha" }
            contentDescription = t.titulo + ", tarea " + estadoTxt + (t.hora?.let { ", $it" } ?: "")
            addView(View(this@AgendaActivity).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    when (st) { Estado.HECHA -> setColor(green); Estado.VENCIDA -> setStroke(dp(1.6f), red); else -> setStroke(dp(1.6f), aro) }
                }
            }, LinearLayout.LayoutParams(dp(11), dp(11)).apply { marginEnd = dp(5) })
            addView(ui.text(t.titulo, 12f, if (st == Estado.HECHA) faintCircle else ui.ink, semibold = true).apply {
                maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                if (st == Estado.HECHA) paintFlags = paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
    }

    /** A column of the hours: lays its children out by their minutes and their share of the width. */
    private class Columna(context: Context, private val alto: Int) : ViewGroup(context) {
        class LP(val arriba: Int, val altura: Int, val desde: Float, val hasta: Float) : ViewGroup.LayoutParams(0, 0)

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val ancho = MeasureSpec.getSize(widthMeasureSpec)
            for (i in 0 until childCount) {
                val v = getChildAt(i)
                val lp = v.layoutParams as? LP ?: continue
                val w = ((lp.hasta - lp.desde) * ancho).toInt() - 3
                v.measure(MeasureSpec.makeMeasureSpec(maxOf(w, 1), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(lp.altura, MeasureSpec.EXACTLY))
            }
            setMeasuredDimension(ancho, alto)
        }

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            val ancho = r - l
            for (i in 0 until childCount) {
                val v = getChildAt(i)
                val lp = v.layoutParams as? LP ?: continue
                val x = (lp.desde * ancho).toInt() + 2
                v.layout(x, lp.arriba, x + v.measuredWidth, lp.arriba + v.measuredHeight)
            }
        }

        override fun checkLayoutParams(p: ViewGroup.LayoutParams?) = p is LP
    }

    /** The hour lines behind a column, and its left border. */
    private class LineasHoras(tono: Int, private val paso: Int) : Drawable() {
        private val p = Paint().apply { color = tono; strokeWidth = 1f }
        override fun draw(canvas: Canvas) {
            val b = bounds
            var y = b.top.toFloat() + paso
            while (y < b.bottom) { canvas.drawLine(b.left.toFloat(), y, b.right.toFloat(), y, p); y += paso }
            canvas.drawLine(b.left + 0.5f, b.top.toFloat(), b.left + 0.5f, b.bottom.toFloat(), p)
        }
        override fun setAlpha(alpha: Int) { p.alpha = alpha }
        override fun setColorFilter(colorFilter: ColorFilter?) { p.colorFilter = colorFilter }
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    /** «Sin calendario»: a dotted grey bar, so it is not taken for Gris or Grafito of the palette. */
    private class BarraPunteada(color: Int, private val grosor: Float) : Drawable() {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color; strokeWidth = grosor; strokeCap = Paint.Cap.ROUND
            pathEffect = DashPathEffect(floatArrayOf(grosor * 0.6f, grosor * 1.6f), 0f)
        }
        override fun draw(canvas: Canvas) {
            val x = bounds.exactCenterX()
            canvas.drawLine(x, bounds.top + grosor, x, bounds.bottom - grosor, p)
        }
        override fun setAlpha(alpha: Int) { p.alpha = alpha }
        override fun setColorFilter(colorFilter: ColorFilter?) { p.colorFilter = colorFilter }
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    // ── Menus (AGE-05, AGE-15, AGE-23) ──

    private data class Item(val texto: String, val icono: Int, val peligro: Boolean = false, val activo: Boolean = false, val punto: Int? = null, val accion: () -> Unit)

    /** A bottom sheet with one row per choice; null draws a separator. */
    private fun menu(titulo: String, items: List<Item?>, subtitulo: String? = null) {
        val (dialog, caja) = hoja(scrollable = items.size > 9)
        caja.addView(ui.text(titulo, 15f, ui.ink, semibold = true).apply {
            maxLines = 2; ellipsize = TextUtils.TruncateAt.END; setPadding(dp(8), 0, dp(8), if (subtitulo == null) dp(8) else dp(2))
        })
        if (subtitulo != null) caja.addView(ui.text(subtitulo, 12.5f, ui.muted).apply { setPadding(dp(8), 0, dp(8), dp(8)) })
        for (it in items) {
            if (it == null) {
                caja.addView(View(this).apply { setBackgroundColor(sep) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply { topMargin = dp(4); bottomMargin = dp(4) })
                continue
            }
            caja.addView(opcion(it.icono, it.texto, it.activo, it.peligro, it.punto) { dialog.dismiss(); it.accion() })
        }
        dialog.show()
    }

    /** AGE-05 · ··· or long press. Overdue (AGE-23): done, today, tomorrow or another date first. */
    private fun menuTarea(t: AgendaStore.Tarea) {
        val st = AgendaEstilo.estado(t)
        fun cambiar(c: JSONObject) { AgendaStore.cambiarTarea(this, t.id, c); despuesDeCambiar() }
        val items = mutableListOf<Item?>()
        if (st == Estado.VENCIDA) {
            items.add(Item("Marcar como hecha", R.drawable.ic_ui_check) { AgendaStore.completar(this, t.id, true); despuesDeCambiar() })
            items.add(Item("Pasar a hoy", R.drawable.ic_ag_sun) { cambiar(JSONObject().put("fecha", AgendaStore.hoy()).put("hora", JSONObject.NULL)) })
            items.add(Item("Pasar a mañana", R.drawable.ic_ui_calendar) { cambiar(JSONObject().put("fecha", sumarDias(AgendaStore.hoy(), 1))) })
            items.add(Item("Elegir fecha…", R.drawable.ic_ui_calendar) { calendarioPicker(t.fecha) { f -> cambiar(JSONObject().put("fecha", f)) } })
            items.add(null)
        }
        items.add(Item(if (t.miDia) "Quitar de Mi día" else "Agregar a Mi día", R.drawable.ic_ag_sun, activo = t.miDia) {
            cambiar(JSONObject().put("mi_dia", if (t.miDia) JSONObject.NULL else AgendaStore.hoy()))
        })
        items.add(Item(if (t.importante) "Quitar de importantes" else "Marcar como importante", if (t.importante) R.drawable.ic_ag_star_fill else R.drawable.ic_ag_star, activo = t.importante) {
            cambiar(JSONObject().put("importante", !t.importante))
        })
        if (st != Estado.VENCIDA) items.add(Item("Fecha de vencimiento…", R.drawable.ic_ui_calendar, activo = t.fecha != null) { elegirFecha(t) { c -> cambiar(c) } })
        items.add(Item("Recordarme…", R.drawable.ic_ag_bell, activo = t.recordar != null) {
            elegirRecordar(t.recordar, t.fecha, t.hora, conExacta = true) { r -> cambiar(conFechaSiHaceFalta(JSONObject().put("recordar", r), t, r)) }
        })
        items.add(Item("Repetir…", R.drawable.ic_ag_repeat, activo = t.repetir != null) {
            elegirRepetir(t.repetir) { r -> val c = JSONObject().put("repetir", r); if (r != JSONObject.NULL && t.fecha == null) c.put("fecha", AgendaStore.hoy()); cambiar(c) }
        })
        items.add(Item("Etiqueta…", R.drawable.ic_ag_tag, activo = t.seccionId != null) { elegirEtiqueta(t.seccionId) { s -> cambiar(JSONObject().put("seccion_id", s)) } })
        items.add(null)
        items.add(Item("Eliminar", R.drawable.ic_ag_trash, peligro = true) { confirmarBorrar(t) {} })
        menu(t.titulo, items, if (st == Estado.VENCIDA) "Vencida · " + (t.fecha?.let { fechaRel(it) + (t.hora?.let { h -> " $h" } ?: "") } ?: "") else null)
    }

    private fun conFechaSiHaceFalta(c: JSONObject, t: AgendaStore.Tarea, r: Any): JSONObject {
        if (r != JSONObject.NULL && t.fecha == null && (r as? String)?.let { AgendaEstilo.esMinutos(it) } == true) c.put("fecha", AgendaStore.hoy())
        return c
    }

    /** AGE-15 · ··· of an event: edit, mark as done, star, delete. */
    private fun menuEvento(e: AgendaStore.Evento) {
        val fecha = fechaEv(e)
        val hecho = AgendaStore.eventoHecho(this, e.id, fecha)
        val imp = AgendaStore.eventoImportante(this, e.id)
        val cuando = if (e.todoElDia) fechaRel(fecha) + " · todo el día" else fechaRel(fecha) + " " + hhmm(e.inicioMs) + "–" + hhmm(AgendaEstilo.finDe(e))
        menu(e.titulo, listOf(
            Item("Editar", R.drawable.ic_ag_edit) { editarEvento(e) },
            Item(if (hecho) "Marcar como pendiente" else "Marcar como terminado", R.drawable.ic_ui_check, activo = hecho) { AgendaStore.marcarEventoHecho(this, e.id, fecha, !hecho) },
            Item(if (imp) "Quitar de importantes" else "Marcar como importante", if (imp) R.drawable.ic_ag_star_fill else R.drawable.ic_ag_star, activo = imp) { AgendaStore.marcarEventoImportante(this, e.id, !imp) },
            null,
            Item("Eliminar evento", R.drawable.ic_ag_trash, peligro = true) { confirmarBorrarEvento(e) {} },
        ), "$cuando · Evento")
    }

    private fun confirmarBorrarEvento(e: AgendaStore.Evento, despues: () -> Unit) {
        val serie = !e.json.optString("rrule").let { it.isEmpty() || it == "null" }
        AlertDialog.Builder(this)
            .setTitle("¿Eliminar «${e.titulo}»?")
            .setMessage((if (serie) "Este evento se repite: se elimina toda la serie. " else "") + "Se elimina de tu calendario en todos tus dispositivos.")
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Eliminar") { _, _ -> AgendaStore.borrarEvento(this, e.id); despuesDeCambiar(); despues() }
            .show()
    }

    /** «Editar evento», on the phone: title, calendar, date, times, reminder and place; saved now, sent with the queue. */
    private fun editarEvento(e: AgendaStore.Evento) {
        if (!e.json.optString("rrule").let { it.isEmpty() || it == "null" }) {
            AlertDialog.Builder(this)
                .setTitle("Evento que se repite")
                .setMessage("Para no cambiar toda la serie por error, este evento se edita en la Agenda web.")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Abrir la Agenda web") { _, _ -> abrirWeb() }
                .show()
            return
        }
        val (dialog, caja) = hoja(scrollable = true)
        val fechaOriginal = fechaEv(e)
        var fecha = fechaOriginal
        var calId = e.calendarId
        var hIni = hhmm(e.inicioMs)
        var hFin = hhmm(AgendaEstilo.finDe(e))
        var alerta: Int? = e.avisoMin
        caja.addView(ui.text("Editar evento", 13f, ui.muted, semibold = true).apply { setPadding(dp(4), 0, 0, dp(2)) })
        val titulo = EditText(this).apply {
            setText(e.titulo)
            setTextColor(ui.ink)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            background = null
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setPadding(dp(4), dp(4), dp(4), dp(8))
        }
        caja.addView(titulo)
        val filas = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        caja.addView(filas)
        val lugar = EditText(this).apply {
            setText(e.json.optString("location").takeIf { it != "null" }.orEmpty())
            hint = "Ubicación"
            setTextColor(ui.ink); setHintTextColor(ui.hintColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            background = round(ui.tile, 12)
            setSingleLine()
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        fun pintar() {
            filas.removeAllViews()
            val cal = AgendaStore.calendarios(this).firstOrNull { it.optString("id") == calId }
            val color = AgendaEstilo.paleta(e.json.optString("color").takeIf { it.startsWith("#") } ?: cal?.optString("color")).de(ui.night)
            filas.addView(opcion(R.drawable.ic_ag_tag, "Calendario: " + AgendaEstilo.nombreCalendario(cal?.optString("name") ?: "—"), false, false, color) {
                val cals = AgendaStore.calendarios(this).filter { AgendaEstilo.esCalendarioDeEtiqueta(it) || it.optString("id") == e.calendarId }
                menu("Calendario", cals.map { c ->
                    Item(AgendaEstilo.nombreCalendario(c.optString("name")), R.drawable.ic_ag_tag, activo = c.optString("id") == calId,
                        punto = AgendaEstilo.paleta(c.optString("color")).de(ui.night)) { calId = c.optString("id"); pintar() }
                })
            })
            filas.addView(opcion(R.drawable.ic_ui_calendar, fechaLarga(AgendaStore.calendarioDe(fecha)!!).replaceFirstChar { it.uppercase() }, false) {
                calendarioPicker(fecha) { f -> fecha = f; pintar() }
            })
            if (e.todoElDia) filas.addView(opcion(R.drawable.ic_ag_clock, "Todo el día", false) { })
            else {
                filas.addView(opcion(R.drawable.ic_ag_clock, "Empieza a las ${horaBonita(fecha, hIni)}", false) { horaPicker(hIni) { h -> hFin = sumarMinutos(h, minutosEntre(hIni, hFin)); hIni = h; pintar() } })
                filas.addView(opcion(R.drawable.ic_ag_clock, "Termina a las ${horaBonita(fecha, hFin)}", false) { horaPicker(hFin) { h -> hFin = h; pintar() } })
            }
            filas.addView(opcion(R.drawable.ic_ag_bell, alerta?.let { "Recordatorio: " + AgendaEstilo.minutosTexto(it).replaceFirstChar { c -> c.lowercase() } } ?: "Sin recordatorio", alerta != null) {
                elegirRecordar(alerta?.toString(), fecha, if (e.todoElDia) null else hIni, conExacta = false, evento = true) { r -> alerta = (r as? String)?.toIntOrNull(); pintar() }
            })
        }
        pintar()
        caja.addView(lugar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8); bottomMargin = dp(14) })
        caja.addView(ui.primaryButton("Guardar") {
            val c = JSONObject()
            val nuevoTitulo = titulo.text.toString().trim()
            if (nuevoTitulo.isEmpty()) { titulo.error = "Escribe el título"; return@primaryButton }
            c.put("title", nuevoTitulo.take(300))
            c.put("calendar_id", calId)
            c.put("location", lugar.text.toString().trim())
            val (ini, fin) = if (e.todoElDia) {
                val delta = diasEntre(fechaOriginal, fecha) * 86_400_000L
                (e.inicioMs + delta) to (e.finMs.takeIf { it > 0 }?.plus(delta) ?: (e.inicioMs + delta + 86_400_000L))
            } else {
                val a = AgendaStore.calendarioDe(fecha, hIni)!!.timeInMillis
                var b = AgendaStore.calendarioDe(fecha, hFin)!!.timeInMillis
                if (b <= a) b += if (hFin < hIni) 86_400_000L else 3_600_000L
                a to b
            }
            c.put("start_at", ini * 1_000_000L)
            c.put("end_at", fin * 1_000_000L)
            val meta = JSONObject((e.json.optJSONObject("meta") ?: JSONObject()).toString())
            if (alerta == null) meta.remove("alert_minutes") else meta.put("alert_minutes", alerta)
            // a new time or reminder may ring again
            if (ini != e.inicioMs || alerta != e.avisoMin) meta.remove("alerted_at")
            c.put("meta", meta)
            AgendaStore.cambiarEvento(this, e.id, c)
            despuesDeCambiar()
            dialog.dismiss()
        })
        val pie = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4), dp(12), 0, 0) }
        pie.addView(ui.text(if (AgendaStore.pendiente(this, e.id)) "Guardado en el teléfono; se enviará al volver la red." else "Se guarda en el teléfono y se envía a tu PC.", 12.5f, ui.muted),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        pie.addView(iconButton(R.drawable.ic_ag_trash, "Eliminar evento", red) { confirmarBorrarEvento(e) { dialog.dismiss() } })
        caja.addView(pie)
        dialog.setOnDismissListener { (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(window.decorView.windowToken, 0) }
        dialog.show()
    }

    // ── Sheets ──

    /** AGE-06 / AGE-16 · «Agregar una tarea» with Fecha, Recordar, Repetir, Mi día, Importante and Etiqueta. */
    private fun hojaAgregar() {
        val elegido = JSONObject()
        if (tab == "calendario") elegido.put("fecha", diaElegido)
        if (tab == "tareas" && lista == "mi_dia") elegido.put("mi_dia", AgendaStore.hoy())
        if (tab == "tareas" && lista == "importante") elegido.put("importante", true)
        val (dialog, caja) = hoja()
        val campo = EditText(this).apply {
            hint = "Agregar una tarea"
            setTextColor(ui.ink); setHintTextColor(ui.hintColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            background = null
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = EditorInfo.IME_ACTION_DONE
            setSingleLine()
            setPadding(dp(4), dp(6), dp(4), dp(10))
        }
        caja.addView(campo)
        val atajos = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(4), 0, dp(14)) }
        fun pintar() {
            atajos.removeAllViews()
            val t = AgendaStore.Tarea(elegido)
            fun add(texto: String, on: Boolean, punto: Int? = null, accion: () -> Unit) = atajos.addView(pastilla(texto, on, punto, accion),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)).apply { marginEnd = dp(8) })
            add(t.fecha?.let { fechaCorta(it) + (t.hora?.let { h -> " " + horaBonita(it, h) } ?: "") } ?: "Fecha", t.fecha != null) {
                elegirFecha(t) { cambios -> for (k in cambios.keys()) elegido.put(k, cambios.get(k)); pintar() }
            }
            add(t.recordar?.let { recordarCorto(it) } ?: "Recordar", t.recordar != null) {
                elegirRecordar(t.recordar, t.fecha, t.hora, conExacta = true) { r -> elegido.put("recordar", r); conFechaSiHaceFalta(elegido, t, r); pintar() }
            }
            add(t.repetir?.let { textoRepetir(it) } ?: "Repetir", t.repetir != null) {
                elegirRepetir(t.repetir) { r -> elegido.put("repetir", r); if (r != JSONObject.NULL && t.fecha == null) elegido.put("fecha", AgendaStore.hoy()); pintar() }
            }
            add("Mi día", t.miDia) { elegido.put("mi_dia", if (t.miDia) JSONObject.NULL else AgendaStore.hoy()); pintar() }
            add(if (t.importante) "★ Importante" else "☆ Importante", t.importante) { elegido.put("importante", !t.importante); pintar() }
            val sec = t.seccionId?.let { s -> AgendaStore.secciones(this).firstOrNull { it.id == s } }
            val col = sec?.let { AgendaEstilo.calDeTarea(t, mapOf(it.id to it), AgendaEstilo.calendarios(this))?.color?.de(ui.night) }
            add(sec?.nombre ?: "Etiqueta", sec != null, col) { elegirEtiqueta(t.seccionId) { s -> elegido.put("seccion_id", s); pintar() } }
        }
        pintar()
        caja.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(atajos) })
        fun guardar() {
            val titulo = campo.text.toString().trim()
            if (titulo.isEmpty()) { campo.error = "Escribe la tarea"; return }
            AgendaStore.nuevaTarea(this, titulo, elegido)
            despuesDeCambiar()
            dialog.dismiss()
        }
        campo.setOnEditorActionListener { _, _, _ -> guardar(); true }
        caja.addView(ui.primaryButton("Agregar") { guardar() })
        if (cerrarAlAgregar) dialog.setOnDismissListener { cerrarAlAgregar = false; finish() }
        dialog.show()
        campo.requestFocus()
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    }

    /** The task, all of it: title, Mi día, date, reminder, repeat, label, steps, notes, delete. */
    private fun detalle(original: AgendaStore.Tarea) {
        val (dialog, caja) = hoja(scrollable = true)
        val id = original.id
        fun actual() = AgendaStore.tarea(this, id)
        fun cambiar(campos: JSONObject) { AgendaStore.cambiarTarea(this, id, campos); despuesDeCambiar() }
        val tituloCampo = EditText(this).apply {
            setText(original.titulo)
            setTextColor(ui.ink)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            background = null
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setPadding(dp(4), dp(4), dp(4), dp(8))
        }
        val cuerpo = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val notas = EditText(this).apply {
            setText(original.notas)
            hint = "Agregar nota"
            setTextColor(ui.ink); setHintTextColor(ui.hintColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            background = round(ui.tile, 12)
            gravity = Gravity.TOP
            minLines = 3
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        fun pintar() {
            val t = actual() ?: run { dialog.dismiss(); return }
            val d = datos()
            val st = AgendaEstilo.estado(t)
            val cal = AgendaEstilo.calDeTarea(t, d.secMap, d.cals)
            cuerpo.removeAllViews()
            val cabecera = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            cabecera.addView(circulo(t, st, cal?.color?.de(ui.night)).also { v -> v.setOnClickListener { AgendaStore.completar(this, id, st != Estado.HECHA); despuesDeCambiar(); pintar() } },
                LinearLayout.LayoutParams(dp(40), dp(40)))
            (tituloCampo.parent as? ViewGroup)?.removeView(tituloCampo)
            cabecera.addView(tituloCampo, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            cabecera.addView(estrella(t.importante, t.titulo) { cambiar(JSONObject().put("importante", !t.importante)); pintar() })
            cuerpo.addView(cabecera)
            cuerpo.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(dp(8), 0, 0, dp(6))
                addView(pastillaEstado(st), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(20)))
            })
            if (st == Estado.VENCIDA) cuerpo.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(dp(4), dp(2), 0, dp(6))
                addView(pastilla("Pasar a hoy", false) { cambiar(JSONObject().put("fecha", AgendaStore.hoy()).put("hora", JSONObject.NULL)); pintar() },
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(34)).apply { marginEnd = dp(8) })
                addView(pastilla("Pasar a mañana", false) { cambiar(JSONObject().put("fecha", sumarDias(AgendaStore.hoy(), 1))); pintar() },
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(34)))
            })
            cuerpo.addView(opcion(R.drawable.ic_ag_sun, if (t.miDia) "Agregada a Mi día" else "Agregar a Mi día", t.miDia) {
                cambiar(JSONObject().put("mi_dia", if (t.miDia) JSONObject.NULL else AgendaStore.hoy())); pintar()
            })
            cuerpo.addView(opcion(R.drawable.ic_ui_calendar, t.fecha?.let { "Vence " + fechaLarga(AgendaStore.calendarioDe(it)!!) + (t.hora?.let { h -> " a las " + horaBonita(it, h) } ?: "") } ?: "Agregar fecha de vencimiento", t.fecha != null, st == Estado.VENCIDA) {
                elegirFecha(t) { c -> cambiar(c); pintar() }
            })
            cuerpo.addView(opcion(R.drawable.ic_ag_bell, t.recordar?.let { "Recordarme " + textoRecordar(it).replaceFirstChar { c -> c.lowercase() } } ?: "Recordarme", t.recordar != null) {
                elegirRecordar(t.recordar, t.fecha, t.hora, conExacta = true) { r -> cambiar(conFechaSiHaceFalta(JSONObject().put("recordar", r), t, r)); pintar() }
            })
            cuerpo.addView(opcion(R.drawable.ic_ag_repeat, t.repetir?.let { textoRepetir(it) } ?: "Repetir", t.repetir != null) {
                elegirRepetir(t.repetir) { r -> val c = JSONObject().put("repetir", r); if (r != JSONObject.NULL && t.fecha == null) c.put("fecha", AgendaStore.hoy()); cambiar(c); pintar() }
            })
            cuerpo.addView(opcion(R.drawable.ic_ag_tag, cal?.let { "Etiqueta: ${it.nombre}" } ?: "Agregar etiqueta", cal != null, false, cal?.color?.de(ui.night)) {
                elegirEtiqueta(t.seccionId) { s -> cambiar(JSONObject().put("seccion_id", s)); pintar() }
            })
            // steps
            cuerpo.addView(etiqueta("Pasos", ui.muted).apply { setPadding(dp(4), dp(14), 0, dp(6)) })
            for (p in t.pasos) {
                cuerpo.addView(LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    isClickable = true
                    contentDescription = p.texto + if (p.hecho) ", hecho" else ", pendiente"
                    addView(View(this@AgendaActivity).apply {
                        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; if (p.hecho) setColor(green) else setStroke(dp(1.6f), faintCircle) }
                    }, LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginStart = dp(6); marginEnd = dp(12) })
                    addView(ui.text(p.texto, 15f, if (p.hecho) faintCircle else ui.ink).apply {
                        if (p.hecho) paintFlags = paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
                    }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    setOnClickListener { cambiar(JSONObject().put("pasos", pasosCon(t) { if (it.optString("id") == p.id) it.put("hecho", !p.hecho) })); pintar() }
                    setOnLongClickListener {
                        cambiar(JSONObject().put("pasos", JSONArray().apply { t.pasos.filter { it.id != p.id }.forEach { put(JSONObject().put("id", it.id).put("texto", it.texto).put("hecho", it.hecho)) } }))
                        pintar(); true
                    }
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)))
            }
            val nuevoPaso = EditText(this).apply {
                hint = "+ Agregar paso"
                setTextColor(ui.ink); setHintTextColor(blue)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                background = null
                setSingleLine()
                imeOptions = EditorInfo.IME_ACTION_DONE
                setPadding(dp(6), dp(8), dp(6), dp(8))
                setOnEditorActionListener { v, _, _ ->
                    val texto = v.text.toString().trim()
                    if (texto.isNotEmpty()) {
                        cambiar(JSONObject().put("pasos", pasosCon(t) { }.put(JSONObject().put("id", UUID.randomUUID().toString()).put("texto", texto).put("hecho", false))))
                        pintar()
                    }
                    true
                }
            }
            cuerpo.addView(nuevoPaso)
            (notas.parent as? ViewGroup)?.removeView(notas)
            cuerpo.addView(notas, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
            val pie = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4), dp(14), 0, 0) }
            pie.addView(ui.text(
                (if (AgendaStore.pendiente(this, id)) "Guardada en el teléfono; se enviará al volver la red. " else "") +
                    "Creada el " + fechaLarga(AgendaStore.calendario().apply { timeInMillis = t.creada.takeIf { it > 0 } ?: System.currentTimeMillis() }),
                12.5f, ui.muted), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            pie.addView(iconButton(R.drawable.ic_ag_trash, "Eliminar tarea", red) { confirmarBorrar(t) { dialog.dismiss() } })
            cuerpo.addView(pie)
        }
        caja.addView(cuerpo)
        pintar()
        dialog.setOnDismissListener {
            val t = actual() ?: return@setOnDismissListener
            val c = JSONObject()
            val titulo = tituloCampo.text.toString().trim()
            if (titulo.isNotEmpty() && titulo != t.titulo) c.put("titulo", titulo.take(300))
            val n = notas.text.toString()
            if (n != t.notas) c.put("notas", n.take(8000))
            if (c.length() > 0) cambiar(c)
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(window.decorView.windowToken, 0)
        }
        dialog.show()
    }

    private fun pasosCon(t: AgendaStore.Tarea, cambio: (JSONObject) -> Unit): JSONArray = JSONArray().apply {
        t.pasos.forEach { p -> put(JSONObject().put("id", p.id).put("texto", p.texto).put("hecho", p.hecho).also(cambio)) }
    }

    private fun confirmarBorrar(t: AgendaStore.Tarea, despues: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle("¿Eliminar «${t.titulo}»?")
            .setMessage("Se elimina de tu Agenda en todos tus dispositivos.")
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Eliminar") { _, _ -> AgendaStore.borrarTarea(this, t.id); despuesDeCambiar(); despues() }
            .show()
    }

    // ── Pickers ──

    private fun elegirFecha(t: AgendaStore.Tarea, listo: (JSONObject) -> Unit) {
        val hoy = AgendaStore.calendario()
        val manana = (hoy.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, 1) }
        val lunes = (hoy.clone() as Calendar).apply { do add(Calendar.DAY_OF_MONTH, 1) while (get(Calendar.DAY_OF_WEEK) != Calendar.MONDAY) }
        val items = mutableListOf<Item?>(
            Item("Hoy", R.drawable.ic_ag_sun, activo = t.fecha == AgendaStore.fechaDe(hoy)) { listo(JSONObject().put("fecha", AgendaStore.fechaDe(hoy))) },
            Item("Mañana", R.drawable.ic_ui_calendar, activo = t.fecha == AgendaStore.fechaDe(manana)) { listo(JSONObject().put("fecha", AgendaStore.fechaDe(manana))) },
            Item("Próxima semana (" + fechaCorta(AgendaStore.fechaDe(lunes)) + ")", R.drawable.ic_ui_calendar) { listo(JSONObject().put("fecha", AgendaStore.fechaDe(lunes))) },
            Item("Elegir fecha…", R.drawable.ic_ui_calendar) { calendarioPicker(t.fecha) { f -> listo(JSONObject().put("fecha", f)) } },
        )
        if (t.fecha != null) {
            items.add(null)
            items.add(Item(if (t.hora != null) "Cambiar la hora (${horaBonita(t.fecha!!, t.hora!!)})" else "Agregar hora…", R.drawable.ic_ag_clock) {
                horaPicker(t.hora) { h -> listo(JSONObject().put("hora", h)) }
            })
            if (t.hora != null) items.add(Item("Quitar la hora (todo el día)", R.drawable.ic_ag_clock) { listo(JSONObject().put("hora", JSONObject.NULL)) })
            items.add(Item("Quitar la fecha", R.drawable.ic_ag_trash) { listo(JSONObject().put("fecha", JSONObject.NULL).put("hora", JSONObject.NULL)) })
        }
        menu("Vence", items)
    }

    /** For the list: «1 h antes», «2 h 30 min antes», «Aviso jue 26 9:00 a. m.». */
    private fun recordarCorto(r: String): String =
        if (AgendaEstilo.esMinutos(r)) AgendaEstilo.minutosTexto(r.toInt())
        else r.split("T").takeIf { it.size == 2 }?.let { "Aviso " + fechaCorta(it[0]).lowercase() + " " + horaBonita(it[0], it[1].take(5)) } ?: "Con aviso"

    private fun textoRecordar(r: String): String =
        if (AgendaEstilo.esMinutos(r)) AgendaEstilo.minutosTexto(r.toInt())
        else r.split("T").takeIf { it.size == 2 }?.let { "El " + fechaCorta(it[0]) + " a las " + horaBonita(it[0], it[1].take(5)) } ?: "Recordatorio"

    /**
     * AGE-09 / AGE-20 · the same reminder menu for tasks and events: no reminder, at the time, 5 / 10 / 15 / 30 min,
     * 1 h, 1 / 2 / 3 days, 1 week, the custom ones already used, «Personalizado…» and (tasks) an exact day and time.
     */
    private fun elegirRecordar(actual: String?, fecha: String?, hora: String?, conExacta: Boolean, evento: Boolean = false, listo: (Any) -> Unit) {
        val items = mutableListOf<Item?>()
        items.add(Item("Sin aviso", R.drawable.ic_ag_bell, activo = actual == null) { listo(JSONObject.NULL) })
        val minutos = (AgendaEstilo.AVISOS + AgendaStore.avisosPropios(this)).distinct().sorted()
        for (m in minutos) {
            val txt = if (m == 0) (if (evento) "A la hora del evento" else "A la hora") else AgendaEstilo.minutosTexto(m)
            items.add(Item(txt, R.drawable.ic_ag_bell, activo = actual == m.toString()) { listo(m.toString()) })
        }
        items.add(Item("Personalizado…", R.drawable.ic_ag_clock) { avisoPersonalizado(fecha, hora, actual?.toIntOrNull(), evento) { m -> listo(m.toString()) } })
        if (conExacta) items.add(Item("Fecha y hora exacta…", R.drawable.ic_ui_calendar, activo = actual != null && !AgendaEstilo.esMinutos(actual)) {
            calendarioPicker(fecha) { f -> horaPicker(hora ?: "09:00") { h -> listo("${f}T$h") } }
        })
        menu(if (evento) "Recordatorio" else "Recordarme", items, if (fecha == null && !evento) "Sin fecha: cuenta desde hoy a las 9:00" else null)
    }

    /** AGE-20 · «Aviso personalizado»: hours and minutes before, with +15 min, +1 h and +12 h. */
    private fun avisoPersonalizado(fecha: String?, hora: String?, inicial: Int?, evento: Boolean, listo: (Int) -> Unit) {
        val (dialog, caja) = hoja()
        var total = (inicial ?: 30).coerceAtLeast(0)
        caja.addView(ui.text("Aviso personalizado", 17f, ui.ink, semibold = true).apply { setPadding(dp(4), 0, 0, dp(10)) })
        val resumen = ui.text("", 13.5f, ui.muted).apply { setPadding(dp(4), dp(10), dp(4), dp(14)) }
        val filas = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun pintar() {
            filas.removeAllViews()
            fun paso(titulo: String, valor: String, menos: () -> Unit, mas: () -> Unit) = filas.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(ui.text(titulo, 15f, ui.ink), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(pastilla("−", false) { menos(); pintar() }.apply { contentDescription = "Menos $titulo" }, LinearLayout.LayoutParams(dp(44), dp(40)))
                addView(ui.text(valor, 16f, ui.ink, semibold = true).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(dp(84), ViewGroup.LayoutParams.WRAP_CONTENT))
                addView(pastilla("+", false) { mas(); pintar() }.apply { contentDescription = "Más $titulo" }, LinearLayout.LayoutParams(dp(44), dp(40)))
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)))
            paso("Horas antes", "${total / 60} h", { total = maxOf(0, total - 60) }, { total += 60 })
            paso("Minutos antes", "${total % 60} min", { total = maxOf(0, total - 5) }, { total += 5 })
            val rapidos = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(6), 0, 0) }
            for ((t, m) in listOf("+15 min" to 15, "+1 h" to 60, "+12 h" to 720)) rapidos.addView(pastilla(t, false) { total += m; pintar() },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(34)).apply { marginEnd = dp(8) })
            filas.addView(rapidos)
            val antes = AgendaEstilo.minutosTexto(total).replaceFirstChar { it.lowercase() }
            resumen.text = if (fecha != null) {
                val base = AgendaStore.calendarioDe(fecha, hora ?: "09:00")!!.timeInMillis
                val aviso = AgendaStore.calendario().apply { timeInMillis = base - total * 60_000L }
                "Te avisa el " + SimpleDateFormat("EEEE d 'a las' h:mm a", es).apply { timeZone = AgendaStore.TZ }.format(aviso.time) +
                    " · $antes " + (if (evento) "del evento" else "de la tarea") + (hora?.let { " de las " + horaBonita(fecha, it) } ?: "") + "."
            } else "Te avisa $antes."
        }
        pintar()
        caja.addView(filas)
        caja.addView(resumen)
        val botones = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END or Gravity.CENTER_VERTICAL }
        botones.addView(pastilla("Cancelar", false) { dialog.dismiss() }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(42)).apply { marginEnd = dp(8) })
        botones.addView(ui.text("Listo", 15f, sobreGrafito, semibold = true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(22), 0, dp(22), 0)
            background = round(grafito, 999)
            isClickable = true
            isFocusable = true
            setOnClickListener { AgendaStore.guardarAvisoPropio(this@AgendaActivity, total); dialog.dismiss(); listo(total) }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(42)))
        caja.addView(botones)
        dialog.show()
    }

    private val repeticiones = listOf("diario" to "Cada día", "laborables" to "Días laborables", "semanal" to "Cada semana", "mensual" to "Cada mes", "anual" to "Cada año")
    private fun textoRepetir(r: String) = repeticiones.firstOrNull { it.first == r }?.second ?: "Se repite"

    private fun elegirRepetir(actual: String?, listo: (Any) -> Unit) {
        menu("Repetir", repeticiones.map { (v, txt) -> Item(txt, R.drawable.ic_ag_repeat, activo = actual == v) { listo(v) } } +
            listOf(Item("No repetir", R.drawable.ic_ag_repeat, activo = actual == null) { listo(JSONObject.NULL) }))
    }

    /** AGE-16 · the label is a calendar: Sin etiqueta, each label with its colour, «Nueva etiqueta…». */
    private fun elegirEtiqueta(actual: String?, listo: (Any) -> Unit) {
        val d = datos()
        val items = mutableListOf<Item?>(Item("Sin etiqueta", R.drawable.ic_ag_tag, activo = actual == null) { listo(JSONObject.NULL) })
        for (s in d.secciones) {
            val cal = AgendaEstilo.calDeTarea(AgendaStore.Tarea(JSONObject().put("seccion_id", s.id)), d.secMap, d.cals)
            items.add(Item(s.nombre, R.drawable.ic_ag_tag, activo = actual == s.id, punto = cal?.color?.de(ui.night)) { listo(s.id) })
        }
        items.add(null)
        items.add(Item("Nueva etiqueta…", R.drawable.ic_ag_plus) { nuevaEtiqueta { id -> listo(id) } })
        menu("Etiqueta", items, "Las etiquetas son tus calendarios")
    }

    /**
     * AGE-17 · «Nueva etiqueta = nuevo calendario»: name and one of the 13 colours. Creating the label creates its
     * calendar in Open WebUI, so it needs the connection with the PC (the label's id is «cal-<calendar id>»).
     */
    private fun nuevaEtiqueta(listo: (String) -> Unit) {
        val (dialog, caja) = hoja()
        var color = AgendaEstilo.PALETA[1]
        caja.addView(ui.text("Nueva etiqueta", 17f, ui.ink, semibold = true).apply { setPadding(dp(4), 0, 0, dp(8)) })
        val campo = EditText(this).apply {
            hint = "Nombre (Trabajo, Casa…)"
            setTextColor(ui.ink); setHintTextColor(ui.hintColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            background = round(ui.tile, 12)
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        caja.addView(campo)
        val nombreColor = ui.text("", 13.5f, ui.muted, semibold = true).apply { setPadding(dp(4), dp(14), 0, dp(8)) }
        caja.addView(nombreColor)
        val muestras = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        caja.addView(muestras)
        val aviso = ui.text("Crea también un calendario con ese nombre y color.", 12.5f, ui.muted).apply { setPadding(dp(4), dp(10), dp(4), dp(12)) }
        caja.addView(aviso)
        fun pintar() {
            nombreColor.text = "Color · ${color.nombre}"
            muestras.removeAllViews()
            AgendaEstilo.PALETA.chunked(7).forEach { fila ->
                muestras.addView(LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    fila.forEach { c ->
                        val on = c == color
                        addView(FrameLayout(this@AgendaActivity).apply {
                            isClickable = true
                            isFocusable = true
                            contentDescription = c.nombre + if (on) ", seleccionado" else ""
                            addView(View(this@AgendaActivity).apply {
                                background = GradientDrawable().apply {
                                    shape = GradientDrawable.OVAL; setColor(c.de(ui.night))
                                    if (on) setStroke(dp(2.5f), grafito)
                                }
                            }, FrameLayout.LayoutParams(dp(34), dp(34), Gravity.CENTER))
                            if (on) addView(ImageView(this@AgendaActivity).apply {
                                setImageResource(R.drawable.ic_ag_check_bold); imageTintList = ColorStateList.valueOf(Color.WHITE)
                            }, FrameLayout.LayoutParams(dp(15), dp(15), Gravity.CENTER))
                            setOnClickListener { color = c; pintar() }
                        }, LinearLayout.LayoutParams(dp(44), dp(44)))
                    }
                })
            }
        }
        pintar()
        val botones = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END or Gravity.CENTER_VERTICAL }
        botones.addView(pastilla("Cancelar", false) { dialog.dismiss() }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(42)).apply { marginEnd = dp(8) })
        val crear = ui.text("Crear etiqueta", 15f, sobreGrafito, semibold = true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(20), 0, dp(20), 0)
            background = round(grafito, 999)
            isClickable = true
            isFocusable = true
        }
        crear.setOnClickListener {
            val nombre = campo.text.toString().trim()
            if (nombre.isEmpty()) { campo.error = "Escribe el nombre"; return@setOnClickListener }
            crear.isEnabled = false
            crear.text = "Creando…"
            val elegido = color
            Thread {
                val cal = runCatching { crearCalendario(nombre.take(80), elegido.hexClaro) }.getOrNull()
                runOnUiThread {
                    if (isFinishing) return@runOnUiThread
                    if (cal == null) {
                        crear.isEnabled = true
                        crear.text = "Crear etiqueta"
                        aviso.setTextColor(red)
                        aviso.text = "No se pudo crear la etiqueta. Necesitas conexión con tu PC, porque cada etiqueta es un calendario."
                        return@runOnUiThread
                    }
                    AgendaStore.guardarCalendario(this, cal)
                    val sid = "cal-" + cal.optString("id")
                    val orden = (AgendaStore.secciones(this).maxOfOrNull { it.orden } ?: 0.0) + 1
                    AgendaStore.cambiarSeccion(this, sid, JSONObject().put("nombre", cal.optString("name", nombre)).put("color", elegido.hexClaro)
                        .put("orden", orden).put("creada", System.currentTimeMillis()).put("borrada", JSONObject.NULL))
                    AgendaSync.pronto(this)
                    dialog.dismiss()
                    listo(sid)
                }
            }.start()
        }
        botones.addView(crear, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(42)))
        caja.addView(botones)
        dialog.show()
    }

    /** POST /api/v1/calendars/create with the session of the app; the new calendar, or null. */
    private fun crearCalendario(nombre: String, color: String): JSONObject? {
        val (base, token) = ChatModels.session(this)
        val c = URL("$base/api/v1/calendars/create").openConnection() as HttpURLConnection
        return try {
            c.requestMethod = "POST"
            c.connectTimeout = 8_000
            c.readTimeout = 15_000
            c.doOutput = true
            c.setRequestProperty("Authorization", "Bearer $token")
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(JSONObject().put("name", nombre).put("color", color).toString().toByteArray()) }
            if (c.responseCode != 200) return null
            JSONObject(c.inputStream.bufferedReader().readText()).let { r ->
                JSONObject().put("id", r.optString("id")).put("name", r.optString("name", nombre)).put("color", r.optString("color", color))
                    .put("is_default", r.optBoolean("is_default")).takeIf { r.optString("id").isNotEmpty() }
            }
        } finally {
            c.disconnect()
        }
    }

    private fun calendarioPicker(actual: String?, listo: (String) -> Unit) {
        val c = actual?.let { AgendaStore.calendarioDe(it) } ?: AgendaStore.calendario()
        DatePickerDialog(this, { _, y, m, d ->
            listo(String.format(Locale.US, "%04d-%02d-%02d", y, m + 1, d))
        }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show()
    }

    private fun horaPicker(actual: String?, listo: (String) -> Unit) {
        val p = (actual ?: "09:00").split(":").mapNotNull { it.toIntOrNull() }
        TimePickerDialog(this, { _, h, m -> listo(String.format(Locale.US, "%02d:%02d", h, m)) }, p.getOrElse(0) { 9 }, p.getOrElse(1) { 0 }, false).show()
    }

    // ── Pieces ──

    /** A bottom sheet: rounded top, the page dimmed behind. */
    private fun hoja(scrollable: Boolean = false): Pair<Dialog, LinearLayout> {
        val dialog = Dialog(this)
        val caja = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(10), dp(18), dp(24))
            addView(View(this@AgendaActivity).apply { background = round(if (ui.night) 0xFF3F3F46.toInt() else 0xFFD4D4D8.toInt(), 999) },
                LinearLayout.LayoutParams(dp(40), dp(4)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(14) })
        }
        val fondo = GradientDrawable().apply {
            setColor(cardBg)
            cornerRadii = floatArrayOf(dp(24f).toFloat(), dp(24f).toFloat(), dp(24f).toFloat(), dp(24f).toFloat(), 0f, 0f, 0f, 0f)
        }
        val vista: View = if (scrollable) ScrollView(this).apply { addView(caja); background = fondo } else caja.apply { background = fondo }
        dialog.setContentView(vista)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setGravity(Gravity.BOTTOM)
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setDimAmount(0.32f)
            if (scrollable) attributes = attributes.apply { height = (resources.displayMetrics.heightPixels * 0.86).toInt() }
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        return dialog to caja
    }

    private fun opcion(icono: Int, texto: String, activa: Boolean, peligro: Boolean = false, punto: Int? = null, accion: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(8), 0, dp(8), 0)
        isClickable = true
        isFocusable = true
        contentDescription = texto + if (activa) ", activo" else ""
        val tinta = when { peligro -> red; activa -> blue; else -> ui.muted }
        if (punto != null) addView(View(this@AgendaActivity).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(punto) }
        }, LinearLayout.LayoutParams(dp(12), dp(12)).apply { marginStart = dp(4); marginEnd = dp(18) })
        else addView(ImageView(this@AgendaActivity).apply { setImageResource(icono); imageTintList = ColorStateList.valueOf(tinta) },
            LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginEnd = dp(14) })
        addView(ui.text(texto, 15.5f, when { peligro -> red; activa -> blue; else -> ui.ink }, semibold = activa || peligro),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (activa && !peligro) addView(ImageView(this@AgendaActivity).apply { setImageResource(R.drawable.ic_ui_check); imageTintList = ColorStateList.valueOf(blue) },
            LinearLayout.LayoutParams(dp(18), dp(18)))
        setOnClickListener { accion() }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50))
    }

    private fun pastilla(texto: String, on: Boolean, punto: Int? = null, accion: () -> Unit): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        setPadding(dp(13), 0, dp(13), 0)
        background = if (on) round(conAlfa(grafito, if (ui.night) 0.14f else 0.08f), 999, grafito) else round(Color.TRANSPARENT, 999, line)
        isClickable = true
        isFocusable = true
        contentDescription = texto + if (on) ", seleccionado" else ""
        if (punto != null) addView(View(this@AgendaActivity).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(punto) }
        }, LinearLayout.LayoutParams(dp(8), dp(8)).apply { marginEnd = dp(6) })
        addView(ui.text(texto, 13.5f, if (on) ui.ink else ui.muted, semibold = on).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO })
        setOnClickListener { accion() }
    }

    private fun etiqueta(texto: String, color: Int) = ui.text(texto, 13.5f, color, bold = true).apply {
        setPadding(dp(18), dp(12), dp(16), dp(6))
    }

    /** «Vencidas · 1»: the name in its colour, the count in grey. */
    private fun grupo(nombre: String, n: Int, color: Int) = grupoTexto(nombre, n, color)

    private fun grupoTexto(nombre: String, n: Int, color: Int) = ui.text("", 13.5f, color, bold = true).apply {
        text = SpannableStringBuilder(nombre).apply { conColor(" · $n", ui.muted) }
        setPadding(dp(18), dp(12), dp(16), dp(6))
        contentDescription = "$nombre, $n"
    }

    /** A label's head: its colour's dot, its name and count; folding when [cerrada] is not null. */
    private fun grupoEtiqueta(s: AgendaStore.Seccion?, n: Int, d: Datos, cerrada: Boolean?, alTocar: (() -> Unit)? = null): View {
        val color = s?.let { AgendaEstilo.calDeTarea(AgendaStore.Tarea(JSONObject().put("seccion_id", it.id)), d.secMap, d.cals)?.color?.de(ui.night) }
        val nombre = s?.nombre ?: "Sin clasificar"
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(12), dp(16), dp(6))
            if (cerrada != null) addView(ui.text(if (cerrada) "▸" else "▾", 13f, ui.muted).apply { setPadding(0, 0, dp(8), 0) })
            if (color != null) addView(View(this@AgendaActivity).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }
            }, LinearLayout.LayoutParams(dp(9), dp(9)).apply { marginEnd = dp(8) })
            addView(ui.text("", 13.5f, if (s == null) ui.muted else ui.ink, bold = true).apply {
                text = SpannableStringBuilder(nombre).apply { conColor(" · $n", ui.muted) }
            })
            contentDescription = "$nombre, $n" + when (cerrada) { true -> ", plegada"; false -> ", desplegada"; null -> "" }
            if (alTocar != null) { isClickable = true; isFocusable = true; setOnClickListener { alTocar() } }
        }
    }

    private fun vacio(texto: String) = ui.text(texto, 14.5f, ui.muted).apply {
        gravity = Gravity.CENTER
        setPadding(dp(32), dp(22), dp(32), dp(22))
    }

    private fun iconButton(icono: Int, descripcion: String, color: Int, accion: () -> Unit) = FrameLayout(this).apply {
        isClickable = true
        isFocusable = true
        contentDescription = descripcion
        addView(ImageView(this@AgendaActivity).apply { setImageResource(icono); imageTintList = ColorStateList.valueOf(color) },
            FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER))
        setOnClickListener { accion() }
        layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
    }

    private fun round(color: Int, radiusDp: Int, stroke: Int? = null) = GradientDrawable().apply {
        cornerRadius = dp(radiusDp).toFloat().coerceAtMost(dp(200).toFloat())
        setColor(color)
        if (stroke != null) setStroke(dp(1), stroke)
    }

    private fun conAlfa(c: Int, a: Float) = (c and 0xFFFFFF) or ((a * 255).toInt().coerceIn(0, 255) shl 24)

    private fun SpannableStringBuilder.sep() { if (isNotEmpty()) append(" · ") }

    private fun SpannableStringBuilder.conColor(texto: String, color: Int, bold: Boolean = false) {
        val ini = length
        append(texto)
        setSpan(ForegroundColorSpan(color), ini, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (bold) setSpan(StyleSpan(Typeface.BOLD), ini, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    private fun SpannableStringBuilder.icono(res: Int, color: Int) {
        val dr = getDrawable(res)?.mutate() ?: return
        dr.setTint(color)
        dr.setBounds(0, 0, dp(12), dp(12))
        val ini = length
        append("  ")
        setSpan(ImageSpan(dr, if (Build.VERSION.SDK_INT >= 29) ImageSpan.ALIGN_CENTER else ImageSpan.ALIGN_BASELINE), ini, ini + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    private fun dp(v: Int) = ui.dp(v)
    private fun dp(v: Float) = ui.dp(v)

    // ── Dates (Mexico City) ──

    private val fmtDia by lazy { SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = AgendaStore.TZ } }
    private val fmtHora by lazy { SimpleDateFormat("HH:mm", Locale.US).apply { timeZone = AgendaStore.TZ } }

    /** The day an event belongs to: the Mexico City day of its start, as on the web. */
    private fun fechaEv(e: AgendaStore.Evento): String = fechaDeMs(e.inicioMs)
    private fun fechaDeMs(ms: Long): String = fmtDia.format(java.util.Date(ms))
    private fun hhmm(ms: Long): String = fmtHora.format(java.util.Date(ms))

    private fun sumarDias(f: String, n: Int): String =
        AgendaStore.fechaDe((AgendaStore.calendarioDe(f) ?: AgendaStore.calendario()).apply { add(Calendar.DAY_OF_MONTH, n) })

    private fun diasEntre(a: String, b: String): Long {
        val x = AgendaStore.calendarioDe(a)?.timeInMillis ?: return 0
        val y = AgendaStore.calendarioDe(b)?.timeInMillis ?: return 0
        return Math.round((y - x) / 86_400_000.0)
    }

    private fun minutosEntre(a: String, b: String): Int {
        fun m(h: String) = h.split(":").mapNotNull { it.toIntOrNull() }.let { it.getOrElse(0) { 0 } * 60 + it.getOrElse(1) { 0 } }
        return (m(b) - m(a)).let { if (it <= 0) 60 else it }
    }

    private fun sumarMinutos(h: String, min: Int): String {
        val p = h.split(":").mapNotNull { it.toIntOrNull() }
        val t = ((p.getOrElse(0) { 0 } * 60 + p.getOrElse(1) { 0 } + min) % (24 * 60) + 24 * 60) % (24 * 60)
        return String.format(Locale.US, "%02d:%02d", t / 60, t % 60)
    }

    private fun fechaLarga(c: Calendar): String =
        SimpleDateFormat("EEEE, d 'de' MMMM", es).apply { timeZone = AgendaStore.TZ }.format(c.time)

    /** «Hoy», «Mañana», «Ayer», «Jue 26» within a week, «18 nov · hace 6 días» further back. */
    private fun fechaRel(f: String): String {
        val hoy = AgendaStore.hoy()
        if (f == hoy) return "Hoy"
        if (f == sumarDias(hoy, 1)) return "Mañana"
        if (f == sumarDias(hoy, -1)) return "Ayer"
        val c = AgendaStore.calendarioDe(f) ?: return f
        val dias = diasEntre(hoy, f)
        if (kotlin.math.abs(dias) <= 6) return SimpleDateFormat("EEE d", es).apply { timeZone = AgendaStore.TZ }.format(c.time).replace(".", "").replaceFirstChar { it.uppercase() }
        val corta = SimpleDateFormat("d MMM", es).apply { timeZone = AgendaStore.TZ }.format(c.time).replace(".", "") +
            if (f.take(4) != hoy.take(4)) " " + f.take(4) else ""
        return if (dias < 0) "$corta · hace ${-dias} días" else corta
    }

    /** Próximos' day heads: «Mañana · jueves 26», «Viernes 27». */
    private fun nombreDia(f: String): String {
        val c = AgendaStore.calendarioDe(f) ?: return f
        val n = SimpleDateFormat("EEEE d", es).apply { timeZone = AgendaStore.TZ }.format(c.time)
        return if (f == sumarDias(AgendaStore.hoy(), 1)) "Mañana · $n" else n.replaceFirstChar { it.uppercase() }
    }

    private fun fechaCorta(f: String): String = fechaRel(f).substringBefore(" · ")

    private fun horaBonita(fecha: String, hora: String): String =
        AgendaStore.calendarioDe(fecha, hora)?.let { horaDe(it.timeInMillis) } ?: hora

    private fun horaDe(ms: Long): String =
        SimpleDateFormat("h:mm a", es).apply { timeZone = AgendaStore.TZ }.format(java.util.Date(ms))
}
