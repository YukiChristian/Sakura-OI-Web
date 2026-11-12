package com.sakura.webapp

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * The offline Spanish speech model used to hear "Oye, Sakura". It is downloaded once
 * (about 39 MB) when the user turns the feature on, so the APK stays small.
 */
object WakeWordModel {
    private const val MODEL_URL = "https://alphacephei.com/vosk/models/vosk-model-small-es-0.42.zip"
    private const val PREFS = "wake_word"
    private const val KEY_ENABLED = "enabled"

    fun dir(context: Context) = File(context.filesDir, "vosk-model-es")

    fun isReady(context: Context) = File(dir(context), "am").isDirectory

    fun isEnabled(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    /** Downloads and unpacks the model; blocking, reports progress (0 to 100) and the bytes read of the total. */
    fun download(context: Context, onProgress: (percent: Int, read: Long, total: Long) -> Unit) {
        val target = dir(context)
        val temp = File(context.filesDir, "vosk-model-es.part").apply { deleteRecursively(); mkdirs() }
        val connection = URL(MODEL_URL).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 20_000
            connection.readTimeout = 60_000
            check(connection.responseCode == HttpURLConnection.HTTP_OK) { "HTTP ${connection.responseCode}" }
            val total = connection.contentLengthLong.takeIf { it > 0 } ?: -1L
            var read = 0L
            val counting = object : java.io.FilterInputStream(connection.inputStream) {
                override fun read(b: ByteArray, off: Int, len: Int): Int =
                    super.read(b, off, len).also { n ->
                        if (n > 0 && total > 0) {
                            read += n
                            onProgress((read * 100 / total).toInt(), read, total)
                        }
                    }
            }
            ZipInputStream(counting.buffered()).use { zip ->
                val root = temp.canonicalPath + File.separator
                while (true) {
                    val entry = zip.nextEntry ?: break
                    // Drop the archive's top folder ("vosk-model-small-es-0.42/...").
                    val relative = entry.name.substringAfter('/', "")
                    if (relative.isEmpty()) continue
                    val out = File(temp, relative)
                    require(out.canonicalPath.startsWith(root)) { "Bad zip entry" }
                    if (entry.isDirectory) out.mkdirs()
                    else {
                        out.parentFile?.mkdirs()
                        out.outputStream().use { zip.copyTo(it) }
                    }
                }
            }
            target.deleteRecursively()
            check(temp.renameTo(target)) { "Could not move the model" }
        } finally {
            connection.disconnect()
            temp.deleteRecursively()
        }
    }
}

/**
 * Listens for "Oye, Sakura" while the app is in the background and opens voice mode.
 * Runs as a microphone foreground service with a persistent notification, and pauses
 * whenever Sakura itself is on screen so Open WebUI's voice mode can use the microphone.
 */
