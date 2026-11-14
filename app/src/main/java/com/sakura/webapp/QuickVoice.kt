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
 * Home screen widget, from "App de Android · Parte 1 · Widgets y «Oye, Sakura»" (Hanami). Its real size picks
 * the layout: large (4 × 2): the chosen model's avatar and name with the graphite mic, a row with the other
 * models (none with a single model; tapping one makes it the widget's model; when they do not fit, the last
 * place is «+N» and opens the picker), and Nuevo chat · Agenda · Notas · Último chat. Medium (one row): the
 * mic, the model and Nuevo and Agenda with their names. Small (2 × 2): avatar and mic, the model, Nuevo and
 * Agenda. The model name (▾) opens «¿Con quién quieres hablar?». When the PC does not answer, the mic and the
 * shortcuts switch off and the large widget says why, with «Reintentar» (see [PcStatus]).
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
            ACTION_SELECT -> {
                // A model of the row: it becomes the widget's model and moves to the top.
                val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
                val model = intent.getStringExtra(IncomingIntents.EXTRA_MODEL)
                if (id != AppWidgetManager.INVALID_APPWIDGET_ID && model != null) {
                    ChatModels.setForWidget(context, id, ChatModels.find(model, intent.getStringExtra(EXTRA_MODEL_NAME) ?: model))
                    update(context, AppWidgetManager.getInstance(context), id)
                }
            }
            ACTION_RETRY -> PcStatus.check(context, force = true)
            else -> super.onReceive(context, intent)
        }
    }

    companion object {
        const val EXTRA_MODEL_NAME = "model_name"
        private const val ACTION_SELECT = "com.sakura.webapp.widget.SELECT"
        private const val ACTION_RETRY = "com.sakura.webapp.widget.RETRY"
        private val SLOTS = intArrayOf(
            R.id.widget_slot_0, R.id.widget_slot_1, R.id.widget_slot_2, R.id.widget_slot_3,
            R.id.widget_slot_4, R.id.widget_slot_5, R.id.widget_slot_6,
        )
        /** The shortcuts of each layout, with their icon and name (dimmed while the PC does not answer). */
        private val SHORTCUTS = listOf(
            Triple(R.id.widget_new_chat, R.id.widget_new_chat_icon, R.id.widget_new_chat_label),
            Triple(R.id.widget_calendar, R.id.widget_calendar_icon, R.id.widget_calendar_label),
            Triple(R.id.widget_notes, R.id.widget_notes_icon, R.id.widget_notes_label),
            Triple(R.id.widget_last_chat, R.id.widget_last_chat_icon, R.id.widget_last_chat_label),
        )
        /** Height (dp) the large widget needs to show the row of models (or the notice) as well. */
        private const val ROW_HEIGHT = 196f
        /** Width (dp) of one place in the row: a 48 dp target plus 2 dp between places. */
        private const val PLACE = 50f

        /** Redraws every widget, e.g. after the list of models changed. */
        fun refreshAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            manager.getAppWidgetIds(ComponentName(context, VoiceWidgetProvider::class.java))
                .forEach { update(context, manager, it) }
        }

        /**
         * Android 12 and later get every layout with the smallest size it needs, and the launcher shows
         * the largest one that fits the widget's real size (the sizes launchers report are not always
         * right). Older versions choose here from the reported size.
         */
        fun update(context: Context, manager: AppWidgetManager, id: Int) {
            val w = Widget(context, id)
            val views = if (Build.VERSION.SDK_INT >= 31) {
                val layouts = linkedMapOf<SizeF, RemoteViews>()
                layouts[SizeF(140f, 110f)] = w.small()
                // One row: the mic and the model need about 190 dp; each 62 dp button 68 more.
                for (buttons in 0..2) layouts[SizeF(190f + 68f * buttons, 50f)] = w.medium(buttons)
                layouts[SizeF(250f, 140f)] = w.large(places = 0)
                if (w.offline) {
                    layouts[SizeF(250f, ROW_HEIGHT)] = w.large(places = 0, notice = true)
                } else if (w.others.isNotEmpty()) {
                    // Wider widgets fit more places (4 to 7); a wider layout wins when it fits.
                    for (places in 4..minOf(SLOTS.size, maxOf(4, w.others.size))) {
                        layouts[SizeF(maxOf(250f, 28f + PLACE * places), ROW_HEIGHT)] = w.large(places)
                    }
                }
                RemoteViews(layouts)
            } else {
                // Portrait size in dp (0 before the launcher reports it: the large layout, as placed).
                val options = manager.getAppWidgetOptions(id)
                val width = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH) ?: 0
                val height = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT) ?: 0
                val tall = height == 0 || height >= ROW_HEIGHT.toInt()
                when {
                    height in 1 until 120 -> w.medium(if (width == 0) 2 else ((width - 190) / 68).coerceIn(0, 2))
                    width in 1 until 250 -> w.small()
                    w.offline -> w.large(0, notice = tall)
                    else -> w.large(
                        if (w.others.isEmpty() || !tall) 0
                        else if (width == 0) 5 else ((width - 28) / PLACE.toInt()).coerceIn(2, SLOTS.size)
                    )
                }
            }
            manager.updateAppWidget(id, views)
        }

        /** Builds the widget's layouts; the avatars are drawn once and shared by all of them. */
        private class Widget(val context: Context, val id: Int) {
            val model = ChatModels.forWidget(context, id)
            val models = ChatModels.available(context).let { list -> if (list.any { it.id == model.id }) list else listOf(model) + list }
            val several = models.size > 1
            /** The row shows only the others: the chosen one is already at the top. */
            val others = models.filter { it.id != model.id }
            val offline = PcStatus.isOffline(context)
            private val density = context.resources.displayMetrics.density
            // Request codes are unique per widget and button, so each keeps its own PendingIntent.
            val code = id * 32
            val picker = QuickVoice.activity(context, code + 11, WidgetModelPickerActivity.intent(context, id))
            val chat = chatIntent(context, id, model, code + 10)
            val retry = broadcast(context, code + 14, Intent(context, VoiceWidgetProvider::class.java).setAction(ACTION_RETRY)
                .setData(Uri.parse("sakura-widget://$id/retry")))
            private val avatars = HashMap<Int, Bitmap>()

            private fun avatar(dp: Int) = avatars.getOrPut(dp) { ChatModels.avatar(context, model, (dp * density).toInt()) }

            private fun base(layout: Int, avatarDp: Int, arrow: Int): RemoteViews {
                val views = RemoteViews(context.packageName, layout)
                views.setImageViewBitmap(R.id.widget_avatar, avatar(avatarDp))
                views.setTextViewText(R.id.widget_model_name, ChatModels.shortName(model))
                views.setTextViewCompoundDrawablesRelative(R.id.widget_model_name, 0, 0, if (several) arrow else 0, 0)
                views.setOnClickPendingIntent(R.id.widget_model, if (several) picker else chat)
                views.setContentDescription(
                    R.id.widget_model,
                    context.getString(if (several) R.string.widget_change_model else R.string.widget_open_chat, model.name)
                )
                if (offline) {
                    // Off rather than failing in silence: a ring instead of the graphite mic; a tap checks again.
                    views.setInt(R.id.widget_mic, "setBackgroundResource", R.drawable.widget_mic_off)
                    views.setInt(R.id.widget_mic, "setColorFilter", context.getColor(R.color.widget_off))
                    views.setOnClickPendingIntent(R.id.widget_mic, retry)
                    views.setContentDescription(R.id.widget_mic, context.getString(R.string.widget_mic_off, model.name))
                } else {
                    views.setOnClickPendingIntent(R.id.widget_mic, QuickVoice.pendingIntent(context, code, model.id))
                    views.setContentDescription(R.id.widget_mic, context.getString(R.string.widget_talk_to, model.name))
                    views.setOnClickPendingIntent(
                        R.id.widget_new_chat,
                        QuickVoice.activity(
                            context, code + 1,
                            QuickVoice.appIntent(context, IncomingIntents.ACTION_NEW_CHAT, model.id).putExtra(IncomingIntents.EXTRA_FOCUS, true)
                        )
                    )
                    views.setOnClickPendingIntent(R.id.widget_calendar, page(context, IncomingIntents.ACTION_CALENDAR, code + 12))
                }
                return views
            }

            /** Greys the shortcuts of [views] that the layout has (only while the PC does not answer). */
            private fun dim(views: RemoteViews, cells: Int) {
                if (!offline) return
                val off = context.getColor(R.color.widget_off)
                SHORTCUTS.take(cells).forEach { (_, icon, label) ->
                    views.setInt(icon, "setColorFilter", off)
                    views.setTextColor(label, off)
                }
            }

            fun small(): RemoteViews = base(R.layout.widget_small, 40, R.drawable.widget_arrow_15).apply {
                setOnClickPendingIntent(R.id.widget_avatar, if (offline) retry else chat)
                dim(this, 2)
            }

            /** One row with the buttons by importance: Nuevo, then Agenda. */
            fun medium(buttons: Int): RemoteViews = base(R.layout.widget_medium, 30, R.drawable.widget_arrow_15).apply {
                setViewVisibility(R.id.widget_buttons, if (buttons > 0) View.VISIBLE else View.GONE)
                setViewVisibility(R.id.widget_calendar, if (buttons > 1) View.VISIBLE else View.GONE)
                dim(this, 2)
            }

            /** The large widget with a row of [places] models (none: no row), or the offline [notice]. */
            fun large(places: Int, notice: Boolean = false): RemoteViews = base(R.layout.widget_large, 44, R.drawable.widget_arrow_17).apply {
                setOnClickPendingIntent(R.id.widget_avatar, if (offline) retry else chat)
                if (!offline) {
                    setOnClickPendingIntent(R.id.widget_notes, page(context, IncomingIntents.ACTION_NOTES, code + 13))
                    setOnClickPendingIntent(R.id.widget_last_chat, page(context, IncomingIntents.ACTION_LAST_CHAT, code + 2))
                }
                dim(this, 4)
                setViewVisibility(R.id.widget_offline, if (notice) View.VISIBLE else View.GONE)
                if (notice) setOnClickPendingIntent(R.id.widget_retry, retry)
                setViewVisibility(R.id.widget_slots, if (places > 0 && !notice) View.VISIBLE else View.GONE)
                if (places > 0 && !notice) fillSlots(this, places)
            }

            /**
             * The row of the other models: 40 dp avatars in 48 dp places. If they do not all fit, the last
             * place is «+N» (announced as «Ver los 9 modelos») and opens the picker.
             */
            private fun fillSlots(views: RemoteViews, places: Int) {
                val size = (48 * density).toInt()
                val shown = if (others.size <= places) others else others.take(places - 1)
                val hidden = others.size - shown.size
                SLOTS.forEachIndexed { i, slot ->
                    when {
                        i < shown.size -> {
                            val m = shown[i]
                            views.setViewVisibility(slot, View.VISIBLE)
                            views.setImageViewBitmap(slot, slotAvatar(m, size))
                            views.setOnClickPendingIntent(slot, selectIntent(m, code + 3 + i))
                            views.setContentDescription(slot, context.getString(R.string.widget_switch_to, m.name))
                        }
                        i == shown.size && hidden > 0 -> {
                            views.setViewVisibility(slot, View.VISIBLE)
                            views.setImageViewBitmap(slot, ChatModels.more(context, hidden, size))
                            views.setOnClickPendingIntent(slot, picker)
                            views.setContentDescription(slot, context.getString(R.string.widget_more_models, models.size))
                        }
                        else -> views.setViewVisibility(slot, View.GONE)
                    }
                }
            }

            private val slotAvatars = HashMap<String, Bitmap>()

            private fun slotAvatar(m: ChatModel, size: Int) = slotAvatars.getOrPut(m.id) {
                ChatModels.avatar(context, m, size, ring = false, padded = true)
            }

            /** «Cambiar a Lain»: the widget switches model without opening anything. */
            private fun selectIntent(m: ChatModel, requestCode: Int): PendingIntent = broadcast(
                context, requestCode,
                Intent(context, VoiceWidgetProvider::class.java).setAction(ACTION_SELECT)
                    .setData(Uri.parse("sakura-widget://$id/select/${Uri.encode(m.id)}"))
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                    .putExtra(IncomingIntents.EXTRA_MODEL, m.id)
                    .putExtra(EXTRA_MODEL_NAME, m.name)
            )
        }

        private fun broadcast(context: Context, requestCode: Int, intent: Intent): PendingIntent =
            PendingIntent.getBroadcast(context, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        /** Agenda, Notas or Último chat: a page of Open WebUI. */
        private fun page(context: Context, action: String, requestCode: Int): PendingIntent =
            QuickVoice.activity(context, requestCode, QuickVoice.appIntent(context, action))

        /**
         * A new chat with [model], which also becomes the widget's model (MainActivity saves it). Opens the
         * app directly: Android 12+ blocks a broadcast from starting it.
         */
        private fun chatIntent(context: Context, widgetId: Int, model: ChatModel, requestCode: Int): PendingIntent =
            QuickVoice.activity(
                context, requestCode,
                QuickVoice.appIntent(context, IncomingIntents.ACTION_NEW_CHAT, model.id)
                    .setData(Uri.parse("sakura-widget://$widgetId/chat/${model.id}"))
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                    .putExtra(EXTRA_MODEL_NAME, model.name)
            )

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
