package com.sakura.webapp

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns

/**
 * Turns what Android hands the app (a share, selected text, a launcher shortcut or the
 * assistant gesture) into the Open WebUI page to open and any files to attach.
 * Open WebUI fills its message box from `?q=` and starts voice mode with `?call=true`.
 */
object IncomingIntents {
    const val ACTION_NEW_CHAT = "com.sakura.webapp.action.NEW_CHAT"
    const val ACTION_VOICE = "com.sakura.webapp.action.VOICE"
    const val ACTION_LAST_CHAT = "com.sakura.webapp.action.LAST_CHAT"
    /** Opens a page of Open WebUI (a chat from a push notice): EXTRA_PATH = "/c/<id>". */
    const val ACTION_OPEN_PATH = "com.sakura.webapp.action.OPEN_PATH"
    const val EXTRA_PATH = "com.sakura.webapp.extra.PATH"
    /** Open WebUI model id to use for voice mode or a new chat (from the widgets). */
    const val EXTRA_MODEL = "com.sakura.webapp.extra.MODEL"
    /** Sent by headsets and Bluetooth earbuds (long press) to the voice command app. */
    private const val VOICE_SEARCH_HANDS_FREE = "android.speech.action.VOICE_SEARCH_HANDS_FREE"

    class Request(
        /** Path on the server, or null for the last page the user had open. */
        val path: String?,
        /** Shared content: files to read and attach once the chat is ready. */
        val streams: List<Uri> = emptyList(),
    )

    fun parse(intent: Intent?): Request? {
        intent ?: return null
        return when (intent.action) {
            ACTION_NEW_CHAT -> Request(withModel("/", intent))
            ACTION_VOICE, Intent.ACTION_ASSIST, Intent.ACTION_VOICE_COMMAND, VOICE_SEARCH_HANDS_FREE ->
                Request(withModel("/?call=true", intent))
            ACTION_LAST_CHAT -> Request(null)
            ACTION_OPEN_PATH -> Request(intent.getStringExtra(EXTRA_PATH)?.takeIf { it.startsWith("/c/") })
            Intent.ACTION_PROCESS_TEXT -> {
                val text = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
                if (text.isNullOrBlank()) Request("/") else Request(newChatWith(text))
            }
            Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE -> parseShare(intent)
            else -> null
        }
    }

    private fun parseShare(intent: Intent): Request {
        val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT)
        val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
        val message = listOfNotNull(subject?.takeIf { it.isNotBlank() && text?.contains(it) != true }, text)
            .filter { it.isNotBlank() }
            .joinToString("\n")
        val streams = if (intent.action == Intent.ACTION_SEND_MULTIPLE) {
            streamList(intent)
        } else {
            listOfNotNull(stream(intent))
        }
        return Request(if (message.isBlank()) "/" else newChatWith(message), streams)
    }

    /** Adds Open WebUI's ?models= for the model the widget chose; "/?models=lain&call=true". */
    private fun withModel(path: String, intent: Intent): String {
        val model = intent.getStringExtra(EXTRA_MODEL)?.takeIf { it.isNotBlank() } ?: return path
        val param = "models=" + Uri.encode(model)
        return if (path.contains('?')) path.replaceFirst("?", "?$param&") else "$path?$param"
    }

    private fun newChatWith(text: String): String = "/?q=" + Uri.encode(text.take(MAX_TEXT))

    @Suppress("DEPRECATION")
    private fun stream(intent: Intent): Uri? =
        if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else intent.getParcelableExtra(Intent.EXTRA_STREAM)

    @Suppress("DEPRECATION")
    private fun streamList(intent: Intent): List<Uri> =
        (if (Build.VERSION.SDK_INT >= 33) intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)).orEmpty().take(MAX_FILES)

    /** Display name of a shared file, falling back to the last path segment. */
    fun displayName(resolver: ContentResolver, uri: Uri): String {
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0)?.let { return it }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "archivo"
    }

    private const val MAX_TEXT = 20_000
    const val MAX_FILES = 10
}
