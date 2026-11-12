package com.sakura.webapp

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * Notices even when Android has closed the app, through UnifiedPush. A distributor app such as
 * ntfy keeps one connection open for every app on the phone and wakes Sakura when a message arrives.
 * Sakura registers with it, gets a push address, and tells Open WebUI to send its "chat finished"
 * notifications there while the user is away (Open WebUI's notification targets, delivery "away").
 * The response text travels through the distributor's server (ntfy.sh unless the user picks another).
 */
object PushNotices {
    private const val PREFS = "push_notices"
    private const val TARGET_ID = "sakura-oi-web"
    private const val CHANNEL_ID = "open_webui_silent_v2"
    private const val TAG = "SakuraWebApp"
    const val NTFY_PACKAGE = "io.heckel.ntfy"

    // UnifiedPush Android protocol (connector ↔ distributor broadcasts).
    private const val DISTRIBUTOR_REGISTER = "org.unifiedpush.android.distributor.REGISTER"
    private const val DISTRIBUTOR_UNREGISTER = "org.unifiedpush.android.distributor.UNREGISTER"
    private const val DISTRIBUTOR_ACK = "org.unifiedpush.android.distributor.MESSAGE_ACK"
    const val NEW_ENDPOINT = "org.unifiedpush.android.connector.NEW_ENDPOINT"
    const val MESSAGE = "org.unifiedpush.android.connector.MESSAGE"
    const val UNREGISTERED = "org.unifiedpush.android.connector.UNREGISTERED"
    const val REGISTRATION_FAILED = "org.unifiedpush.android.connector.REGISTRATION_FAILED"

    enum class State { OFF, NO_DISTRIBUTOR, CONNECTING, ON, NOT_ADMIN, FAILED }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(context: Context) = prefs(context).getBoolean("enabled", false)

    fun state(context: Context): State =
        runCatching { State.valueOf(prefs(context).getString("state", State.OFF.name)!!) }.getOrDefault(State.OFF)

    private fun setState(context: Context, state: State) = prefs(context).edit().putString("state", state.name).apply()

    /** Host of the push server in use ("ntfy.sh"), for the settings. */
    fun pushHost(context: Context): String? = prefs(context).getString("endpoint", null)?.let { Uri.parse(it).host }

    /** Installed distributors (apps that answer UnifiedPush registrations), ntfy first. */
    fun distributors(context: Context): List<String> {
        val pm = context.packageManager
        val found = if (Build.VERSION.SDK_INT >= 33) {
            pm.queryBroadcastReceivers(Intent(DISTRIBUTOR_REGISTER), PackageManager.ResolveInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION") pm.queryBroadcastReceivers(Intent(DISTRIBUTOR_REGISTER), 0)
        }
        return found.mapNotNull { it.activityInfo?.packageName }.filter { it != context.packageName }.distinct()
            .sortedBy { if (it == NTFY_PACKAGE) 0 else 1 }
    }

    /** Turns notices on: registers with the distributor; the address arrives in [PushReceiver]. */
    fun enable(context: Context) {
        val distributor = distributors(context).firstOrNull()
        prefs(context).edit().putBoolean("enabled", true).apply()
        if (distributor == null) {
            setState(context, State.NO_DISTRIBUTOR)
            return
        }
        val token = prefs(context).getString("token", null) ?: UUID.randomUUID().toString()
        prefs(context).edit().putString("token", token).putString("distributor", distributor).apply()
        setState(context, State.CONNECTING)
        context.sendBroadcast(
            Intent(DISTRIBUTOR_REGISTER).setPackage(distributor)
                .putExtra("token", token)
                .putExtra("application", context.packageName)
                .putExtra("message", context.getString(R.string.push_distributor_message))
        )
    }

    /** Turns notices off: removes the Open WebUI target and unregisters from the distributor. */
    fun disable(context: Context) {
        val p = prefs(context)
        val token = p.getString("token", null)
        val distributor = p.getString("distributor", null)
        if (token != null && distributor != null) {
            context.sendBroadcast(Intent(DISTRIBUTOR_UNREGISTER).setPackage(distributor).putExtra("token", token))
        }
        p.edit().putBoolean("enabled", false).remove("endpoint").remove("token").apply()
        setState(context, State.OFF)
        val app = context.applicationContext
        Thread { runCatching { request(app, "DELETE", "/api/v1/notifications/targets/$TARGET_ID") } }.start()
    }

    fun isOurToken(context: Context, token: String?) = token != null && token == prefs(context).getString("token", null)

    fun onNewEndpoint(context: Context, endpoint: String) {
        prefs(context).edit().putString("endpoint", endpoint).apply()
        val app = context.applicationContext
        Thread {
            val state = try {
                configureServer(app, endpoint)
                State.ON
            } catch (e: NotAdmin) {
                State.NOT_ADMIN
            } catch (e: Exception) {
                Log.w(TAG, "Could not set up push notices on the server", e)
                State.FAILED
            }
            setState(app, state)
        }.start()
    }

    fun onFailed(context: Context) = setState(context, State.FAILED)

    fun onUnregistered(context: Context) {
        prefs(context).edit().remove("endpoint").apply()
        if (isEnabled(context)) setState(context, State.FAILED)
    }

    private class NotAdmin : Exception()

    /**
     * Makes Open WebUI send "chat finished" and "chat failed" to the push address while the user is
     * away: user webhooks on (an admin setting) and a notification target pointing at the address.
     */
    private fun configureServer(context: Context, endpoint: String) {
        val config = request(context, "GET", "/api/v1/auths/admin/config")
        if (config.first == 401 || config.first == 403) throw NotAdmin()
        check(config.first == 200) { "admin config HTTP ${config.first}" }
        val admin = JSONObject(config.second)
        if (!admin.optBoolean("ENABLE_USER_WEBHOOKS", false)) {
            admin.put("ENABLE_USER_WEBHOOKS", true)
            val saved = request(context, "POST", "/api/v1/auths/admin/config", admin.toString())
            check(saved.first == 200) { "admin config update HTTP ${saved.first}" }
        }
        val target = JSONObject()
            .put("id", TARGET_ID)
            .put("type", "webhook")
            .put("enabled", true)
            .put("events", JSONArray(listOf("chat.finished", "chat.failed")))
            .put("delivery", "away")
            .put("config", JSONObject().put("url", endpoint))
        val existing = request(context, "GET", "/api/v1/notifications/targets")
        val exists = existing.first == 200 && existing.second.contains("\"$TARGET_ID\"")
        val result = if (exists) request(context, "PUT", "/api/v1/notifications/targets/$TARGET_ID", target.toString())
        else request(context, "POST", "/api/v1/notifications/targets", target.toString())
        check(result.first == 200) { "notification target HTTP ${result.first}: ${result.second.take(200)}" }
    }

    /** Blocking call to Open WebUI with the session the app keeps (see SessionSync). */
    private fun request(context: Context, method: String, path: String, body: String? = null): Pair<Int, String> {
        ServerAddress.load(context)
        val base = ServerAddress.current.ifEmpty { ServerAddress.exterior ?: ServerAddress.local ?: "" }.trimEnd('/')
        val token = context.getSharedPreferences("session_sync", Context.MODE_PRIVATE).getString("token", null)
        check(base.isNotEmpty() && !token.isNullOrEmpty()) { "No session" }
        val connection = URL(base + path).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.setRequestProperty("Accept", "application/json")
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = connection.responseCode
            val text = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.readText().orEmpty()
            return code to text
        } finally {
            connection.disconnect()
        }
    }

    /** A message from Open WebUI: {"action":"chat","title":…,"message":…,"url":…/c/<id>}. */
    fun show(context: Context, raw: String) {
        val json = runCatching { JSONObject(raw) }.getOrNull()
        val title = json?.optString("title")?.takeIf { it.isNotBlank() } ?: context.getString(R.string.push_default_title)
        val message = json?.optString("message").orEmpty()
            .replace(Regex("<details\\b[^>]*>.*?</details>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)), "")
            .replace(Regex("[*_`#>]+"), "")
            .trim()
        val failed = json?.optString("action") == "chat_failed"
        val path = json?.optString("url")?.let { Uri.parse(it).path }?.takeIf { it.startsWith("/c/") }
        createChannel(context)
        val open = PendingIntent.getActivity(
            context, (path ?: "push").hashCode(),
            Intent(context, MainActivity::class.java)
                .setAction(IncomingIntents.ACTION_OPEN_PATH)
                .putExtra(IncomingIntents.EXTRA_PATH, path)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val text = if (failed) context.getString(R.string.push_failed_text) else message.ifEmpty { context.getString(R.string.push_default_text) }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text.take(1_000)))
            .setAutoCancel(true)
            .setContentIntent(open)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(("push:" + (path ?: title)).hashCode(), notification) }
    }

    fun acknowledge(context: Context, token: String, id: String?) {
        val distributor = prefs(context).getString("distributor", null) ?: return
        if (id == null) return
        context.sendBroadcast(Intent(DISTRIBUTOR_ACK).setPackage(distributor).putExtra("token", token).putExtra("id", id))
    }

    private fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, context.getString(R.string.notification_channel_name), NotificationManager.IMPORTANCE_HIGH).apply {
                description = context.getString(R.string.notification_channel_description)
                setSound(null, null)
                enableVibration(false)
            }
        )
    }
}

/** Receives the push address and the messages from the UnifiedPush distributor (ntfy). */
class PushReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val token = intent.getStringExtra("token")
        if (!PushNotices.isOurToken(context, token)) return
        when (intent.action) {
            PushNotices.NEW_ENDPOINT -> intent.getStringExtra("endpoint")?.let { PushNotices.onNewEndpoint(context, it) }
            PushNotices.MESSAGE -> {
                val bytes = intent.getByteArrayExtra("bytesMessage")
                val text = bytes?.toString(Charsets.UTF_8) ?: intent.getStringExtra("message").orEmpty()
                PushNotices.show(context, text)
                PushNotices.acknowledge(context, token!!, intent.getStringExtra("id"))
            }
            PushNotices.REGISTRATION_FAILED -> PushNotices.onFailed(context)
            PushNotices.UNREGISTERED -> PushNotices.onUnregistered(context)
        }
    }
}
