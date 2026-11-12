package com.sakura.webapp

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Updates from GitHub Releases: a check once a day (and on demand from the settings), the
 * "Hay una actualización" message, and the download of the signed APK, which Android installs
 * over the current app. Personal builds ("0.8.0c") compare by their number, so they also hear of
 * a newer published version; both are signed with the same key, so one installs over the other.
 */
object Updates {
    private const val REPO = "YukiChristian/Sakura-OI-Web"
    private const val PREFS = "updates"
    private const val DAY = 24 * 60 * 60 * 1000L
    private const val TAG = "SakuraWebApp"

    data class Release(val version: String, val apkUrl: String?, val pageUrl: String)

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The version number without a personal suffix: "0.8.0c" -> "0.8.0". */
    val current: String get() = BuildConfig.VERSION_NAME.takeWhile { it.isDigit() || it == '.' }

    fun isAutomatic(context: Context) = prefs(context).getBoolean("auto", true)

    fun setAutomatic(context: Context, value: Boolean) = prefs(context).edit().putBoolean("auto", value).apply()

    fun lastCheck(context: Context) = prefs(context).getLong("last_check", 0L)

    /** True when [candidate] is a higher version than [installed] ("0.10.0" > "0.9.3"). */
    fun isNewer(candidate: String, installed: String): Boolean {
        val a = candidate.trimStart('v', 'V').split('.').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
        val b = installed.split('.').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    /** The newest release seen, if it is newer than this app. */
    fun available(context: Context): Release? {
        val p = prefs(context)
        val version = p.getString("version", null) ?: return null
        if (!isNewer(version, current)) return null
        return Release(version, p.getString("apk", null), p.getString("page", "https://github.com/$REPO/releases/latest")!!)
    }

    /** Latest published release on GitHub; blocking. */
    fun fetch(context: Context): Release {
        val connection = URL("https://api.github.com/repos/$REPO/releases/latest").openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("User-Agent", "SakuraOIWeb")
            check(connection.responseCode == HttpURLConnection.HTTP_OK) { "HTTP ${connection.responseCode}" }
            val json = JSONObject(connection.inputStream.bufferedReader().readText())
            val version = json.getString("tag_name").trimStart('v', 'V')
            val assets = json.optJSONArray("assets")
            val apk = (0 until (assets?.length() ?: 0)).map { assets!!.getJSONObject(it) }
                .firstOrNull { it.optString("name").endsWith(".apk") }?.optString("browser_download_url")
            val release = Release(version, apk, json.optString("html_url", "https://github.com/$REPO/releases/latest"))
            prefs(context).edit()
                .putString("version", release.version).putString("apk", release.apkUrl).putString("page", release.pageUrl)
                .putLong("last_check", System.currentTimeMillis())
                .apply()
            return release
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Once a day (when automatic checks are on): looks for a newer release in the background and
     * calls [onNewer] on the main thread if there is one. Also repeats a known pending update once a day.
     */
    fun checkIfDue(context: Context, onNewer: (Release) -> Unit) {
        val app = context.applicationContext
        if (!isAutomatic(app)) return
        val p = prefs(app)
        val now = System.currentTimeMillis()
        if (now - p.getLong("last_prompt", 0L) < DAY) return
        val main = Handler(Looper.getMainLooper())
        Thread {
            val release = if (now - lastCheck(app) >= DAY) runCatching { fetch(app) }.getOrNull() else null
            val newer = release?.takeIf { isNewer(it.version, current) } ?: available(app)
            if (newer != null) main.post {
                p.edit().putLong("last_prompt", System.currentTimeMillis()).apply()
                onNewer(newer)
            }
        }.start()
    }

    /**
     * Downloads the release's APK with Android's download manager and opens the installer when it
     * finishes; without an APK in the release, opens its page. Calls [onState] with progress messages.
     */
    fun install(context: Context, release: Release, onState: (String) -> Unit) {
        val app = context.applicationContext
        val url = release.apkUrl
        if (url == null) {
            app.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(release.pageUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        val manager = app.getSystemService(DownloadManager::class.java)
        val name = "SakuraOIWeb-${release.version}.apk"
        File(app.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), name).delete()
        val id = manager.enqueue(
            DownloadManager.Request(Uri.parse(url))
                .setTitle("Sakura OI Web ${release.version}")
                .setMimeType("application/vnd.android.package-archive")
                .setDestinationInExternalFilesDir(app, Environment.DIRECTORY_DOWNLOADS, name)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
        )
        onState(app.getString(R.string.update_downloading, release.version))
        val main = Handler(Looper.getMainLooper())
        Thread {
            while (true) {
                Thread.sleep(500)
                val status = manager.query(DownloadManager.Query().setFilterById(id))?.use { c ->
                    if (c.moveToFirst()) c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)) else null
                }
                when (status) {
                    DownloadManager.STATUS_SUCCESSFUL -> {
                        main.post { openInstaller(app, manager.getUriForDownloadedFile(id)) }
                        return@Thread
                    }
                    DownloadManager.STATUS_FAILED, null -> {
                        main.post { onState(app.getString(R.string.update_failed)) }
                        return@Thread
                    }
                }
            }
        }.start()
    }

    private fun openInstaller(context: Context, uri: Uri?) {
        uri ?: return
        try {
            context.startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
            Log.w(TAG, "Could not open the installer", e)
        }
    }
}
