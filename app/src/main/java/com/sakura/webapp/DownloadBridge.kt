package com.sakura.webapp

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.MimeTypeMap
import java.io.File
import java.util.concurrent.Executors

/**
 * Android WebView ignores `<a download href="blob:...">`, which is how Open WebUI (through
 * file-saver) saves generated images and exported files, so the Download button did nothing.
 * [SHIM_JS] catches those links, reads the file in the page and hands it to this bridge
 * (`window.SakuraDownloads`), which writes it to Downloads/Sakura OI Web.
 */
class DownloadBridge(
    private val context: Context,
    /** True only while the WebView shows the trusted Open WebUI origin. */
    private val isTrustedPage: () -> Boolean,
    /** Android 9 and older need WRITE_EXTERNAL_STORAGE; returns false after asking for it. */
    private val hasLegacyStoragePermission: () -> Boolean,
    /**
     * Called on a background thread with the saved file name, or null and a reason on failure.
     * Both null means the save waits for the storage permission.
     */
    private val onResult: (savedName: String?, error: String?) -> Unit,
) {
    private val io = Executors.newSingleThreadExecutor()

    @JavascriptInterface
    fun save(fileName: String?, mimeType: String?, base64: String) {
        if (!isTrustedPage()) return
        io.execute {
            try {
                val bytes = Base64.decode(base64, Base64.DEFAULT)
                onResult(saveBytes(bytes, fileName, mimeType), null)
            } catch (e: Exception) {
                Log.w(TAG, "Download failed", e)
                onResult(null, e.message ?: e.javaClass.simpleName)
            }
        }
    }

    @JavascriptInterface
    fun failed(reason: String?) {
        if (!isTrustedPage()) return
        Log.w(TAG, "Download failed in page: $reason")
        onResult(null, reason ?: "")
    }

    /** Saves a `data:` URL that reached the WebView's download listener directly. */
    fun saveDataUrl(dataUrl: String, fileName: String?) {
        val comma = dataUrl.indexOf(',')
        if (comma < 0) return
        val header = dataUrl.substring(5, comma)
        val payload = dataUrl.substring(comma + 1)
        io.execute {
            try {
                val bytes = if (header.endsWith(";base64")) {
                    Base64.decode(payload, Base64.DEFAULT)
                } else {
                    Uri.decode(payload).toByteArray()
                }
                onResult(saveBytes(bytes, fileName, header.substringBefore(';')), null)
            } catch (e: Exception) {
                Log.w(TAG, "Download failed", e)
                onResult(null, e.message ?: e.javaClass.simpleName)
            }
        }
    }

    private fun saveBytes(bytes: ByteArray, requestedName: String?, mimeType: String?): String? {
        val mime = mimeType?.takeIf { it.isNotBlank() } ?: "application/octet-stream"
        val name = fileNameFor(requestedName, mime)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$FOLDER")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("MediaStore rejected $name")
            try {
                resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: error("No output stream")
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                throw e
            }
            return name
        }
        if (!hasLegacyStoragePermission()) return null
        @Suppress("DEPRECATION")
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), FOLDER)
        dir.mkdirs()
        val file = uniqueFile(dir, name)
        file.writeBytes(bytes)
        return file.name
    }

    /**
     * Open WebUI names every image "generated_image.png", so each download gets its own key
     * instead: the date plus four random letters or digits (20261108Bn21.png). The extension
     * comes from the page's file name, or from the MIME type when it has none.
     */
    private fun fileNameFor(requested: String?, mime: String): String {
        val requestedExt = requested.orEmpty()
            .substringAfterLast('/')
            .substringAfterLast('.', "")
            .lowercase()
            .takeIf { it.isNotEmpty() && it.length <= 8 && it.all(Char::isLetterOrDigit) }
        val ext = requestedExt ?: MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)
        val key = downloadKey()
        return if (ext != null) "$key.$ext" else key
    }

    private fun uniqueFile(dir: File, name: String): File {
        var file = File(dir, name)
        var n = 1
        while (file.exists()) {
            file = File(dir, "${name.substringBeforeLast('.')} ($n).${name.substringAfterLast('.', "")}".trimEnd('.'))
            n++
        }
        return file
    }

    companion object {
        const val JS_NAME = "SakuraDownloads"
        const val FOLDER = "Sakura OI Web"
        private const val TAG = "SakuraWebApp"
        private const val KEY_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        private val random = java.security.SecureRandom()

        /** Today's date as yyyyMMdd followed by four random letters or digits, e.g. 20261108Bn21. */
        fun downloadKey(): String {
            val date = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(java.util.Date())
            val suffix = String(CharArray(4) { KEY_CHARS[random.nextInt(KEY_CHARS.length)] })
            return date + suffix
        }

        /**
         * Intercepts download links (file-saver creates an `<a download>` and clicks it, often
         * without adding it to the page), reads blob:, data: and same-origin files as base64
         * and passes them to [save]. `window.__sakuraSaveUrl` lets the activity reuse it for
         * blob: URLs that reach the WebView's download listener.
         */
        val SHIM_JS = """
            (function() {
                if (window.__sakuraDownloads || !window.$JS_NAME) return;
                window.__sakuraDownloads = true;
                function isSaveable(href) {
                    if (!href) return false;
                    if (href.startsWith('blob:') || href.startsWith('data:')) return true;
                    try { return new URL(href, location.href).origin === location.origin; } catch (e) { return false; }
                }
                function wanted(a) {
                    return !!a && a.hasAttribute && a.hasAttribute('download') && isSaveable(a.href);
                }
                function saveUrl(href, name) {
                    fetch(href, { credentials: 'include' })
                        .then(function(r) {
                            if (!r.ok) throw new Error('HTTP ' + r.status);
                            return r.blob();
                        })
                        .then(function(blob) {
                            return new Promise(function(resolve, reject) {
                                const reader = new FileReader();
                                reader.onload = function() { resolve([blob.type, reader.result]); };
                                reader.onerror = function() { reject(reader.error); };
                                reader.readAsDataURL(blob);
                            });
                        })
                        .then(function(result) {
                            const dataUrl = String(result[1]);
                            window.$JS_NAME.save(name || '', result[0] || '', dataUrl.substring(dataUrl.indexOf(',') + 1));
                        })
                        .catch(function(error) {
                            window.$JS_NAME.failed(String((error && error.message) || error));
                        });
                }
                window.__sakuraSaveUrl = saveUrl;
                function save(a) { saveUrl(a.href, a.getAttribute('download')); }
                const originalClick = HTMLAnchorElement.prototype.click;
                HTMLAnchorElement.prototype.click = function() {
                    if (wanted(this)) { save(this); return; }
                    return originalClick.apply(this, arguments);
                };
                HTMLAnchorElement.prototype.dispatchEvent = function(event) {
                    if (event && event.type === 'click' && wanted(this)) { save(this); return false; }
                    return EventTarget.prototype.dispatchEvent.call(this, event);
                };
                document.addEventListener('click', function(event) {
                    const a = event.target && event.target.closest ? event.target.closest('a') : null;
                    if (!wanted(a)) return;
                    event.preventDefault();
                    event.stopImmediatePropagation();
                    save(a);
                }, true);
                // The image viewer's pinch-zoom (panzoom) calls preventDefault() on touchstart,
                // so a tap on its Download button never becomes a click. Replay short taps on
                // buttons inside dialogs whose touchstart was cancelled that way.
                let tap = null;
                document.addEventListener('touchstart', function(event) {
                    const button = event.target && event.target.closest ? event.target.closest('[role="dialog"] button') : null;
                    tap = button && event.touches.length === 1 ? {
                        event: event, button: button, x: event.touches[0].clientX, y: event.touches[0].clientY, time: Date.now()
                    } : null;
                }, true);
                document.addEventListener('touchend', function(event) {
                    const t = tap;
                    tap = null;
                    if (!t || !t.event.defaultPrevented || Date.now() - t.time > 600) return;
                    const touch = event.changedTouches[0];
                    if (!touch || Math.abs(touch.clientX - t.x) > 12 || Math.abs(touch.clientY - t.y) > 12) return;
                    if (!t.button.contains(document.elementFromPoint(touch.clientX, touch.clientY))) return;
                    setTimeout(function() { t.button.click(); }, 0);
                }, true);
            })();
        """.trimIndent()
    }
}
