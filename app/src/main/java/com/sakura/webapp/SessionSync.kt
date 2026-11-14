package com.sakura.webapp

import android.content.Context
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.JavascriptInterface

/**
 * The browser keeps a separate session for each address, so switching from the exterior
 * address to the home one would ask the user to sign in again. Both addresses are the same
 * Open WebUI server and accept the same token, so the app carries the token across.
 * A sign-out on the address where the token was saved is respected: the copy is dropped.
 */
class SessionSync(
    private val context: Context,
    private val isTrustedPage: () -> Boolean,
    /** The first sign-in was seen: the home address can be used from now on. */
    private val onFirstToken: () -> Unit,
) {
    private val prefs = context.getSharedPreferences("session_sync", Context.MODE_PRIVATE)

    /** True once a signed-in page has reported its token, so the other address can reuse it. */
    val hasToken: Boolean get() = prefs.getString(KEY_TOKEN, null) != null

    /** Called by [SYNC_JS] with the page's origin and its token ("" when signed out). */
    @JavascriptInterface
    fun report(origin: String, token: String): String {
        if (!isTrustedPage() || !ServerAddress.isServer(Uri.parse(origin))) return ""
        val savedToken = prefs.getString(KEY_TOKEN, null)
        val savedOrigin = prefs.getString(KEY_ORIGIN, null)
        if (token.isNotEmpty()) {
            if (token != savedToken) {
                prefs.edit().putString(KEY_TOKEN, token).putString(KEY_ORIGIN, origin).remove(KEY_COPIED_TO).apply()
                if (savedToken == null) onFirstToken()
            }
            // Signed in: keep the widgets' list of models current.
            ChatModels.refresh(context)
            // A page of the server answered: the widgets drop «Tu PC no responde».
            PcStatus.markOnline(context)
            return ""
        }
        if (savedToken == null) return ""
        if (origin == savedOrigin) {
            // Signed out where the token came from: forget it everywhere.
            prefs.edit().clear().apply()
            return ""
        }
        if (prefs.getString(KEY_COPIED_TO, null) == origin) return ""
        prefs.edit().putString(KEY_COPIED_TO, origin).apply()
        CookieManager.getInstance().setCookie(origin, "token=$savedToken; Path=/; Secure; SameSite=Lax")
        return savedToken
    }

    companion object {
        const val JS_NAME = "SakuraSession"
        private const val KEY_TOKEN = "token"
        private const val KEY_ORIGIN = "origin"
        private const val KEY_COPIED_TO = "copied_to"

        /** Reports the session; if the app hands back a token, stores it and reloads once. */
        val SYNC_JS = """
            (function() {
                if (!window.$JS_NAME) return;
                const token = localStorage.getItem('token') || '';
                const copy = window.$JS_NAME.report(location.origin, token);
                if (copy && !token) {
                    localStorage.setItem('token', copy);
                    location.reload();
                }
            })();
        """.trimIndent()
    }
}
