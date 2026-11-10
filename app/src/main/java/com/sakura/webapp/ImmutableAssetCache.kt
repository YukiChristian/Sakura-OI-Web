package com.sakura.webapp

import android.content.Context
import android.net.Uri
import android.util.Log
import android.webkit.MimeTypeMap
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import java.io.File
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Keeps Open WebUI's build files (`/_app/immutable/...`) on the phone for good. Their names
 * carry a content hash, so a file never changes once published, but the server sends them
 * without cache headers or compression and the WebView downloads some of them again on
 * every start (the main bundle alone is about 760 KB). Serving them from disk saves that
 * transfer each time the app opens.
 */
class ImmutableAssetCache(
    context: Context,
    /** True when [uri] belongs to the configured Open WebUI server. */
    private val isOpenWebUi: (Uri) -> Boolean,
) {
    private val dir = File(context.cacheDir, "webui-immutable").apply { mkdirs() }

    /** Called from WebViewClient.shouldInterceptRequest, which runs off the main thread. */
    fun intercept(request: WebResourceRequest): WebResourceResponse? {
        val uri = request.url
        if (!request.method.equals("GET", ignoreCase = true)) return null
        if (!isOpenWebUi(uri) || uri.path?.startsWith(PREFIX) != true) return null
        if (!uri.query.isNullOrEmpty()) return null
        val file = File(dir, keyFor(uri.toString()))
        if (!file.exists() && !download(uri.toString(), file)) return null
        file.setLastModified(System.currentTimeMillis())
        return response(file, uri.lastPathSegment.orEmpty())
    }

    /** Drops files not used for a month, so old Open WebUI versions do not pile up. */
    fun prune() {
        val cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(30)
        dir.listFiles()?.forEach { if (it.lastModified() < cutoff) it.delete() }
    }

    private fun download(url: String, target: File): Boolean {
        val temp = File(dir, target.name + ".part")
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
            }
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return false
            connection.inputStream.use { input -> temp.outputStream().use { input.copyTo(it) } }
            temp.renameTo(target)
        } catch (e: Exception) {
            Log.w(TAG, "Could not cache $url", e)
            false
        } finally {
            connection?.disconnect()
            temp.delete()
        }
    }

    private fun response(file: File, name: String): WebResourceResponse {
        val ext = name.substringAfterLast('.', "").lowercase()
        val mime = when (ext) {
            "js", "mjs" -> "text/javascript"
            "css" -> "text/css"
            "json" -> "application/json"
            else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
        }
        val encoding = if (mime.startsWith("text/") || mime == "application/json") "utf-8" else null
        return WebResourceResponse(mime, encoding, 200, "OK", CACHED_HEADERS, FileInputStream(file))
    }

    private fun keyFor(url: String): String =
        MessageDigest.getInstance("SHA-1").digest(url.toByteArray())
            .joinToString("") { "%02x".format(it) }

    private companion object {
        const val PREFIX = "/_app/immutable/"
        const val TAG = "SakuraWebApp"
        val CACHED_HEADERS = mapOf("Cache-Control" to "public, max-age=31536000, immutable")
    }
}