class WakeWordService : Service(), RecognitionListener {
    private val main = Handler(Looper.getMainLooper())
    private var model: Model? = null
    private var speech: SpeechService? = null
    private var recognizer: Recognizer? = null
    private var paused = false
    private var lastTrigger = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                WakeWordModel.setEnabled(this, false)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_PAUSE -> {
                paused = true
                stopListening()
                return START_NOT_STICKY
            }
            ACTION_RESUME -> {
                paused = false
                startListening()
                return START_NOT_STICKY
            }
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED || !WakeWordModel.isReady(this)
        ) {
            stopSelf()
            return START_NOT_STICKY
        }
        createChannels(this)
        if (Build.VERSION.SDK_INT >= 30) {
            startForeground(NOTIFICATION_ID, listeningNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, listeningNotification())
        }
        running = true
        warmUpWebEngine()
        paused = intent?.getBooleanExtra(EXTRA_PAUSED, false) ?: false
        if (model == null) {
            Thread {
                try {
                    val loaded = Model(WakeWordModel.dir(this).path)
                    main.post {
                        model = loaded
                        startListening()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Could not load the wake word model", e)
                    main.post { stopSelf() }
                }
            }.start()
        } else {
            startListening()
        }
        return START_NOT_STICKY
    }

    /**
     * Loads the web engine (Chromium) into this process once, while nothing is on screen, so the
     * WebView starts faster when «Oye, Sakura» opens the app from scratch.
     */
    private fun warmUpWebEngine() {
        if (webEngineWarm) return
        webEngineWarm = true
        main.post { runCatching { android.webkit.WebSettings.getDefaultUserAgent(this) } }
    }

    private fun startListening() {
        val m = model ?: return
        if (paused || speech != null) return
        try {
            val r = Recognizer(m, SAMPLE_RATE).also { recognizer = it }
            speech = SpeechService(r, SAMPLE_RATE).also { it.startListening(this) }
        } catch (e: Exception) {
            Log.e(TAG, "Could not start listening", e)
        }
    }

    private fun stopListening() {
        speech?.stop()
        speech?.shutdown()
        speech = null
        recognizer?.close()
        recognizer = null
    }

    override fun onPartialResult(hypothesis: String?) = check(hypothesis, "partial")

    override fun onResult(hypothesis: String?) = check(hypothesis, "text")

    override fun onFinalResult(hypothesis: String?) = check(hypothesis, "text")

    override fun onError(exception: Exception?) {
        Log.w(TAG, "Wake word recognizer error", exception)
        stopListening()
        main.postDelayed({ startListening() }, 2_000)
    }

    override fun onTimeout() {
        stopListening()
        startListening()
    }

    private fun check(json: String?, key: String) {
        val text = runCatching { JSONObject(json ?: return).optString(key) }.getOrNull() ?: return
        if (text.isBlank() || !WakePhrase.matches(this, text)) return
        val now = System.currentTimeMillis()
        if (now - lastTrigger < 4_000) return
        lastTrigger = now
        Log.i(TAG, "Wake phrase heard: $text")
        stopListening()
        openVoiceMode()
    }

    /**
     * Android blocks activity starts from the background unless the app may draw over other
     * apps; with that permission the activation animation plays and voice mode opens, without
     * it a high-priority notification offers to open voice mode.
     */
    private fun openVoiceMode() {
        val intent = Intent(this, MainActivity::class.java)
            .setAction(IncomingIntents.ACTION_VOICE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (WakeActivation.canDraw(this)) {
            // Tone, flower and the pink border; voice mode loads underneath from the first frame.
            WakeActivation.run(this) {
                try {
                    val grow = android.app.ActivityOptions.makeCustomAnimation(this, R.anim.voice_grow, 0)
                    startActivity(intent, grow.toBundle())
                } catch (e: Exception) {
                    Log.w(TAG, "Could not open Sakura directly", e)
                    showHeardNotification(intent)
                }
            }
            return
        }
        WakeActivation.playTone(this)
        showHeardNotification(intent)
    }

    private fun showHeardNotification(intent: Intent) {
        val open = PendingIntent.getActivity(
            this, 1, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_HEARD)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.wake_heard_title))
            .setContentText(getString(R.string.wake_heard_text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setContentIntent(open)
            .setFullScreenIntent(open, true)
            .setAutoCancel(true)
            .setTimeoutAfter(15_000)
            .build()
        getSystemService(NotificationManager::class.java).notify(HEARD_NOTIFICATION_ID, notification)
        main.postDelayed({ if (!paused) startListening() }, 5_000)
    }

    private fun listeningNotification(): Notification {
        val stop = PendingIntent.getService(
            this, 0, Intent(this, WakeWordService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val settings = PendingIntent.getActivity(
            this, 2, Intent(this, WakeWordSettingsActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        // One tap to talk without saying the phrase.
        val talk = PendingIntent.getActivity(
            this, 3,
            Intent(this, MainActivity::class.java)
                .setAction(IncomingIntents.ACTION_VOICE)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val phrase = WakePhrase.phrase(this)
        return NotificationCompat.Builder(this, CHANNEL_LISTENING)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.wake_listening_title, phrase))
            .setContentText(getString(R.string.wake_listening_text, phrase))
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(settings)
            .addAction(0, getString(R.string.wake_talk), talk)
            .addAction(0, getString(R.string.wake_turn_off), stop)
            .build()
    }

    override fun onDestroy() {
        running = false
        stopListening()
        model?.close()
        model = null
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "com.sakura.webapp.wake.STOP"
        const val ACTION_PAUSE = "com.sakura.webapp.wake.PAUSE"
        const val ACTION_RESUME = "com.sakura.webapp.wake.RESUME"
        const val EXTRA_PAUSED = "paused"
        private const val CHANNEL_LISTENING = "wake_listening"
        private const val CHANNEL_HEARD = "wake_heard"
        private const val NOTIFICATION_ID = 4101
        private const val HEARD_NOTIFICATION_ID = 4102
        private const val SAMPLE_RATE = 16_000f
        private const val TAG = "SakuraWebApp"

        @Volatile var running = false
            private set

        private var webEngineWarm = false

        fun start(context: Context, paused: Boolean = false) {
            ContextCompat.startForegroundService(
                context, Intent(context, WakeWordService::class.java).putExtra(EXTRA_PAUSED, paused)
            )
        }

        /** Pauses or resumes listening if the service is running; ignored otherwise. */
        fun send(context: Context, action: String) {
            if (!running) return
            try {
                context.startService(Intent(context, WakeWordService::class.java).setAction(action))
            } catch (e: Exception) {
                Log.w(TAG, "Could not reach the wake word service", e)
            }
        }

        fun createChannels(context: Context) {
            if (Build.VERSION.SDK_INT < 26) return
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_LISTENING, context.getString(R.string.wake_channel_listening),
                    NotificationManager.IMPORTANCE_MIN
                ).apply { setShowBadge(false) }
            )
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_HEARD, context.getString(R.string.wake_channel_heard),
                    NotificationManager.IMPORTANCE_HIGH
                ).apply { setSound(null, null) }
            )
        }
    }
}
