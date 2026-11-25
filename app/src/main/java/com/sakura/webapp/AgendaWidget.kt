package com.sakura.webapp

import android.app.Activity
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * The «Pendientes» of the widgets W3–W10 (Hanami, «AND · App de Android»), from the Agenda kept on the phone, so
 * they work without the PC: the nearest tasks first, each with its date and reminder; ticking one completes it
 * without opening the app; the ones changed and not sent yet say «por enviar».
 */
object AgendaWidget {
    const val EXTRA_ACCION = "com.sakura.webapp.widget.ACCION"
    const val EXTRA_TAREA = "com.sakura.webapp.widget.TAREA"
    const val COMPLETAR = "completar"
    const val ABRIR = "abrir"
    const val NUEVA = "nueva"

    /** Open tasks, nearest first: overdue (the oldest first) and today, then by date and time, then the ones without a date. */
    fun pendientes(context: Context): List<AgendaStore.Tarea> =
        AgendaStore.tareas(context).filter { !it.completada }
            .sortedWith(compareBy({ AgendaEstilo.vence(it) ?: Long.MAX_VALUE }, { !it.importante }, { it.orden }))

    /** Labels and calendars, read once per drawing. */
    class Datos(val secciones: Map<String, AgendaStore.Seccion>, val cals: Map<String, org.json.JSONObject>)

    /** One row of the card (layout widget_task_row); clicks are set by whoever shows it. */
    fun fila(context: Context, t: AgendaStore.Tarea, datos: Datos): RemoteViews {
        val v = RemoteViews(context.packageName, R.layout.widget_task_row)
        v.setTextViewText(R.id.task_title, t.titulo)
        val night = (context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
        val cal = AgendaEstilo.calDeTarea(t, datos.secciones, datos.cals)
        val vencida = AgendaEstilo.estado(t) == AgendaEstilo.Estado.VENCIDA
        // AGE-13: the ring is black (as the web since 24-nov) and the palette's red when it is overdue
        v.setInt(R.id.task_ring, "setColorFilter", if (vencida) AgendaEstilo.rojo(night) else context.getColor(R.color.widget_ink))
        val recordar = t.recordar
        val partes = mutableListOf<String>()
        t.fecha?.let { partes.add((if (vencida) "Vencida · " else "") + fechaCorta(it) + (t.hora?.let { h -> " " + hora(it, h) } ?: "")) }
        if (recordar != null && !vencida) partes.add(recordarCorto(recordar))
        else cal?.let { partes.add(it.nombre) }
        val meta = partes.joinToString(" · ")
        v.setTextViewText(R.id.task_meta, meta)
        v.setViewVisibility(R.id.task_meta, if (meta.isEmpty()) View.GONE else View.VISIBLE)
        v.setViewVisibility(R.id.task_bell, if (recordar != null && !vencida) View.VISIBLE else View.GONE)
        v.setTextColor(R.id.task_meta, when {
            vencida -> AgendaEstilo.rojoTexto(night)
            recordar != null -> context.getColor(R.color.widget_blue)
            else -> context.getColor(R.color.widget_text2)
        })
        val pendiente = AgendaStore.pendiente(context, t.id)
        v.setViewVisibility(R.id.task_pend_dot, if (pendiente) View.VISIBLE else View.GONE)
        v.setViewVisibility(R.id.task_pend, if (pendiente) View.VISIBLE else View.GONE)
        v.setContentDescription(R.id.task_check, "Completar ${t.titulo}" + (if (meta.isNotEmpty()) ", $meta" else "") + (cal?.let { ", ${it.nombre}" } ?: "") + if (pendiente) ", por enviar" else "")
        v.setContentDescription(R.id.task_row, t.titulo)
        return v
    }

    fun secciones(context: Context): Datos =
        Datos(AgendaStore.secciones(context).associateBy { it.id }, AgendaEstilo.calendarios(context))

    /** The action screen (invisible) that completes, opens or adds without a broadcast trampoline. */
    fun accion(context: Context, requestCode: Int, accion: String, tareaId: String? = null): PendingIntent =
        PendingIntent.getActivity(
            context, requestCode,
            Intent(context, AgendaWidgetActionActivity::class.java)
                .setData(Uri.parse("sakura-widget-agenda://$accion/${tareaId ?: ""}"))
                .putExtra(EXTRA_ACCION, accion).putExtra(EXTRA_TAREA, tareaId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    /** Template for the rows of the 4×4 list: each row fills in its action and task. */
    fun plantilla(context: Context, requestCode: Int): PendingIntent =
        PendingIntent.getActivity(
            context, requestCode,
            Intent(context, AgendaWidgetActionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION),
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
        )

    fun relleno(accion: String, tareaId: String): Intent =
        Intent().putExtra(EXTRA_ACCION, accion).putExtra(EXTRA_TAREA, tareaId)
            .setData(Uri.parse("sakura-widget-agenda://$accion/$tareaId"))

    /** The model's photo in grey, for «Sin conexión con tu PC». */
    fun gris(bitmap: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0.3f) })
            alpha = 180
        }
        Canvas(out).drawBitmap(bitmap, 0f, 0f, paint)
        return out
    }

    private val es = Locale("es", "MX")

    private fun fechaCorta(f: String): String {
        val hoy = AgendaStore.hoy()
        val manana = AgendaStore.fechaDe(AgendaStore.calendario().apply { add(Calendar.DAY_OF_MONTH, 1) })
        val ayer = AgendaStore.fechaDe(AgendaStore.calendario().apply { add(Calendar.DAY_OF_MONTH, -1) })
        return when (f) {
            hoy -> "Hoy"
            manana -> "Mañana"
            ayer -> "Ayer"
            else -> corta(f)
        }
    }

    private fun corta(f: String): String = AgendaStore.calendarioDe(f)?.let { c ->
        SimpleDateFormat("EEE d", es).apply { timeZone = AgendaStore.TZ }.format(c.time).replace(".", "").replaceFirstChar { it.uppercase() }
    } ?: f

    private fun hora(f: String, h: String): String = AgendaStore.calendarioDe(f, h)?.let {
        SimpleDateFormat("h:mm a", es).apply { timeZone = AgendaStore.TZ }.format(it.time)
    } ?: h

    private fun recordarCorto(r: String): String =
        if (AgendaEstilo.esMinutos(r)) AgendaEstilo.minutosTexto(r.toInt()).replaceFirstChar { it.lowercase() }
        else r.split("T").takeIf { it.size == 2 }?.let { "aviso " + fechaCorta(it[0]).lowercase() + " " + hora(it[0], it[1].take(5)) } ?: "con aviso"
}

