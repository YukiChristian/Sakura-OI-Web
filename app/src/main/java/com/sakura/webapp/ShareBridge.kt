package com.sakura.webapp

import android.content.Context
import android.net.Uri
import android.util.Base64
import android.util.Log
import android.webkit.JavascriptInterface
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * Holds files shared with Sakura until Open WebUI's chat box is ready, then hands them to
 * the page (`window.SakuraShare`). [ATTACH_JS] pastes them into Open WebUI's message box,
 * which uploads pasted files the same way as files chosen by hand. (Pasting avoids the
 * page's hidden file inputs, one of which imports chats.)
 */
class ShareBridge(
    private val context: Context,
    private val isTrustedPage: () -> Boolean,
    /** Called on a background thread when files are ready, or with a message when some were skipped. */
    private val onReady: (skipped: Int) -> Unit,
) {
    private val io = Executors.newSingleThreadExecutor()
    @Volatile private var pending: String? = null

    val hasPending: Boolean get() = pending != null

    /** Reads [uris] in the background; files over the size limits are skipped. */
    fun prepare(uris: List<Uri>) {
        if (uris.isEmpty()) return
        io.execute {
            val resolver = context.contentResolver
            val files = JSONArray()
            var total = 0L
            var skipped = 0
            for (uri in uris) {
                try {
                    val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: continue
                    if (bytes.size > MAX_FILE_BYTES || total + bytes.size > MAX_TOTAL_BYTES) {
                        skipped++
                        continue
                    }
                    total += bytes.size
                    files.put(JSONObject().apply {
                        put("name", IncomingIntents.displayName(resolver, uri))
                        put("type", resolver.getType(uri) ?: "application/octet-stream")
                        put("data", Base64.encodeToString(bytes, Base64.NO_WRAP))
                    })
                } catch (e: Exception) {
                    Log.w(TAG, "Could not read shared file", e)
                    skipped++
                }
            }
            pending = if (files.length() > 0) files.toString() else null
            onReady(skipped)
        }
    }

    /** Returns the waiting files as JSON once, then forgets them. */
    @JavascriptInterface
    fun take(): String? {
        if (!isTrustedPage()) return null
        val files = pending
        pending = null
        return files
    }

    companion object {
        const val JS_NAME = "SakuraShare"
        private const val TAG = "SakuraWebApp"
        private const val MAX_FILE_BYTES = 20L * 1024 * 1024
        private const val MAX_TOTAL_BYTES = 50L * 1024 * 1024

        /** Waits (up to 20 s) for the chat box, then pastes the files into it. */
        val ATTACH_JS = """
            (function() {
                if (!window.$JS_NAME || window.__sakuraAttaching) return;
                window.__sakuraAttaching = true;
                let tries = 0;
                (function attempt() {
                    const box = document.querySelector('#chat-input');
                    if (!box) {
                        if (++tries < 80) { setTimeout(attempt, 250); } else { window.__sakuraAttaching = false; }
                        return;
                    }
                    window.__sakuraAttaching = false;
                    const raw = window.$JS_NAME.take();
                    if (!raw) return;
                    const transfer = new DataTransfer();
                    JSON.parse(raw).forEach(function(f) {
                        const bin = atob(f.data);
                        const bytes = new Uint8Array(bin.length);
                        for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
                        transfer.items.add(new File([bytes], f.name, { type: f.type }));
                    });
                    const editor = box.matches('[contenteditable="true"], textarea')
                        ? box : (box.querySelector('[contenteditable="true"], textarea') || box);
                    editor.focus();
                    editor.dispatchEvent(new ClipboardEvent('paste', {
                        clipboardData: transfer, bubbles: true, cancelable: true
                    }));
                })();
            })();
        """.trimIndent()
    }
}
