package com.sakura.webapp

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.SizeF
import android.view.View
import android.widget.RemoteViews

/** Ways to open voice mode with one tap: the quick settings tile and the home screen widgets. */
object QuickVoice {
    /** The Agenda changed (a sync, a task done from a reminder): the widgets redraw their pending tasks. */
    fun refreshAgenda(context: Context) {
        runCatching { VoiceWidgetProvider.refreshAll(context.applicationContext) }
            .onFailure { android.util.Log.w("SakuraWebApp", "Widget: no se pudo redibujar", it) }
    }

    fun intent(context: Context, model: String? = null): Intent = appIntent(context, IncomingIntents.ACTION_VOICE, model)

    fun appIntent(context: Context, action: String, model: String? = null): Intent =
        Intent(context, MainActivity::class.java)
            .setAction(action)
            .apply { if (model != null) putExtra(IncomingIntents.EXTRA_MODEL, model) }
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)

    fun pendingIntent(context: Context, requestCode: Int, model: String? = null): PendingIntent =
        activity(context, requestCode, intent(context, model))

    fun activity(context: Context, requestCode: Int, intent: Intent): PendingIntent =
        PendingIntent.getActivity(
            context, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
}

/** "Hablar con Sakura" in the quick settings panel. */
class VoiceTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply {
            state = Tile.STATE_INACTIVE
            label = getString(R.string.tile_label)
            updateTile()
        }
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    override fun onClick() {
        super.onClick()
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(QuickVoice.pendingIntent(this, 10))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(QuickVoice.intent(this))
        }
    }
}

/**
 * Home screen widget, after «AND · App de Android» W1–W12 (Hanami, 24-nov-2026). Glass over the wallpaper, an opaque
 * field to write and the graphite mic; the model's photo and name ⌄ open «Elegir modelo». By height: W1 4×1 (field
 * and mic), W2 4×2 (name, description and field), W3 4×3 (+ the nearest pending task, or W4 «Nada pendiente hoy»),
 * W5/W6 4×4 (pending tasks that scroll + Nuevo chat · Agenda · Notas). Without the PC only the Agenda works, and the
 * widget says so (W7–W10): grey photo and «Sin conexión con tu PC», «Reintentar», the mic and the chat off, the
 * pending tasks still ticking and adding, «N por enviar», and in 4×4 only Agenda active with a blue border.
 */
class VoiceWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { update(context, manager, it) }
        ChatModels.refresh(context)
        PcStatus.check(context)
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) {
        update(context, manager, id)
    }

    override fun onDeleted(context: Context, ids: IntArray) {
        ids.forEach { ChatModels.forget(context, it) }
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_RETRY -> {
                PcStatus.check(context, force = true)
                AgendaSync.ahora(context)
            }
            else -> super.onReceive(context, intent)
        }
    }

    companion object {
        const val EXTRA_MODEL_NAME = "model_name"
        private const val ACTION_RETRY = "com.sakura.webapp.widget.RETRY"

        /** Redraws every widget, e.g. after the list of models or the Agenda changed. */
        fun refreshAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            val ids = manager.getAppWidgetIds(ComponentName(context, VoiceWidgetProvider::class.java))
            ids.forEach { update(context, manager, it) }
            if (ids.isNotEmpty()) {
                @Suppress("DEPRECATION")
                runCatching { manager.notifyAppWidgetViewDataChanged(ids, R.id.widget_task_list) }
            }
        }

        /**
         * Android 12 and later get the four layouts with the smallest size each needs, and the launcher shows the
         * largest that fits the widget's real size. Older versions choose here from the reported height.
         */
        fun update(context: Context, manager: AppWidgetManager, id: Int) {
            val w = Widget(context, id)
            val views = if (Build.VERSION.SDK_INT >= 31) {
                RemoteViews(linkedMapOf(
                    SizeF(180f, 50f) to w.w1(),
                    SizeF(180f, 140f) to w.w2(),
                    SizeF(180f, 250f) to w.w3(),
                    SizeF(180f, 360f) to w.w4(),
                ))
            } else {
                val options = manager.getAppWidgetOptions(id)
                val height = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT) ?: 0
                when {
                    height == 0 -> w.w3()
                    height < 140 -> w.w1()
                    height < 250 -> w.w2()
                    height < 360 -> w.w3()
                    else -> w.w4()
                }
            }
            manager.updateAppWidget(id, views)
        }

        /** Builds the widget's layouts; the photos are drawn once and shared by all of them. */
        private class Widget(val context: Context, val id: Int) {
            val model = ChatModels.forWidget(context, id)
            val several = ChatModels.available(context).size > 1
            val offline = PcStatus.isOffline(context)
            private val density = context.resources.displayMetrics.density
            // Request codes are unique per widget and button, so each keeps its own PendingIntent.
            val code = id * 32
            val picker = QuickVoice.activity(context, code + 11, WidgetModelPickerActivity.intent(context, id))
            val chat = QuickVoice.activity(
                context, code + 1,
                QuickVoice.appIntent(context, IncomingIntents.ACTION_NEW_CHAT, model.id).putExtra(IncomingIntents.EXTRA_FOCUS, true)
                    .setData(Uri.parse("sakura-widget://$id/chat/${model.id}"))
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id).putExtra(EXTRA_MODEL_NAME, model.name)
            )
            val agenda = QuickVoice.activity(context, code + 12, AgendaActivity.intent(context))
            val retry = PendingIntent.getBroadcast(
                context, code + 14,
                Intent(context, VoiceWidgetProvider::class.java).setAction(ACTION_RETRY).setData(Uri.parse("sakura-widget://$id/retry")),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            private val avatars = HashMap<Int, Bitmap>()
            private fun avatar(dp: Int): Bitmap = avatars.getOrPut(dp) {
                val b = ChatModels.avatar(context, model, (dp * density).toInt(), ring = true)
                if (offline) AgendaWidget.gris(b) else b
            }
            private val descripcion = ChatModels.descripcion(context, model.id)

            /** Photo, name ⌄, description, mic and field: what W2–W10 share. */
            private fun cabecera(views: RemoteViews, avatarDp: Int) {
                views.setImageViewBitmap(R.id.widget_avatar, avatar(avatarDp))
                views.setTextViewText(R.id.widget_model_name, ChatModels.shortName(model))
                views.setViewVisibility(R.id.widget_chev, if (several && !offline) View.VISIBLE else View.GONE)
                views.setTextColor(R.id.widget_model_name, context.getColor(if (offline) R.color.widget_text2 else R.color.widget_ink))
                views.setTextViewText(R.id.widget_model_desc, descripcion ?: "")
                views.setViewVisibility(R.id.widget_model_desc, if (!offline && !descripcion.isNullOrBlank()) View.VISIBLE else View.GONE)
                views.setViewVisibility(R.id.widget_off_row, if (offline) View.VISIBLE else View.GONE)
                views.setOnClickPendingIntent(R.id.widget_model, if (offline) retry else if (several) picker else chat)
                views.setContentDescription(R.id.widget_model,
                    if (offline) "${model.name}. Sin conexión con tu PC. Reintentar"
                    else context.getString(if (several) R.string.widget_change_model else R.string.widget_open_chat, model.name))
                microfono(views)
                views.setViewVisibility(R.id.widget_field, if (offline) View.GONE else View.VISIBLE)
                views.setViewVisibility(R.id.widget_offline, if (offline) View.VISIBLE else View.GONE)
                if (offline) views.setOnClickPendingIntent(R.id.widget_retry, retry)
                else {
                    views.setOnClickPendingIntent(R.id.widget_field, chat)
                    views.setTextViewText(R.id.widget_hint, "Escríbele a ${ChatModels.shortName(model)}…")
                    views.setContentDescription(R.id.widget_field, "Escribir a ${model.name}")
                }
            }

            private fun microfono(views: RemoteViews) {
                if (offline) {
                    // off rather than failing in silence; a tap checks the PC again
                    views.setInt(R.id.widget_mic, "setBackgroundResource", R.drawable.widget_w_mic_off)
                    views.setInt(R.id.widget_mic, "setColorFilter", context.getColor(R.color.widget_check_ring))
                    views.setOnClickPendingIntent(R.id.widget_mic, retry)
                    views.setContentDescription(R.id.widget_mic, "Hablar (sin conexión)")
                } else {
                    views.setInt(R.id.widget_mic, "setBackgroundResource", R.drawable.widget_w_mic)
                    views.setInt(R.id.widget_mic, "setColorFilter", context.getColor(R.color.widget_on_mic))
                    views.setOnClickPendingIntent(R.id.widget_mic, QuickVoice.pendingIntent(context, code, model.id))
                    views.setContentDescription(R.id.widget_mic, context.getString(R.string.widget_talk_to, model.name))
                }
            }

            /** W1 · 4×1, or W7 offline: the whole pill opens the Agenda. */
            fun w1(): RemoteViews = RemoteViews(context.packageName, R.layout.widget_w1).apply {
                setViewVisibility(R.id.widget_field, if (offline) View.GONE else View.VISIBLE)
                setViewVisibility(R.id.widget_off_pill, if (offline) View.VISIBLE else View.GONE)
                if (offline) {
                    setImageViewBitmap(R.id.widget_off_avatar, avatar(42))
                    setOnClickPendingIntent(R.id.widget_off_pill, agenda)
                } else {
                    setImageViewBitmap(R.id.widget_avatar, avatar(42))
                    setViewVisibility(R.id.widget_chev, if (several) View.VISIBLE else View.GONE)
                    setOnClickPendingIntent(R.id.widget_model, if (several) picker else chat)
                    setContentDescription(R.id.widget_model, context.getString(if (several) R.string.widget_change_model else R.string.widget_open_chat, model.name))
                    setTextViewText(R.id.widget_hint, "Escríbele a ${ChatModels.shortName(model)}…")
                    setOnClickPendingIntent(R.id.widget_hint, chat)
                    setContentDescription(R.id.widget_hint, "Escribir a ${model.name}")
                }
                microfono(this)
            }

            /** W2 · 4×2, or W8. */
            fun w2(): RemoteViews = RemoteViews(context.packageName, R.layout.widget_w2).apply { cabecera(this, 50) }

            /** W3 / W4 · 4×3 with the nearest task, or W9. */
            fun w3(): RemoteViews = RemoteViews(context.packageName, R.layout.widget_w3).apply {
                cabecera(this, 50)
                val tareas = tarjeta(this)
                removeAllViews(R.id.widget_tasks)
                tareas.firstOrNull()?.let { t ->
                    val fila = AgendaWidget.fila(context, t, AgendaWidget.secciones(context))
                    fila.setOnClickPendingIntent(R.id.task_check, AgendaWidget.accion(context, code + 20, AgendaWidget.COMPLETAR, t.id))
                    fila.setOnClickPendingIntent(R.id.task_row, AgendaWidget.accion(context, code + 21, AgendaWidget.ABRIR, t.id))
                    addView(R.id.widget_tasks, fila)
                }
            }

            /** W5 / W6 · 4×4: the tasks scroll inside the widget, then Nuevo chat · Agenda · Notas; or W10. */
            fun w4(): RemoteViews = RemoteViews(context.packageName, R.layout.widget_w4).apply {
                cabecera(this, 50)
                val tareas = tarjeta(this)
                setViewVisibility(R.id.widget_task_list, if (tareas.isEmpty()) View.GONE else View.VISIBLE)
                @Suppress("DEPRECATION")
                setRemoteAdapter(R.id.widget_task_list,
                    Intent(context, AgendaWidgetService::class.java).setData(Uri.parse("sakura-widget://$id/tareas")))
                setPendingIntentTemplate(R.id.widget_task_list, AgendaWidget.plantilla(context, code + 22))
                setContentDescription(R.id.widget_task_list, "Pendientes, ${tareas.size}")
                setOnClickPendingIntent(R.id.widget_calendar, agenda)
                setInt(R.id.widget_calendar, "setBackgroundResource", if (offline) R.drawable.widget_w_action_on else R.drawable.widget_w_action)
                val off = context.getColor(R.color.widget_check_ring)
                val ink = context.getColor(R.color.widget_ink)
                for ((cell, icon, label) in listOf(
                    Triple(R.id.widget_new_chat, R.id.widget_new_chat_icon, R.id.widget_new_chat_label),
                    Triple(R.id.widget_notes, R.id.widget_notes_icon, R.id.widget_notes_label),
                )) {
                    setInt(icon, "setColorFilter", if (offline) off else ink)
                    setTextColor(label, if (offline) off else ink)
                    if (offline) {
                        setOnClickPendingIntent(cell, retry)
                        setContentDescription(cell, (if (cell == R.id.widget_new_chat) "Nuevo chat" else "Notas") + ", no disponible sin conexión")
                    }
                }
                if (!offline) {
                    setOnClickPendingIntent(R.id.widget_new_chat, chat)
                    setOnClickPendingIntent(R.id.widget_notes, QuickVoice.activity(context, code + 13, QuickVoice.appIntent(context, IncomingIntents.ACTION_NOTES)))
                }
                setInt(R.id.widget_calendar_icon, "setColorFilter", if (offline) context.getColor(R.color.widget_blue) else ink)
                setTextColor(R.id.widget_calendar_label, if (offline) context.getColor(R.color.widget_blue) else ink)
            }

            /** «Pendientes · N», «N por enviar», «+ Tarea» and the empty state (W4); returns the open tasks. */
            private fun tarjeta(views: RemoteViews): List<AgendaStore.Tarea> {
                val tareas = AgendaWidget.pendientes(context)
                val porEnviar = AgendaStore.pendientes(context)
                views.setTextViewText(R.id.widget_pend_count, " · ${tareas.size}")
                views.setViewVisibility(R.id.widget_pend_count, if (tareas.isEmpty()) View.GONE else View.VISIBLE)
                views.setTextViewText(R.id.widget_pend_chip, "$porEnviar por enviar")
                views.setViewVisibility(R.id.widget_pend_chip, if (porEnviar > 0) View.VISIBLE else View.GONE)
                views.setContentDescription(R.id.widget_pend_title, "Pendientes, ${tareas.size}" + if (porEnviar > 0) ", $porEnviar por enviar" else "")
                val nueva = AgendaWidget.accion(context, code + 23, AgendaWidget.NUEVA)
                views.setOnClickPendingIntent(R.id.widget_add, nueva)
                views.setViewVisibility(R.id.widget_empty, if (tareas.isEmpty()) View.VISIBLE else View.GONE)
                if (tareas.isEmpty()) {
                    views.setOnClickPendingIntent(R.id.widget_empty_add, nueva)
                    views.setViewVisibility(R.id.widget_plan, if (offline) View.GONE else View.VISIBLE)
                    views.setOnClickPendingIntent(R.id.widget_plan, QuickVoice.activity(context, code + 24,
                        QuickVoice.appIntent(context, IncomingIntents.ACTION_PLAN_DAY, model.id)))
                }
                return tareas
            }
        }

        /** Called by MainActivity for a chat opened from a widget: remember the model and redraw it. */
        fun remember(context: Context, intent: Intent) {
            val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            val model = intent.getStringExtra(IncomingIntents.EXTRA_MODEL) ?: return
            if (id == AppWidgetManager.INVALID_APPWIDGET_ID) return
            ChatModels.setForWidget(context, id, ChatModels.find(model, intent.getStringExtra(EXTRA_MODEL_NAME) ?: model))
            update(context, AppWidgetManager.getInstance(context), id)
        }
    }
}

