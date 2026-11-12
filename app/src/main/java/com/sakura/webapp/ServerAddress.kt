package com.sakura.webapp

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.util.Log
import java.net.HttpURLConnection
import java.net.URL
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * The two addresses of the same Open WebUI server: an optional home-network one (local IP
 * and port) and an optional exterior one (public address, for example Tailscale). They are
 * set in the app's setup assistant and stored on the phone; the build-time values
 * (SAKURA_WEBUI_LAN_URL / SAKURA_WEBUI_URL) are only the starting point. On Wi-Fi the app
 * tries the home address first because it skips the internet round trip.
 */
object ServerAddress {
    private const val PREFS = "server_address"
    private const val KEY_SAVED = "saved"
    private const val KEY_LOCAL = "local"
    private const val KEY_EXTERIOR = "exterior"
    private const val KEY_VERSION = "version"

    @Volatile var local: String? = null
        private set
    @Volatile var exterior: String? = null
        private set

    /** The address the app is using right now. */
    @Volatile var current: String = ""
        private set

    val isConfigured: Boolean get() = local != null || exterior != null

    /** True while the home address is in use and an exterior one exists to fall back to. */
    val usingLan: Boolean get() = local != null && exterior != null && current == local

    /** Reads the saved addresses, or the build-time ones if the user never saved any. */
    fun load(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_SAVED, false)) {
            local = prefs.getString(KEY_LOCAL, null)
            exterior = prefs.getString(KEY_EXTERIOR, null)
        } else {
            local = normalize(BuildConfig.WEBUI_LAN_URL)
            exterior = normalize(BuildConfig.WEBUI_URL)
        }
        if (current.isEmpty() || !isServer(Uri.parse(current))) current = exterior ?: local ?: ""
    }

    /** Stores new addresses; [version] changes so open screens know to reload. */
    fun save(context: Context, newLocal: String?, newExterior: String?) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit()
            .putBoolean(KEY_SAVED, true)
            .putString(KEY_LOCAL, newLocal)
            .putString(KEY_EXTERIOR, newExterior)
            .putInt(KEY_VERSION, prefs.getInt(KEY_VERSION, 0) + 1)
            .apply()
        current = ""
        load(context)
    }

    fun version(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_VERSION, 0)

    /**
     * Turns what the user typed ("192.168.1.10:8081", "mi-pc.tailnet.ts.net",
     * "https://host:8443/") into "https://host:port/". HTTPS is assumed when no scheme is
     * given, because voice mode needs a secure page. Returns null for empty or invalid input.
     */
    fun normalize(input: String?): String? {
        val text = input?.trim().orEmpty()
        if (text.isEmpty()) return null
        val withScheme = if (text.contains("://")) text else "https://$text"
        val uri = runCatching { Uri.parse(withScheme) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase()
        val host = uri.host
        if (scheme !in setOf("https", "http") || host.isNullOrBlank() || host.contains(' ')) return null
        val port = if (uri.port != -1) ":${uri.port}" else ""
        return "$scheme://$host$port/"
    }

    /** True for pages served by either address of the configured server. */
    fun isServer(uri: Uri): Boolean =
        listOfNotNull(local, exterior).any { sameOrigin(uri, it) }

    /** [url] with the same path, query and fragment but on [base]. */
    fun rebase(url: String, base: String = current): String {
        val from = runCatching { Uri.parse(url) }.getOrNull() ?: return base
        if (!isServer(from)) return base
        val target = Uri.parse(base)
        return from.buildUpon()
            .scheme(target.scheme)
            .encodedAuthority(target.encodedAuthority)
            .build()
            .toString()
    }

    /** [path] (such as "/?call=true") on the current address. */
    fun at(path: String): String = current.trimEnd('/') + path

    /**
     * Picks the address for the current network and remembers it. [allowLan] is false until
     * the app has a session it can carry over (see SessionSync), so the user is not sent to
     * a sign-in page on the home address while an exterior one exists. Blocks for up to about
     * a second on Wi-Fi while probing the home address, so call it off the main thread.
     */
    fun choose(context: Context, allowLan: Boolean = true): String {
        val home = local
        val outside = exterior
        current = when {
            home == null -> outside ?: ""
            outside == null -> home
            allowLan && onLocalNetwork(context) && check(context, home) == null -> home
            else -> outside
        }
        return current
    }

    private fun onLocalNetwork(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    /**
     * Asks Open WebUI's /health endpoint whether [base] is up. Returns null when it answers,
     * or a short reason that fits a status pill. Blocking.
     */
    fun check(context: Context, base: String, timeoutMs: Int = PROBE_TIMEOUT_MS): String? {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(base.trimEnd('/') + "/health").openConnection() as HttpURLConnection).apply {
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                instanceFollowRedirects = false
            }
            val code = connection.responseCode
            if (code == HttpURLConnection.HTTP_OK) null else context.getString(R.string.check_http_error, code)
        } catch (e: SSLException) {
            context.getString(R.string.check_certificate)
        } catch (e: UnknownHostException) {
            context.getString(R.string.check_not_found)
        } catch (e: Exception) {
            Log.i(TAG, "Address check failed", e)
            if (e.message?.contains("CLEARTEXT", ignoreCase = true) == true) {
                context.getString(R.string.check_cleartext)
            } else {
                context.getString(R.string.check_no_answer)
            }
        } finally {
            connection?.disconnect()
        }
    }

    private fun sameOrigin(uri: Uri, base: String): Boolean {
        val b = Uri.parse(base)
        return uri.scheme.equals(b.scheme, ignoreCase = true) &&
            uri.host.equals(b.host, ignoreCase = true) &&
            port(uri) == port(b)
    }

    /** A URL without a port (such as a Tailscale address) uses the scheme's default. */
    private fun port(uri: Uri): Int = when {
        uri.port != -1 -> uri.port
        uri.scheme.equals("http", ignoreCase = true) -> 80
        else -> 443
    }

    private const val PROBE_TIMEOUT_MS = 1_200
    private const val TAG = "SakuraWebApp"
}
