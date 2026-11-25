package com.sakura.webapp

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Battery in the background (pedido de Christian, 21-nov-2026: less battery without losing functions).
 *
 * Before, leaving the app kept Open WebUI awake (its connection, its timers, a foreground service) for as long as
 * you were away, hours if need be. Now MainActivity keeps it awake [IDLE_MS] after you leave, so coming back soon
 * finds it exactly as it was, and longer only while a model is still answering, so its notice arrives; then the
 * page sleeps (its timers stop and the service ends, so Android can freeze it) until you come back. Notices of
 * answers that finish while it sleeps arrive by push (ntfy) when that is on.
 */
object PageSleep {
    /** Awake this long after leaving, answering or not. */
    const val IDLE_MS = 10 * 60 * 1000L
    /** While a model answers, asked again this often… */
    const val BUSY_RECHECK_MS = 60 * 1000L
    /** …but never awake longer than this after leaving. */
    const val MAX_AWAKE_MS = 60 * 60 * 1000L

    private val CHAT = Regex("^/c/([^/?#]+)")

    /** The chat the page shows ("/c/<id>"), or null. */
    fun chatOf(url: String?): String? = url?.let { runCatching { CHAT.find(java.net.URI(it).path ?: "")?.groupValues?.get(1) }.getOrNull() }

    /**
     * True while Open WebUI is still answering: in [chatId] (any user may ask about their own chat) or anywhere
     * (only an administrator may ask that). Blocking; false when it cannot tell (no session, no connection).
     */
    fun isAnswering(context: Context, chatId: String?): Boolean {
        val (base, token) = runCatching { ChatModels.session(context) }.getOrNull() ?: return false
        if (chatId != null) {
            val ids = runCatching {
                get("$base/api/tasks/chat/" + URLEncoder.encode(chatId, "UTF-8"), token).optJSONArray("task_ids")
            }.getOrNull()
            if (ids != null && ids.length() > 0) return true
        }
        val all = runCatching { get("$base/api/tasks", token).optJSONArray("tasks") }.getOrNull()
        return all != null && all.length() > 0
    }

    private fun get(url: String, token: String): JSONObject {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 8_000
            connection.readTimeout = 10_000
            connection.setRequestProperty("Authorization", "Bearer $token")
            check(connection.responseCode == HttpURLConnection.HTTP_OK) { "HTTP ${connection.responseCode}" }
            return JSONObject(connection.inputStream.bufferedReader().readText())
        } finally {
            connection.disconnect()
        }
    }
}
