package com.sakura.webapp

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
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
 * Home screen widget, from "Widgets · 1, 3 y 8 modelos, con Notas y Calendario". Its size picks the layout:
 * large (4 × 2): avatar, model name and the blue mic, a row with the avatars of the models (not shown with
 * a single model; when they do not fit, the last place becomes «+N»), and the bar Nuevo · Calendario ·
 * Notas · Último. Medium (one row): the mic, the model and round Nuevo chat, Calendario and Notas buttons.
 * Small (2 × 2): avatar and mic, the model, Nuevo chat and Calendario.
 * The model name (▾) opens «¿Con quién quieres hablar?»; the mic is the only way to voice mode; an avatar
 * of the row opens a new chat with that model, which also becomes the widget's model.
 */
class VoiceWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { update(context, manager, it) }
        ChatModels.refresh(context)
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) {
        update(context, manager, id)
    }

    override fun onDeleted(context: Context, ids: IntArray) {
        ids.forEach { ChatModels.forget(context, it) }
    }

    companion object {
        const val EXTRA_MODEL_NAME = "model_name"
        private val SLOTS = intArrayOf(
            R.id.widget_slot_0, R.id.widget_slot_1, R.id.widget_slot_2, R.id.widget_slot_3,
            R.id.widget_slot_4, R.id.widget_slot_5, R.id.widget_slot_6,
        )
        /** Below this height (dp) the large widget leaves out the row of avatars so the rest fits. */
        private const val ROW_MIN_HEIGHT = 200

        /** Redraws every widget, e.g. after the list of models changed. */
        fun refreshAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            manager.getAppWidgetIds(ComponentName(context, VoiceWidgetProvider::class.java))
                .forEach { update(context, manager, it) }
        }

        fun update(context: Context, manager: AppWidgetManager, id: Int) {
            // Portrait size in dp (0 before the launcher reports it: the large layout, as placed).
            val options = manager.getAppWidgetOptions(id)
            val width = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH) ?: 0
            val height = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT) ?: 0
            val layout = when {
                height in 1 until 120 -> R.layout.widget_medium
                width in 1 until 250 -> R.layout.widget_small
                else -> R.layout.widget_large
            }
            if (BuildConfig.DEBUG) android.util.Log.d("SakuraWidget", "widget $id: $width x $height dp")
            val model = ChatModels.forWidget(context, id)
            val models = ChatModels.available(context).let { list -> if (list.any { it.id == model.id }) list else list + model }
            val several = models.size > 1
            val density = context.resources.displayMetrics.density
            fun px(dp: Int) = (dp * density).toInt()
            val views = RemoteViews(context.packageName, layout)
            // Request codes are unique per widget and button, so each keeps its own PendingIntent.
            val code = id * 32
            val picker = QuickVoice.activity(context, code + 11, WidgetModelPickerActivity.intent(context, id))
            val chat = chatIntent(context, id, model, code + 10)

            views.setOnClickPendingIntent(R.id.widget_mic, QuickVoice.pendingIntent(context, code, model.id))
            views.setContentDescription(R.id.widget_mic, context.getString(R.string.widget_talk_to, model.name))
            val avatarDp = when (layout) { R.layout.widget_large -> 48; R.layout.widget_small -> 46; else -> 30 }
            views.setImageViewBitmap(R.id.widget_avatar, ChatModels.avatar(context, model, px(avatarDp)))
            views.setTextViewText(R.id.widget_model_name, ChatModels.shortName(model))
            val arrow = if (layout == R.layout.widget_large) R.drawable.widget_arrow_17 else R.drawable.widget_arrow_15
            views.setTextViewCompoundDrawablesRelative(R.id.widget_model_name, 0, 0, if (several) arrow else 0, 0)
            views.setOnClickPendingIntent(R.id.widget_model, if (several) picker else chat)
            views.setContentDescription(
                R.id.widget_model,
                context.getString(if (several) R.string.widget_change_model else R.string.widget_open_chat, model.name)
            )
            views.setOnClickPendingIntent(
                R.id.widget_new_chat,
                QuickVoice.activity(context, code + 1, QuickVoice.appIntent(context, IncomingIntents.ACTION_NEW_CHAT, model.id))
            )
            views.setOnClickPendingIntent(R.id.widget_calendar, page(context, IncomingIntents.ACTION_CALENDAR, code + 12))

            when (layout) {
                R.layout.widget_large -> {
                    views.setOnClickPendingIntent(R.id.widget_avatar, chat)
                    views.setOnClickPendingIntent(R.id.widget_notes, page(context, IncomingIntents.ACTION_NOTES, code + 13))
                    views.setOnClickPendingIntent(R.id.widget_last_chat, page(context, IncomingIntents.ACTION_LAST_CHAT, code + 2))
                    val showRow = several && (height == 0 || height >= ROW_MIN_HEIGHT)
                    views.setViewVisibility(R.id.widget_slots, if (showRow) View.VISIBLE else View.GONE)
                    if (showRow) fillSlots(context, views, id, code, model, models, width, picker)
                }
                R.layout.widget_medium -> {
                    views.setOnClickPendingIntent(R.id.widget_notes, page(context, IncomingIntents.ACTION_NOTES, code + 13))
                    // Round buttons by importance, as many as fit next to the mic and the model's name.
                    val room = if (width == 0) 3 else ((width - 186) / 50).coerceIn(0, 3)
                    intArrayOf(R.id.widget_new_chat, R.id.widget_calendar, R.id.widget_notes).forEachIndexed { i, button ->
                        views.setViewVisibility(button, if (i < room) View.VISIBLE else View.GONE)
                    }
                }
                else -> views.setOnClickPendingIntent(R.id.widget_avatar, chat)
            }
            manager.updateAppWidget(id, views)
        }

        /**
         * The row of avatars: 42 dp places (a 34 dp avatar and room for the ring) 2 dp apart, as many as
         * fit the width, up to 7. If there are more models, the last place is «+N» and opens the picker;
         * the widget's model is always among those shown, with its ring.
         */
        private fun fillSlots(
            context: Context, views: RemoteViews, id: Int, code: Int, model: ChatModel, models: List<ChatModel>,
            width: Int, picker: PendingIntent,
        ) {
            val size = (42 * context.resources.displayMetrics.density).toInt()
            val places = if (width == 0) SLOTS.size else ((width - 28 + 2) / 44).coerceIn(2, SLOTS.size)
            var shown = if (models.size <= places) models else models.take(places - 1)
            if (shown.none { it.id == model.id }) shown = shown.dropLast(1) + model
            val hidden = models.size - shown.size
            SLOTS.forEachIndexed { i, slot ->
                when {
                    i < shown.size -> {
                        val m = shown[i]
                        views.setViewVisibility(slot, View.VISIBLE)
                        views.setImageViewBitmap(slot, ChatModels.avatar(context, m, size, ring = m.id == model.id, padded = true))
                        views.setOnClickPendingIntent(slot, chatIntent(context, id, m, code + 3 + i))
                        views.setContentDescription(slot, context.getString(R.string.widget_open_chat, m.name))
                    }
                    i == shown.size && hidden > 0 -> {
                        views.setViewVisibility(slot, View.VISIBLE)
                        views.setImageViewBitmap(slot, ChatModels.more(context, hidden, size))
                        views.setOnClickPendingIntent(slot, picker)
                        views.setContentDescription(slot, context.getString(R.string.widget_more_models, hidden))
                    }
                    else -> views.setViewVisibility(slot, View.GONE)
                }
            }
        }

        /** Calendario, Notas or Último chat: a page of Open WebUI. */
        private fun page(context: Context, action: String, requestCode: Int): PendingIntent =
            QuickVoice.activity(context, requestCode, QuickVoice.appIntent(context, action))

        /**
         * A new chat with [model], which also becomes the widget's model (MainActivity saves it), so the
         * blue mic then talks to it. Opens the app directly: Android 12+ blocks a broadcast from starting it.
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