/** The scrolling list of the 4×4 widget (W6, W10). */
class AgendaWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory = object : RemoteViewsFactory {
        private var tareas: List<AgendaStore.Tarea> = emptyList()
        private var secciones = AgendaWidget.Datos(emptyMap(), emptyMap())
        override fun onCreate() {}
        override fun onDataSetChanged() {
            tareas = AgendaWidget.pendientes(applicationContext)
            secciones = AgendaWidget.secciones(applicationContext)
        }
        override fun onDestroy() {}
        override fun getCount() = tareas.size
        override fun getViewAt(position: Int): RemoteViews {
            val t = tareas.getOrNull(position) ?: return RemoteViews(packageName, R.layout.widget_task_row)
            return AgendaWidget.fila(applicationContext, t, secciones).apply {
                setOnClickFillInIntent(R.id.task_check, AgendaWidget.relleno(AgendaWidget.COMPLETAR, t.id))
                setOnClickFillInIntent(R.id.task_row, AgendaWidget.relleno(AgendaWidget.ABRIR, t.id))
            }
        }
        override fun getLoadingView(): RemoteViews? = null
        override fun getViewTypeCount() = 1
        override fun getItemId(position: Int) = tareas.getOrNull(position)?.id?.hashCode()?.toLong() ?: position.toLong()
        override fun hasStableIds() = true
    }
}

/**
 * The widget's taps on a task, without showing anything: «completar» marks it done on the phone (and sends it when
 * there is a network); «abrir» and «nueva» open the Agenda on the task or on «Agregar una tarea».
 */
class AgendaWidgetActionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val accion = intent?.getStringExtra(AgendaWidget.EXTRA_ACCION)
        val tarea = intent?.getStringExtra(AgendaWidget.EXTRA_TAREA)
        when (accion) {
            AgendaWidget.COMPLETAR -> if (tarea != null) {
                AgendaStore.completar(this, tarea, true)
                AgendaAlarms.programar(this)
                QuickVoice.refreshAgenda(this)
                AgendaSync.ahora(applicationContext)
            }
            AgendaWidget.ABRIR -> startActivity(AgendaActivity.intent(this, tarea))
            AgendaWidget.NUEVA -> startActivity(AgendaActivity.intent(this).putExtra(AgendaActivity.EXTRA_NUEVA, true))
        }
        finish()
        @Suppress("DEPRECATION") overridePendingTransition(0, 0)
    }
}

/** For VoiceWidgetProvider: whether [id] is a real widget. */
internal fun widgetValido(id: Int) = id != AppWidgetManager.INVALID_APPWIDGET_ID
