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
import java.io.File
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.security.MessageDigest
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
        ChatModel("lain", "Lain", 0xFF3E5F99.toInt(), R.drawable.avatar_lain),
        ChatModel("yoko", "Yoko", 0xFFE5484D.toInt(), R.drawable.avatar_yoko),
    )
    /**
     * Initials of the other models in Hanami's palette, without purples: orange, green, amber, teal, lime and
     * graphite (picked by name, so each keeps its own).
     */
    private val OTHER_COLORS = intArrayOf(
        0xFFD9681F.toInt(), 0xFF2B8A5A.toInt(), 0xFFA87F00.toInt(), 0xFF1A8A9A.toInt(), 0xFF5F8A12.toInt(), 0xFF52525B.toInt(),
    )
    /** The same colours lighter for dark mode, so each ring and initial keeps 3:1 on graphite glass. */
    private val NIGHT = mapOf(
        0xFFD6336C.toInt() to 0xFFF06595.toInt(), 0xFF3E5F99.toInt() to 0xFF8FA9DA.toInt(), 0xFFE5484D.toInt() to 0xFFFF6B70.toInt(),
        0xFFD9681F.toInt() to 0xFFF08C4A.toInt(), 0xFF2B8A5A.toInt() to 0xFF4CC38A.toInt(), 0xFFA87F00.toInt() to 0xFFE0B43A.toInt(),
        0xFF1A8A9A.toInt() to 0xFF3FB5C4.toInt(), 0xFF5F8A12.toInt() to 0xFF93C142.toInt(), 0xFF52525B.toInt() to 0xFFA1A1AA.toInt(),
    )

    /** The model's colour for the current light or dark mode. */
    fun color(context: Context, model: ChatModel): Int {
        val night = (context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        return if (night) NIGHT[model.color] ?: model.color else model.color
    }

    fun isPersona(model: ChatModel) = PERSONAS.any { it.id == model.id }
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
        // the models' descriptions came with the widgets W1–W12: read them once at once if they are missing
        val sinDescripciones = !app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).contains("descripciones")
        if (refreshing || (!force && !sinDescripciones && System.currentTimeMillis() - at < REFRESH_MS)) return
        refreshing = true
        Thread {
            try {
                val models = runCatching { fetchAll(app) }.getOrNull()
                if (models != null) {
                    val listChanged = saveList(app, models)
                    val artChanged = syncArt(app, models)
                    if (listChanged || artChanged) VoiceWidgetProvider.refreshAll(app)
                }
            } finally {
                refreshing = false
            }
        }.start()
    }

    /** The model's description in Open WebUI, as last read («Compañera»), or null. */
    fun descripcion(context: Context, id: String): String? = runCatching {
        JSONObject(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("descripciones", "{}")!!).optString(id).takeIf { it.isNotBlank() && it != "null" }
    }.getOrNull()

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
     * Sakura, Lain and Yoko (when the server has them) and then the models pinned in Open WebUI (its sidebar's pinned models, `ui.pinnedModels` in the user's
     * settings), in that order, among the visible ones; uses the session the app already keeps (see
     * SessionSync). Blocking; throws when there is no session or the server does not answer.
     */
    fun fetchAll(context: Context): List<ChatModel> {
        val (base, token) = session(context)
        val data = get("$base/api/models", token).optJSONArray("data") ?: return emptyList()
        // each model's description from Open WebUI («Compañera»), for the widgets' second line
        val descripciones = JSONObject()
        for (i in 0 until data.length()) {
            val m = data.optJSONObject(i) ?: continue
            val meta = m.optJSONObject("info")?.optJSONObject("meta")
            val d = if (meta == null || meta.isNull("description")) "" else meta.optString("description").trim()
            if (d.isNotEmpty() && d != "null") descripciones.put(m.optString("id"), d.lineSequence().first().take(60))
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("descripciones", descripciones.toString()).apply()
        val visible = (0 until data.length()).mapNotNull { i ->
            val m = data.optJSONObject(i) ?: return@mapNotNull null
            val hidden = m.optJSONObject("info")?.optJSONObject("meta")?.optBoolean("hidden") == true
            val id = m.optString("id")
            if (hidden || id.isEmpty()) null else find(id, m.optString("name").ifEmpty { id })
        }
        val pinned = get("$base/api/v1/users/user/settings", token).optJSONObject("ui")?.optJSONArray("pinnedModels")
        val ids = pinned?.let { array -> (0 until array.length()).map { array.optString(it) } }.orEmpty()
        // «Tus compañeras» first whenever the server has them, pinned or not; then the other pinned models.
        val companions = PERSONAS.mapNotNull { p -> visible.firstOrNull { it.id == p.id } }
        return (companions + ids.mapNotNull { id -> visible.firstOrNull { it.id == id } }).distinctBy { it.id }
    }

    /**
     * The server's address and the session the app already keeps (see SessionSync); throws without them.
     * Also used by [Updates] for the private channel of personal builds.
     */
    fun session(context: Context): Pair<String, String> {
        ServerAddress.load(context)
        val base = ServerAddress.current.ifEmpty { ServerAddress.exterior ?: ServerAddress.local ?: "" }.trimEnd('/')
        val token = context.getSharedPreferences("session_sync", Context.MODE_PRIVATE).getString("token", null)
        check(base.isNotEmpty() && !token.isNullOrEmpty()) { "No session" }
        return base to token
    }

    // ── Pictures from Open WebUI ──
    // Only Sakura, Lain and Yoko came drawn in the app; any other model (Sakiwa) showed its initial, and a picture
    // changed on the server never reached the widgets. Now each model's picture comes from Open WebUI and is kept
    // on the phone; the app's own art stays as the fallback until the first download, and the initial for models
    // with no picture of their own.
    private const val KEY_ART_TAG = "art_tag_"
    private const val ART_PX = 256

    private fun artFile(context: Context, id: String): File {
        val hash = MessageDigest.getInstance("SHA-1").digest(id.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(File(context.filesDir, "model_art"), "$hash.png")
    }

    /** The model's picture as last downloaded from Open WebUI; null when it has none or it was not read yet. */
    private fun serverArt(context: Context, id: String): Bitmap? {
        val file = artFile(context, id)
        return if (file.isFile) runCatching { BitmapFactory.decodeFile(file.path) }.getOrNull() else null
    }

    /**
     * Downloads the pictures of [models] from Open WebUI (`/api/v1/models/model/profile/image`) and keeps them on
     * the phone. Each one is asked with its last ETag, so an unchanged picture costs an empty 304. True when any
     * picture changed (the widgets then redraw). Blocking; quietly keeps what it has when the server fails.
     */
    fun syncArt(context: Context, models: List<ChatModel>): Boolean {
        val (base, token) = runCatching { session(context) }.getOrNull() ?: return false
        var changed = false
        for (model in models.distinctBy { it.id }) {
            if (runCatching { syncOneArt(context, base, token, model.id) }.getOrDefault(false)) changed = true
        }
        return changed
    }

    private fun syncOneArt(context: Context, base: String, token: String, id: String): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val file = artFile(context, id)
        val tag = prefs.getString(KEY_ART_TAG + id, null)
        val url = "$base/api/v1/models/model/profile/image?id=" + URLEncoder.encode(id, "UTF-8")
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 8_000
            connection.readTimeout = 12_000
            connection.setRequestProperty("Authorization", "Bearer $token")
            if (tag != null && file.isFile) connection.setRequestProperty("If-None-Match", tag)
            when (connection.responseCode) {
                HttpURLConnection.HTTP_NOT_MODIFIED -> return false
                HttpURLConnection.HTTP_OK -> {
                    val bytes = connection.inputStream.use { it.readBytes() }
                    val picture = decodeScaled(bytes) ?: return false
                    file.parentFile?.mkdirs()
                    val temp = File(file.path + ".tmp")
                    temp.outputStream().use { picture.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    if (!temp.renameTo(file)) {
                        temp.delete()
                        return false
                    }
                    prefs.edit().putString(KEY_ART_TAG + id, connection.getHeaderField("ETag")).apply()
                    return true
                }
                in 300..399 -> {
                    // Open WebUI sends its default logo: the model has no picture of its own (or it was removed).
                    val had = file.exists()
                    file.delete()
                    prefs.edit().remove(KEY_ART_TAG + id).apply()
                    return had
                }
                else -> return false
            }
        } finally {
            connection.disconnect()
        }
    }

    /** Decodes a downloaded picture no larger than needed: its short side at most [ART_PX]. */
    private fun decodeScaled(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val short = minOf(bounds.outWidth, bounds.outHeight)
        if (short <= 0) return null
        var sample = 1
        while (short / (sample * 2) >= ART_PX) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null
        val side = minOf(decoded.width, decoded.height)
        if (side <= ART_PX) return decoded
        val scale = ART_PX.toFloat() / side
        return Bitmap.createScaledBitmap(decoded, (decoded.width * scale).toInt(), (decoded.height * scale).toInt(), true)
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
     * avatars. The picture is the one in Open WebUI (or the app's own art until it is downloaded); models without
     * any get their initial.
     */
    fun avatar(context: Context, model: ChatModel, sizePx: Int, ring: Boolean = false, padded: Boolean = ring): Bitmap {
        val density = context.resources.displayMetrics.density
        val inset = if (padded) 4 * density else 0f
        val out = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val c = sizePx / 2f
        val r = c - inset
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val art = serverArt(context, model.id) ?: model.avatar?.let { BitmapFactory.decodeResource(context.resources, it) }
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
            paint.color = color(context, model)
            canvas.drawCircle(c, c, r, paint)
            drawCentered(canvas, shortName(model).trim().take(1).uppercase(), c, r * 0.95f, 0xFFFFFFFF.toInt())
        }
        if (ring) {
            canvas.drawCircle(c, c, c - density, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 2 * density
                color = this@ChatModels.color(context, model)
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
