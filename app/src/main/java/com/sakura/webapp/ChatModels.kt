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
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** A model to talk to: one of the three characters, or any other model on the server. */
data class ChatModel(val id: String, val name: String, val color: Int, val avatar: Int?)

/**
 * Sakura, Lain and Yoko (the pinned models of the widgets), the choice each widget remembers,
 * and the full model list from Open WebUI for «Ver todos los modelos».
 */
object ChatModels {
    val PERSONAS = listOf(
        ChatModel("sakura", "Sakura", 0xFFD6336C.toInt(), R.drawable.avatar_sakura),
        ChatModel("lain", "Lain", 0xFF3D5A9E.toInt(), R.drawable.avatar_lain),
        ChatModel("yoko", "Yoko", 0xFFE8452C.toInt(), R.drawable.avatar_yoko),
    )
    private const val OTHER_COLOR = 0xFF71717A.toInt()
    private const val PREFS = "widget_models"

    fun find(id: String, name: String = id): ChatModel =
        PERSONAS.firstOrNull { it.id == id } ?: ChatModel(id, name, OTHER_COLOR, null)

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
     * Visible models from Open WebUI, using the session the app already keeps (see SessionSync).
     * Blocking; throws when there is no session or the server does not answer.
     */
    fun fetchAll(context: Context): List<ChatModel> {
        ServerAddress.load(context)
        val base = ServerAddress.current.ifEmpty { ServerAddress.exterior ?: ServerAddress.local ?: "" }.trimEnd('/')
        val token = context.getSharedPreferences("session_sync", Context.MODE_PRIVATE).getString("token", null)
        check(base.isNotEmpty() && !token.isNullOrEmpty()) { "No session" }
        val connection = URL("$base/api/models").openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 8_000
            connection.readTimeout = 12_000
            connection.setRequestProperty("Authorization", "Bearer $token")
            check(connection.responseCode == HttpURLConnection.HTTP_OK) { "HTTP ${connection.responseCode}" }
            val data = JSONObject(connection.inputStream.bufferedReader().readText()).optJSONArray("data")
                ?: return emptyList()
            return (0 until data.length()).mapNotNull { i ->
                val m = data.optJSONObject(i) ?: return@mapNotNull null
                val hidden = m.optJSONObject("info")?.optJSONObject("meta")?.optBoolean("hidden") == true
                val id = m.optString("id")
                if (hidden || id.isEmpty()) null else find(id, m.optString("name").ifEmpty { id })
            }
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Round avatar of [sizePx], with a 2 dp transparent gap and a 2 dp ring in the model's colour when
     * [ring] is set (as in the design's selected avatars). Models without art get their initial.
     */
    fun avatar(context: Context, model: ChatModel, sizePx: Int, ring: Boolean = false): Bitmap {
        val density = context.resources.displayMetrics.density
        val inset = if (ring) 4 * density else 0f
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
            paint.color = 0xFFFFFFFF.toInt()
            paint.textAlign = Paint.Align.CENTER
            paint.typeface = Typeface.create("sans-serif", Typeface.BOLD)
            paint.textSize = r * 0.95f
            val initial = model.name.trim().take(1).uppercase()
            canvas.drawText(initial, c, c - (paint.descent() + paint.ascent()) / 2, paint)
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
}