/**
 * Whether the PC (Open WebUI) answers, for the widgets' «Tu PC no responde». Checked in the background when
 * the widgets update (at most every two minutes) and on «Reintentar»; the app marks it as answering when a
 * page of the server loads. Any HTTP answer counts: only a network failure means the PC is off or unreachable.
 */
object PcStatus {
    private const val PREFS = "pc_status"
    private const val KEY_OFFLINE = "offline"
    private const val KEY_AT = "checked_at"
    private const val EVERY_MS = 2 * 60 * 1000L
    @Volatile private var checking = false

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isOffline(context: Context) = prefs(context).getBoolean(KEY_OFFLINE, false)

    /** The PC answered within the last [ms] (a check or a page of the server): «online» is not a stale guess. */
    fun answeredRecently(context: Context, ms: Long = EVERY_MS): Boolean =
        !isOffline(context) && System.currentTimeMillis() - prefs(context).getLong(KEY_AT, 0) < ms

    fun markOnline(context: Context) = set(context.applicationContext, offline = false)

    private fun set(context: Context, offline: Boolean) {
        val p = prefs(context)
        val changed = p.getBoolean(KEY_OFFLINE, false) != offline
        p.edit().putBoolean(KEY_OFFLINE, offline).putLong(KEY_AT, System.currentTimeMillis()).apply()
        if (changed) VoiceWidgetProvider.refreshAll(context)
    }

    fun check(context: Context, force: Boolean = false) {
        val app = context.applicationContext
        if (checking || (!force && System.currentTimeMillis() - prefs(app).getLong(KEY_AT, 0) < EVERY_MS)) return
        checking = true
        Thread {
            try {
                ServerAddress.load(app)
                val bases = listOfNotNull(ServerAddress.current.ifEmpty { null }, ServerAddress.local, ServerAddress.exterior).distinct()
                if (bases.isEmpty()) return@Thread
                set(app, offline = bases.none { answers(it) })
            } finally {
                checking = false
            }
        }.start()
    }

    /** Blocking: does the server at [base] answer at all within 4 s? (Any HTTP status counts.) */
    fun answersNow(base: String): Boolean = answers(base)

    private fun answers(base: String): Boolean = runCatching {
        val connection = java.net.URL(base.trimEnd('/') + "/health").openConnection() as java.net.HttpURLConnection
        try {
            connection.connectTimeout = 4_000
            connection.readTimeout = 4_000
            connection.responseCode > 0
        } finally {
            connection.disconnect()
        }
    }.getOrDefault(false)
}
