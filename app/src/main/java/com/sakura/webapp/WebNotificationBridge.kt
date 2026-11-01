package com.sakura.webapp

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import android.webkit.JavascriptInterface
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * Android WebView does not implement the Web Notifications API, so Open WebUI's
 * `new Notification(...)` calls never reach the system. This bridge is exposed to the
 * page as `window.SakuraNotifications` and a JS shim (see [SHIM_JS]) installs a
 * `window.Notification` replacement that forwards to it.
 */
class WebNotificationBridge(
    private val context: Context,
    /** True only while the WebView shows the trusted Open WebUI origin. */
    private val isTrustedPage: () -> Boolean,
    /** Asks the activity to request POST_NOTIFICATIONS; reports the resulting state. */
    private val requestSystemPermission: (requestId: String) -> Unit,
) {
    private val prefs = context.getSharedPreferences("web_notifications", Context.MODE_PRIVATE)

    init {
        createChannel()
    }

    @JavascriptInterface
    fun permission(): String {
        if (!isTrustedPage()) return "denied"
        return currentPermission()
    }

    @JavascriptInterface
    fun requestPermission(requestId: String) {
        if (!isTrustedPage()) return
        requestSystemPermission(requestId)
    }

    @JavascriptInterface
    fun show(id: String, title: String, body: String?, tag: String?): Boolean {
        if (!isTrustedPage() || currentPermission() != "granted") {
            Log.w(TAG, "Notification skipped: trusted=${isTrustedPage()}, permission=${currentPermission()}")
            return false
        }
        val openApp = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ContextCompat.getColor(context, R.color.notification_accent))
            .setContentTitle(title)
            .setContentText(body.orEmpty())
            .setStyle(NotificationCompat.BigTextStyle().bigText(body.orEmpty()))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openApp)
            .build()
        return try {
            NotificationManagerCompat.from(context).notify(notificationId(id, tag), notification)
            true
        } catch (_: SecurityException) {
            false
        }
    }

    @JavascriptInterface
    fun close(id: String, tag: String?) {
        NotificationManagerCompat.from(context).cancel(notificationId(id, tag))
    }

    fun currentPermission(): String {
        val granted = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        return when {
            granted && NotificationManagerCompat.from(context).areNotificationsEnabled() -> "granted"
            prefs.getBoolean(KEY_ASKED, false) -> "denied"
            else -> "default"
        }
    }

    fun markAsked() {
        prefs.edit().putBoolean(KEY_ASKED, true).apply()
    }

    /** A tag replaces the previous notification with the same tag, as on the web. */
    private fun notificationId(id: String, tag: String?): Int =
        (if (tag.isNullOrEmpty()) "id:$id" else "tag:$tag").hashCode()

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply { description = context.getString(R.string.notification_channel_description) }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        const val JS_NAME = "SakuraNotifications"
        private const val CHANNEL_ID = "open_webui"
        private const val KEY_ASKED = "asked"
        private const val TAG = "SakuraWebApp"

        /** Resolves a pending `Notification.requestPermission()` promise in the page. */
        fun resolveScript(requestId: String, state: String): String =
            "window.__sakuraNotifyResolve && window.__sakuraNotifyResolve(" +
                "'${requestId.filter { it.isLetterOrDigit() || it == '_' }}', '$state');"

        val SHIM_JS = """
            (function() {
                var bridge = window.$JS_NAME;
                if (!bridge || window.__sakuraNotificationShim) return;
                window.__sakuraNotificationShim = true;
                var pending = {};
                var seq = 0;
                function nextId() { return 'n' + Date.now() + '_' + (++seq); }
                function fire(target, type) {
                    var handler = target['on' + type];
                    var event;
                    try { event = new Event(type); } catch (e) { event = { type: type }; }
                    if (typeof handler === 'function') {
                        try { handler.call(target, event); } catch (e) { console.error(e); }
                    }
                    if (target.dispatchEvent && event instanceof Event) target.dispatchEvent(event);
                }
                function SakuraNotification(title, options) {
                    if (!(this instanceof SakuraNotification)) {
                        throw new TypeError("Failed to construct 'Notification': Please use the 'new' operator.");
                    }
                    var self = (typeof EventTarget === 'function') ? Reflect.construct(EventTarget, [], SakuraNotification) : this;
                    options = options || {};
                    self.title = String(title);
                    self.body = options.body == null ? '' : String(options.body);
                    self.tag = options.tag == null ? '' : String(options.tag);
                    self.icon = options.icon || '';
                    self.data = options.data;
                    self.onclick = self.onshow = self.onclose = self.onerror = null;
                    self.__id = nextId();
                    var shown = bridge.show(self.__id, self.title, self.body, self.tag);
                    setTimeout(function() { fire(self, shown ? 'show' : 'error'); }, 0);
                    return self;
                }
                if (typeof EventTarget === 'function') {
                    SakuraNotification.prototype = Object.create(EventTarget.prototype);
                    SakuraNotification.prototype.constructor = SakuraNotification;
                }
                SakuraNotification.prototype.close = function() {
                    bridge.close(this.__id, this.tag);
                    fire(this, 'close');
                };
                Object.defineProperty(SakuraNotification, 'permission', {
                    get: function() { return bridge.permission(); }
                });
                SakuraNotification.maxActions = 0;
                SakuraNotification.requestPermission = function(callback) {
                    var current = bridge.permission();
                    var promise;
                    if (current !== 'default') {
                        promise = Promise.resolve(current);
                    } else {
                        promise = new Promise(function(resolve) {
                            var id = nextId();
                            pending[id] = resolve;
                            bridge.requestPermission(id);
                        });
                    }
                    if (typeof callback === 'function') promise.then(callback);
                    return promise;
                };
                window.__sakuraNotifyResolve = function(id, state) {
                    var resolve = pending[id];
                    delete pending[id];
                    if (resolve) resolve(state);
                };
                window.Notification = SakuraNotification;
            })();
        """.trimIndent()
    }
}
