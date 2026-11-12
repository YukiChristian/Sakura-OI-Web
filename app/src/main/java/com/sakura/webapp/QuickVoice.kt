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
 * Home screen widget, from "App Android · widgets y «Oye, Sakura»". One row: avatar with a mic badge
 * (voice mode), "Habla con" and the model capsule (opens «¿Con quién quieres hablar?»), and round
 * Nuevo chat / Último chat buttons. Two rows or more: "Habla con <modelo>" and the blue mic, Sakura /
 * Lain / Yoko as chips to switch with one tap, and the two chat buttons.
 * Each widget remembers its model; voice mode and new chats open with it.
 */
class VoiceWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { update(context, manager, it) }
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) {
        update(context, manager, id)
    }

    override fun onDeleted(context: Context, ids: IntArray) {
        ids.forEach { ChatModels.forget(context, it) }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_SELECT) {
            val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            val model = intent.getStringExtra(EXTRA_MODEL_ID)
            if (id != AppWidgetManager.INVALID_APPWIDGET_ID && model != null) {
                ChatModels.setForWidget(context, id, ChatModels.find(model, intent.getStringExtra(EXTRA_MODEL_NAME) ?: model))
                update(context, AppWidgetManager.getInstance(context), id)
            }
            return
        }
        super.onReceive(context, intent)
    }

    companion object {
        private const val ACTION_SELECT = "com.sakura.webapp.widget.SELECT_MODEL"
        private const val EXTRA_MODEL_ID = "model_id"
        private const val EXTRA_MODEL_NAME = "model_name"
        private val CHIPS = listOf(
            Triple(R.id.widget_chip_0, R.id.widget_chip_avatar_0, R.id.widget_chip_name_0),
            Triple(R.id.widget_chip_1, R.id.widget_chip_avatar_1, R.id.widget_chip_name_1),
            Triple(R.id.widget_chip_2, R.id.widget_chip_avatar_2, R.id.widget_chip_name_2),
        )

        /** Redraws every widget, e.g. after «Oye, Sakura» is turned on or off. */
        fun refreshAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            manager.getAppWidgetIds(ComponentName(context, VoiceWidgetProvider::class.java))
                .forEach { update(context, manager, it) }
        }

        fun update(context: Context, manager: AppWidgetManager, id: Int) {
            val options = manager.getAppWidgetOptions(id)
            val height = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT) ?: 0
            val large = height >= 180
            val model = ChatModels.forWidget(context, id)
            val density = context.resources.displayMetrics.density
            val views = RemoteViews(context.packageName, if (large) R.layout.widget_large else R.layout.widget_small)
            // Request codes are unique per widget and button, so each keeps its own PendingIntent.
            val code = id * 16
            val voice = QuickVoice.pendingIntent(context, code, model.id)
            views.setOnClickPendingIntent(R.id.widget_voice, voice)
            views.setContentDescription(R.id.widget_voice, context.getString(R.string.widget_talk_to, model.name))
            views.setOnClickPendingIntent(
                R.id.widget_new_chat,
                QuickVoice.activity(context, code + 1, QuickVoice.appIntent(context, IncomingIntents.ACTION_NEW_CHAT, model.id))
            )
            views.setOnClickPendingIntent(
                R.id.widget_last_chat,
                QuickVoice.activity(context, code + 2, QuickVoice.appIntent(context, IncomingIntents.ACTION_LAST_CHAT))
            )
            if (large) {
                views.setImageViewBitmap(R.id.widget_avatar, ChatModels.avatar(context, model, (60 * density).toInt(), ring = true))
                views.setTextViewText(R.id.widget_title, context.getString(R.string.widget_talk_to, model.name))
                views.setOnClickPendingIntent(R.id.widget_mic, voice)
                ChatModels.PERSONAS.forEachIndexed { i, persona ->
                    val (chip, avatar, name) = CHIPS[i]
                    val selected = persona.id == model.id
                    views.setImageViewBitmap(avatar, ChatModels.avatar(context, persona, (34 * density).toInt()))
                    views.setTextViewText(name, persona.name)
                    views.setInt(chip, "setBackgroundResource", if (selected) R.drawable.widget_chip_selected else R.drawable.widget_chip)
                    if (Build.VERSION.SDK_INT >= 31) {
                        views.setColorStateList(
                            name, "setTextColor", if (selected) R.color.widget_chip_selected_ink else R.color.widget_ink
                        )
                    } else {
                        views.setTextColor(
                            name, context.getColor(if (selected) R.color.widget_chip_selected_ink else R.color.widget_ink)
                        )
                    }
                    views.setOnClickPendingIntent(chip, selectIntent(context, id, persona, code + 3 + i))
                }
            } else {
                views.setImageViewBitmap(R.id.widget_avatar, ChatModels.avatar(context, model, (58 * density).toInt()))
                views.setTextViewText(R.id.widget_model_name, model.name)
                views.setInt(R.id.widget_model_dot, "setColorFilter", model.color)
                views.setOnClickPendingIntent(
                    R.id.widget_model,
                    QuickVoice.activity(context, code + 6, WidgetModelPickerActivity.intent(context, id))
                )
            }
            manager.updateAppWidget(id, views)
        }

        private fun selectIntent(context: Context, widgetId: Int, model: ChatModel, requestCode: Int): PendingIntent =
            PendingIntent.getBroadcast(
                context, requestCode,
                Intent(context, VoiceWidgetProvider::class.java)
                    .setAction(ACTION_SELECT)
                    .setData(Uri.parse("sakura-widget://$widgetId/${model.id}"))
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                    .putExtra(EXTRA_MODEL_ID, model.id)
                    .putExtra(EXTRA_MODEL_NAME, model.name),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
    }
}
