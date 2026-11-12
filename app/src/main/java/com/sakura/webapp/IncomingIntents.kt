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

    class Request(
        /** Path on the server, or null for the last page the user had open. */
        val path: String?,
        /** Shared content: files to read and attach once the chat is ready. */
        val streams: List<Uri> = emptyList(),
    )

    fun parse(intent: Intent?): Request? {
        intent ?: return null
        return when (intent.action) {
            ACTION_NEW_CHAT -> Request("/")
            ACTION_VOICE, Intent.ACTION_ASSIST, Intent.ACTION_VOICE_COMMAND -> Request("/?call=true")
            ACTION_LAST_CHAT -> Request(null)
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
