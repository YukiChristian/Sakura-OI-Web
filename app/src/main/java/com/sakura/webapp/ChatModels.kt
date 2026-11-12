package com.sakura.webapp

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** A model to talk to: one of the three characters, or any other model on the server. */
data class ChatModel(val id: String, val name: String, val color: Int, val avatar: Int?)

/**
 * The models of the widgets: the ones pinned in Open WebUI (saved, in the order they are pinned), the one
 * each widget remembers, and their round avatars.
 */
object ChatModels {
    val PERSONAS = listOf(
        ChatModel("sakura", "Sakura", 0xFFD6336C.toInt(), R.drawable.avatar_sakura),
        ChatModel("lain", "Lain", 0xFF3D5A9E.toInt(), R.drawable.avatar_lain),
        ChatModel("yoko", "Yoko", 0xFFE8452C.toInt(), R.drawable.avatar_yoko),
    )
    /** Initials of the other models, in the design's colours (picked by name, so each keeps its own). */
    private val OTHER_COLORS = intArrayOf(
        0xFF6D5BD0.toInt(), 0xFF1A8A7A.toInt(), 0xFFC27A00.toInt(), 0xFF2F6FD6.toInt(), 0xFF7A4A2A.toInt(), 0xFF5A6B7A.toInt(),
    )
    private const val PREFS = "widget_models"
    private const val KEY_LIST = "pinned_list"
    private const val KEY_LIST_AT = "pinned_list_at"
    private const val REFRESH_MS = 30 * 60 * 1000L
    @Volatile private var refreshing = false

    /**
     * The name to show: without the prefix Open WebUI puts before the models of a connection
     * ("laín.qwen-27b" → "qwen-27b"), so they do not all start with the same letter. "GPT-4.1" stays.
     */
    fun shortName(model: ChatModel): String {
        val rest = model.name.substringAfter('.', "")
        return if (rest.firstOrNull()?.isLetter() == true) rest else model.name
    }

    fun find(id: String, name: String = id): ChatModel =
        PERSONAS.firstOrNull { it.id == id }
            ?: ChatModel(id, name, OTHER_COLORS[Math.floorMod(name.lowercase().hashCode(), OTHER_COLORS.size)], null)

