package com.sakura.webapp

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Updates from GitHub Releases: a check once a day (and on demand from the settings), the
 * "Hay una actualización" message, and the download of the signed APK, which Android installs
 * over the current app. Personal builds ("0.8.0c") compare by their number, so they also hear of
 * a newer published version, and of the published version with their own number (see [isUpdate]);
 * both are signed with the same key, so one installs over the other.
 *
 * Personal builds also have a private channel (21-nov-2026): the newest «c» build waits on Christian's own
 * Open WebUI server (`/static/sakura-app/personal.json`, with the session), never on GitHub. Once that channel
 * has answered, a personal build is offered the newer «c» builds, and a published version only when it is
 * newer than every «c» build, so a published version no longer replaces the «c» with the same number.
 */
object Updates {
    private const val REPO = "YukiChristian/Sakura-OI-Web"
    private const val PREFS = "updates"
    private const val DAY = 24 * 60 * 60 * 1000L
    private const val TAG = "SakuraWebApp"

    /** [personal]: a «c» build from the private channel, downloaded with the session and checked by [sha256]. */
    data class Release(
        val version: String,
        val apkUrl: String?,
        val pageUrl: String,
        val personal: Boolean = false,
        val sha256: String? = null,
    )

    private const val PERSONAL_JSON = "/static/sakura-app/personal.json"
    private const val PERSONAL_APK = "/static/sakura-app/personal.apk"

    /** True for a personal build ("1.0 Beta 010c"). */
    val isPersonalBuild: Boolean get() = BuildConfig.VERSION_NAME.trim().endsWith("c")
    private fun withoutC(version: String) = version.trim().removeSuffix("c").trim()

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The version without a personal suffix: "0.8.0c" -> "0.8.0", "1.0 Beta 001c" -> "1.0 Beta 001". */
    val current: String get() = BuildConfig.VERSION_NAME.trim().removeSuffix("c").trim()

    fun isAutomatic(context: Context) = prefs(context).getBoolean("auto", true)

    fun setAutomatic(context: Context, value: Boolean) = prefs(context).edit().putBoolean("auto", value).apply()

    fun lastCheck(context: Context) = prefs(context).getLong("last_check", 0L)

    /**
     * True when [candidate] is a higher version than [installed]: "0.10.0" > "0.9.3", and from 1.0 the betas
     * ("1.0 Beta 001", tag "v1.0-Beta-001") go up by their number, below the final "1.0".
     */
    fun isNewer(candidate: String, installed: String): Boolean {
        val (a, betaA) = parse(candidate)
        val (b, betaB) = parse(installed)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return betaA > betaB
    }

    /**
     * True when [candidate] should be offered over the app installed as [installedName]: a higher version, or,
     * for a personal build ("1.0 Beta 008c"), also the published version with its own number ("1.0 Beta 008"),
     * which is the finished one the test build led to.
     */
    fun isUpdate(candidate: String, installedName: String): Boolean {
        val name = installedName.trim()
        val base = name.removeSuffix("c").trim()
        return isNewer(candidate, base) || (name.endsWith("c") && !isNewer(base, candidate))
    }

    /** The numbers of a version and its beta (Int.MAX_VALUE for a final version). */
    private fun parse(version: String): Pair<List<Int>, Int> {
        val text = version.trim().trimStart('v', 'V')
        val numbers = text.takeWhile { it.isDigit() || it == '.' }.split('.').map { it.toIntOrNull() ?: 0 }
        val beta = Regex("beta[ _.-]*(\\d+)", RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)?.toIntOrNull()
        return numbers to (beta ?: Int.MAX_VALUE)
    }

    /** The newest release seen, if it is newer than this app. */
    fun available(context: Context): Release? {
        val p = prefs(context)
        val installed = BuildConfig.VERSION_NAME
        val personal = if (isPersonalBuild) p.getString("p_version", null) else null
        if (personal != null && isNewer(withoutC(personal), withoutC(installed))) {
            return Release(personal, null, "", personal = true, sha256 = p.getString("p_sha256", null))
        }
        val version = p.getString("version", null) ?: return null
        if (!isUpdate(version, installed)) return null
        // the private channel answered: a published version only when it is newer than every «c» build
        if (personal != null && !(isNewer(version, withoutC(installed)) && isNewer(version, withoutC(personal)))) return null
        return Release(version, p.getString("apk", null), p.getString("page", "https://github.com/$REPO/releases/latest")!!)
    }

