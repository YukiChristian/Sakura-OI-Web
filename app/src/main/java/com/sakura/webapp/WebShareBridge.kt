package com.sakura.webapp

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import android.util.Log
import android.webkit.JavascriptInterface
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File

/**
 * Android WebView has no Web Share API (`navigator.share`), so Sakura's Compartir buttons (the image
 * viewer, the file viewer) fell back to opening the file. [SHIM_JS] gives the page `navigator.share` and
 * `navigator.canShare`; this bridge (`window.SakuraWebShare`) writes the shared files to the app's cache
 * and opens Android's own share menu with them.
 */
class WebShareBridge(
    private val context: Context,
    /** True only while the WebView shows the trusted Open WebUI origin. */
    private val isTrustedPage: () -> Boolean,
    /** Opens the share menu (on the main thread, from the activity). */
    private val launch: (Intent) -> Unit,
) {
    /** Returns "" when the share menu opens, or the reason it could not. */
    @JavascriptInterface
    fun share(json: String): String {
        if (!isTrustedPage()) return "not allowed"
        return try {
            val data = JSONObject(json)
            val title = data.optString("title")
            val text = listOf(data.optString("text"), data.optString("url")).filter { it.isNotBlank() }.joinToString("\n")
            val files = data.optJSONArray("files")
            val uris = ArrayList<Uri>()
            val types = mutableListOf<String>()
            if (files != null && files.length() > 0) {
                val dir = freshFolder()
                for (k in 0 until files.length()) {
                    val f = files.getJSONObject(k)
                    val type = f.optString("type").ifBlank { "application/octet-stream" }
                    val file = File(dir, safeName(f.optString("name"), type, k))
                    file.writeBytes(Base64.decode(f.getString("data"), Base64.DEFAULT))
                    uris += FileProvider.getUriForFile(context, context.packageName + AUTHORITY_SUFFIX, file)
                    types += type
                }
            }
            if (uris.isEmpty() && text.isBlank()) return "nothing to share"
            val send = when {
                uris.size > 1 -> Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                uris.size == 1 -> Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
                else -> Intent(Intent.ACTION_SEND)
            }.apply {
                type = when {
                    uris.isEmpty() -> "text/plain"
                    types.distinct().size == 1 -> types[0]
                    types.all { it.startsWith("image/") } -> "image/*"
                    else -> "*/*"
                }
                if (text.isNotBlank()) putExtra(Intent.EXTRA_TEXT, text)
                if (title.isNotBlank()) putExtra(Intent.EXTRA_SUBJECT, title)
                if (uris.isNotEmpty()) {
                    // The clip lets the share menu show the image and grants the chosen app access to it.
                    clipData = ClipData.newRawUri(title, uris[0]).also { clip ->
                        uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
                    }
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }
            val chooser = Intent.createChooser(send, title.ifBlank { null }).apply {
                if (uris.isNotEmpty()) addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            launch(chooser)
            ""
        } catch (e: Exception) {
            Log.w(TAG, "Share failed", e)
            e.message ?: e.javaClass.simpleName
        }
    }

    /** A new folder for this share; folders from shares over an hour old are removed. */
    private fun freshFolder(): File {
        val root = File(context.cacheDir, FOLDER)
        val old = System.currentTimeMillis() - 60 * 60 * 1000L
        root.listFiles()?.filter { it.lastModified() < old }?.forEach { it.deleteRecursively() }
        return File(root, System.currentTimeMillis().toString()).apply { mkdirs() }
    }

    private fun safeName(name: String, type: String, index: Int): String {
        val clean = name.substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("[^\\p{L}\\p{N} ._()-]"), "_").trim().take(80)
        if (clean.isNotEmpty() && clean.trim('.').isNotEmpty()) return clean
        val ext = android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(type)
        return "sakura-${index + 1}" + if (ext != null) ".$ext" else ""
    }

    companion object {
        private const val TAG = "WebShareBridge"
        const val JS_NAME = "SakuraWebShare"
        const val AUTHORITY_SUFFIX = ".files"
        const val FOLDER = "compartir"

        val SHIM_JS = """
            (function() {
                if (window.__sakuraWebShare || !window.$JS_NAME || navigator.share) return;
                window.__sakuraWebShare = true;
                function read(file) {
                    return new Promise(function(resolve, reject) {
                        const reader = new FileReader();
                        reader.onload = function() {
                            const s = String(reader.result);
                            resolve({ name: file.name || '', type: file.type || '', data: s.substring(s.indexOf(',') + 1) });
                        };
                        reader.onerror = function() { reject(reader.error); };
                        reader.readAsDataURL(file);
                    });
                }
                navigator.canShare = function(data) {
                    if (!data) return false;
                    const files = data.files ? Array.from(data.files) : [];
                    if (files.some(function(f) { return !(f instanceof Blob); })) return false;
                    return !!(data.title || data.text || data.url || files.length);
                };
                navigator.share = function(data) {
                    data = data || {};
                    if (!navigator.canShare(data)) return Promise.reject(new TypeError('Nothing to share'));
                    let url = '';
                    if (data.url) {
                        try { url = new URL(data.url, location.href).href; }
                        catch (e) { return Promise.reject(new TypeError('Invalid URL')); }
                    }
                    return Promise.all(Array.from(data.files || []).map(read)).then(function(files) {
                        const error = window.$JS_NAME.share(JSON.stringify({
                            title: String(data.title || ''), text: String(data.text || ''), url: url, files: files
                        }));
                        if (error) throw new DOMException(error, 'DataError');
                    });
                };
            })();
        """
    }
}