    /**
     * The models the widgets offer: the ones pinned in Open WebUI as last seen (none pinned: an empty list,
     * so the widget shows only its own model), or the three characters until the app has read them once.
     */
    fun available(context: Context): List<ChatModel> {
        val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_LIST, null)
            ?: return PERSONAS
        return runCatching {
            val array = JSONArray(saved)
            (0 until array.length()).map { i ->
                val m = array.getJSONObject(i)
                find(m.getString("id"), m.optString("name").ifEmpty { m.getString("id") })
            }.distinctBy { it.id }
        }.getOrNull() ?: PERSONAS
    }

    fun hasServerList(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).contains(KEY_LIST)

    /** Saves the server's list; true when it changed (the widgets then redraw). */
    fun saveList(context: Context, models: List<ChatModel>): Boolean {
        val json = JSONArray().apply {
            models.distinctBy { it.id }.forEach { put(JSONObject().put("id", it.id).put("name", it.name)) }
        }.toString()
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val changed = prefs.getString(KEY_LIST, null) != json
        prefs.edit().putString(KEY_LIST, json).putLong(KEY_LIST_AT, System.currentTimeMillis()).apply()
        return changed
    }

    /**
     * Reads the server's models in the background (at most every 30 minutes unless [force]) and redraws
     * the widgets when the list changed. Quietly does nothing without a session or a connection.
     */
    fun refresh(context: Context, force: Boolean = false) {
        val app = context.applicationContext
        val at = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_LIST_AT, 0)
        if (refreshing || (!force && System.currentTimeMillis() - at < REFRESH_MS)) return
        refreshing = true
        Thread {
            try {
                val models = runCatching { fetchAll(app) }.getOrNull()
                if (models != null && saveList(app, models)) VoiceWidgetProvider.refreshAll(app)
            } finally {
                refreshing = false
            }
        }.start()
    }

    /** The model a widget talks to; new widgets start with the last one chosen anywhere. */
    fun forWidget(context: Context, widgetId: Int): ChatModel {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString("model_$widgetId", null)?.let { id -> return find(id, prefs.getString("name_$widgetId", null) ?: id) }
        // First time: take the last choice and keep it for this widget, so other widgets don't change it later.
        val id = prefs.getString("model_last", null) ?: PERSONAS[0].id
        val name = prefs.getString("name_last", null) ?: id
        prefs.edit().putString("model_$widgetId", id).putString("name_$widgetId", name).apply()
        return find(id, name)
    }

    fun setForWidget(context: Context, widgetId: Int, model: ChatModel) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("model_$widgetId", model.id).putString("name_$widgetId", model.name)
            .putString("model_last", model.id).putString("name_last", model.name)
            .apply()
    }

    fun forget(context: Context, widgetId: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove("model_$widgetId").remove("name_$widgetId").apply()
    }

    /**
     * The models pinned in Open WebUI (its sidebar's pinned models, `ui.pinnedModels` in the user's
     * settings), in that order, among the visible ones; uses the session the app already keeps (see
     * SessionSync). Blocking; throws when there is no session or the server does not answer.
     */
    fun fetchAll(context: Context): List<ChatModel> {
        ServerAddress.load(context)
        val base = ServerAddress.current.ifEmpty { ServerAddress.exterior ?: ServerAddress.local ?: "" }.trimEnd('/')
        val token = context.getSharedPreferences("session_sync", Context.MODE_PRIVATE).getString("token", null)
        check(base.isNotEmpty() && !token.isNullOrEmpty()) { "No session" }
        val data = get("$base/api/models", token).optJSONArray("data") ?: return emptyList()
        val visible = (0 until data.length()).mapNotNull { i ->
            val m = data.optJSONObject(i) ?: return@mapNotNull null
            val hidden = m.optJSONObject("info")?.optJSONObject("meta")?.optBoolean("hidden") == true
            val id = m.optString("id")
            if (hidden || id.isEmpty()) null else find(id, m.optString("name").ifEmpty { id })
        }
        val pinned = get("$base/api/v1/users/user/settings", token).optJSONObject("ui")?.optJSONArray("pinnedModels")
        val ids = pinned?.let { array -> (0 until array.length()).map { array.optString(it) } }.orEmpty()
        return ids.mapNotNull { id -> visible.firstOrNull { it.id == id } }
    }

    private fun get(url: String, token: String): JSONObject {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 8_000
            connection.readTimeout = 12_000
            connection.setRequestProperty("Authorization", "Bearer $token")
            check(connection.responseCode == HttpURLConnection.HTTP_OK) { "HTTP ${connection.responseCode}" }
            val text = connection.inputStream.bufferedReader().readText()
            return if (text.isBlank() || text.trim() == "null") JSONObject() else JSONObject(text)
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Round avatar of [sizePx]. With [padded] the picture leaves 4 dp around it, and [ring] draws a 2 dp
     * ring in the model's colour at the edge (2 dp away from the picture), as in the design's selected
     * avatars. Models without art get their initial.
     */
    fun avatar(context: Context, model: ChatModel, sizePx: Int, ring: Boolean = false, padded: Boolean = ring): Bitmap {
        val density = context.resources.displayMetrics.density
        val inset = if (padded) 4 * density else 0f
        val out = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val c = sizePx / 2f
        val r = c - inset
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val art = model.avatar?.let { BitmapFactory.decodeResource(context.resources, it) }
        if (art != null) {
            val scale = (2 * r) / minOf(art.width, art.height)
            paint.shader = BitmapShader(art, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
                setLocalMatrix(Matrix().apply {
                    postScale(scale, scale)
                    postTranslate(c - art.width * scale / 2, c - art.height * scale / 2)
                })
            }
            canvas.drawCircle(c, c, r, paint)
        } else {
            paint.color = model.color
            canvas.drawCircle(c, c, r, paint)
            drawCentered(canvas, shortName(model).trim().take(1).uppercase(), c, r * 0.95f, 0xFFFFFFFF.toInt())
        }
        if (ring) {
            canvas.drawCircle(c, c, c - density, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 2 * density
                color = model.color
            })
        }
        return out
    }

    /** «+N» in the place of an avatar: the models that did not fit, same 4 dp margin as the avatars. */
    fun more(context: Context, count: Int, sizePx: Int): Bitmap {
        val density = context.resources.displayMetrics.density
        val out = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val c = sizePx / 2f
        val r = c - 4 * density
        canvas.drawCircle(c, c, r, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.widget_tint) })
        canvas.drawCircle(c, c, r - density / 2, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = density
            color = context.getColor(R.color.widget_tint_ring)
        })
        drawCentered(canvas, "+$count", c, 13 * density, context.getColor(R.color.widget_ink))
        return out
    }

    private fun drawCentered(canvas: Canvas, text: String, c: Float, size: Float, color: Int) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            textSize = size
        }
        canvas.drawText(text, c, c - (paint.descent() + paint.ascent()) / 2, paint)
    }
}