    /**
     * Reads both sources: the private channel (personal builds only) and GitHub. Blocking; throws only when
     * neither answered, so a GitHub without signal does not hide a «c» build (or the other way around).
     */
    fun fetch(context: Context) {
        val personal = if (isPersonalBuild) runCatching { fetchPersonal(context) } else null
        val github = runCatching { fetchGithub(context) }
        if (github.isFailure && personal?.isSuccess != true) throw github.exceptionOrNull()!!
    }

    /**
     * Newest «c» build on the private channel of Christian's server, saved for [available]. A server without
     * one (404) clears it; no session or no connection throws and keeps what was known.
     */
    private fun fetchPersonal(context: Context) {
        val (base, token) = ChatModels.session(context)
        val connection = URL(base + PERSONAL_JSON).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 8_000
            connection.readTimeout = 12_000
            connection.setRequestProperty("Authorization", "Bearer $token")
            val code = connection.responseCode
            if (code == HttpURLConnection.HTTP_NOT_FOUND) {
                prefs(context).edit().remove("p_version").remove("p_sha256").apply()
                return
            }
            check(code == HttpURLConnection.HTTP_OK) { "HTTP $code" }
            val json = JSONObject(connection.inputStream.bufferedReader().readText())
            val version = json.getString("version").trim()
            prefs(context).edit()
                .putString("p_version", version).putString("p_sha256", json.optString("sha256").ifEmpty { null })
                .putLong("last_check", System.currentTimeMillis())
                .apply()
        } finally {
            connection.disconnect()
        }
    }

    /** Latest published release on GitHub; blocking. */
    private fun fetchGithub(context: Context): Release {
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
            if (now - lastCheck(app) >= DAY) runCatching { fetch(app) }
            val newer = available(app)
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
        if (release.personal) return installPersonal(app, release, onState)
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
                .setTitle("${app.getString(R.string.app_name)} ${release.version}")
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

    /**
     * A «c» build from the private channel: the app downloads it itself, with the session (Android's download
     * manager would not send it, nor trust the home server's own certificate), checks its SHA-256 against the
     * channel's and opens the installer.
     */
    private fun installPersonal(app: Context, release: Release, onState: (String) -> Unit) {
        val main = Handler(Looper.getMainLooper())
        onState(app.getString(R.string.update_downloading, release.version))
        Thread {
            val file = runCatching {
                val (base, token) = ChatModels.session(app)
                val dir = File(app.cacheDir, "compartir").apply { mkdirs() }
                val target = File(dir, "SakuraOIWeb-${release.version.replace(' ', '-')}.apk")
                val connection = URL(base + PERSONAL_APK).openConnection() as HttpURLConnection
                try {
                    connection.connectTimeout = 10_000
                    connection.readTimeout = 30_000
                    connection.setRequestProperty("Authorization", "Bearer $token")
                    check(connection.responseCode == HttpURLConnection.HTTP_OK) { "HTTP ${connection.responseCode}" }
                    val digest = MessageDigest.getInstance("SHA-256")
                    connection.inputStream.use { input ->
                        target.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                val n = input.read(buffer)
                                if (n < 0) break
                                digest.update(buffer, 0, n)
                                output.write(buffer, 0, n)
                            }
                        }
                    }
                    val hash = digest.digest().joinToString("") { "%02x".format(it) }
                    if (release.sha256 != null && !hash.equals(release.sha256, ignoreCase = true)) {
                        target.delete()
                        error("SHA-256 does not match")
                    }
                } finally {
                    connection.disconnect()
                }
                target
            }.onFailure { Log.w(TAG, "Personal build download failed", it) }.getOrNull()
            main.post {
                if (file == null) onState(app.getString(R.string.update_failed))
                else openInstaller(app, FileProvider.getUriForFile(app, "${app.packageName}.files", file))
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
