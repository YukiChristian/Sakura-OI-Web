package com.sakura.webapp

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.PixelFormat
import android.graphics.RectF
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.util.Log
import android.view.RoundedCorner
import android.view.View
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import android.view.animation.PathInterpolator
import java.util.concurrent.atomic.AtomicBoolean

/**
 * What happens when «Oye, Sakura» is heard, from "App de Android · Parte 1 · Activación" (Hanami): the tone
 * and a light vibration, then only the stroke says Sakura woke up — no logo, nothing over the screen or the
 * camera. Two Rosa Sakura lines with a dark pink border are born at the top centre and run down both edges
 * (0–800 ms), meet at the bottom and the ring stays complete until voice mode is on screen, then it fades
 * while voice mode grows from it. Voice mode starts loading at the first frame, under the stroke. With
 * reduced motion the border only glows (no run), and tone and vibration stay. Drawn over any app (and over
 * Sakura itself) when Sakura may draw over other apps.
 */
object WakeActivation {
    private const val TAG = "SakuraWebApp"
    /** The ring waits at most this long for voice mode before it fades anyway (a cold start takes a few seconds). */
    private const val MAX_HOLD = 6_500L

    @Volatile private var current: BorderTraceView? = null

    fun playTone(context: Context) {
        try {
            val player = MediaPlayer()
            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            context.resources.openRawResourceFd(R.raw.oye_sakura_activacion).use {
                player.setDataSource(it.fileDescriptor, it.startOffset, it.length)
            }
            player.setOnCompletionListener { it.release() }
            player.setOnErrorListener { mp, _, _ -> mp.release(); true }
            player.prepare()
            player.start()
        } catch (e: Exception) {
            Log.w(TAG, "Could not play the activation tone", e)
        }
        vibrate(context)
    }

    /** One light tick with the tone. */
    private fun vibrate(context: Context) {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= 31) {
                context.getSystemService(VibratorManager::class.java)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION") context.getSystemService(Vibrator::class.java)
            } ?: return
            if (!vibrator.hasVibrator()) return
            if (Build.VERSION.SDK_INT >= 29) vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
            else if (Build.VERSION.SDK_INT >= 26) vibrator.vibrate(VibrationEffect.createOneShot(25, 90))
        } catch (e: Exception) {
            Log.w(TAG, "Could not vibrate", e)
        }
    }

    fun canDraw(context: Context) = Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(context)

    /** True while the ring is on screen, waiting for voice mode. */
    val isShowing: Boolean get() = current != null

    /** Called by MainActivity once voice mode listens: the ring may fade now. */
    fun appShown() {
        current?.release()
    }

    /**
     * Shows the border animation and, on its first frame, plays the tone and calls [open], so Sakura loads
     * beneath the stroke from the moment it starts. The overlay lives on its own thread: starting Sakura
     * (the WebView) keeps the main thread busy, and the animation must not freeze. If the overlay cannot
     * draw within 700 ms, Sakura opens anyway.
     */
    fun run(context: Context, open: () -> Unit) {
        val app = context.applicationContext
        val main = Handler(Looper.getMainLooper())
        val opened = AtomicBoolean(false)
        val toned = AtomicBoolean(false)
        fun begin() {
            if (toned.compareAndSet(false, true)) playTone(app)
            if (opened.compareAndSet(false, true)) main.post(open)
        }
        if (!canDraw(app)) {
            begin()
            return
        }
        val wm = app.getSystemService(WindowManager::class.java)
        val thread = HandlerThread("SakuraWakeBorder").apply { start() }
        val border = Handler(thread.looper)
        border.post {
            lateinit var view: BorderTraceView
            view = BorderTraceView(app, border, onFirstFrame = { begin() }) {
                if (current === view) current = null
                runCatching { wm.removeView(view) }
                thread.quitSafely()
            }
            current = view
            try {
                wm.addView(view, overlayParams())
            } catch (e: Exception) {
                Log.w(TAG, "Could not show the activation border", e)
                current = null
                thread.quitSafely()
                begin()
            }
        }
        main.postDelayed({ begin() }, 700)
    }

    private fun overlayParams() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
        PixelFormat.TRANSLUCENT
    ).apply {
        if (Build.VERSION.SDK_INT >= 28) {
            layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= 30)
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            else WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        // Android 12+ only lets touches through another app's overlay at 80 % opacity or less.
        if (Build.VERSION.SDK_INT >= 31) alpha = 0.8f
    }

    /**
     * The border: drawn 0–800 ms with cubic-bezier(.6,.05,.3,1), a short glow where the strokes meet, held
     * complete until [release] (voice mode on screen, not before 1 s) or [MAX_HOLD], then faded in 400 ms.
     */
    private class BorderTraceView(
        context: Context,
        private val handler: Handler,
        private val onFirstFrame: () -> Unit,
        private val onDone: () -> Unit,
    ) : View(context) {
        private val density = resources.displayMetrics.density
        private val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        // Rosa Sakura with a dark pink border, readable over cherry wallpapers and in dark mode.
        private val pink = if (night) 0xFFF06595.toInt() else 0xFFD6336C.toInt()
        private val edge = if (night) 0xFFA61E4D.toInt() else 0xFF9C1C48.toInt()
        private val motion = ValueAnimator.areAnimatorsEnabled()
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val traceEase = PathInterpolator(0.6f, 0.05f, 0.3f, 1f)
        private var t = 0f // milliseconds since the first frame
        private var fade = 0f // 0 → 1 while the ring fades
        private var right = Path()
        private var left = Path()
        private var length = 0f
        private val segment = Path()
        private var started = false
        private var released = false
        private var fading = false
        private var startedAt = 0L

        /** Voice mode is on screen: fade once the ring has closed (≈ 1 s). */
        fun release() = handler.post {
            released = true
            if (started) maybeFade()
        }

        private fun maybeFade() {
            if (fading || !released) return
            val wait = (1_000 - (android.os.SystemClock.uptimeMillis() - startedAt)).coerceAtLeast(0)
            handler.postDelayed({ startFade() }, wait)
        }

        private fun startFade() {
            if (fading) return
            fading = true
            ValueAnimator.ofFloat(0f, 1f).apply {
                duration = if (motion) 400 else 200
                interpolator = PathInterpolator(0.2f, 0.8f, 0.2f, 1f)
                addUpdateListener { fade = it.animatedValue as Float; invalidate() }
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) { onDone() }
                })
                start()
            }
        }

        /** The clock starts with the first frame on screen, when the window is really visible. */
        private fun start() {
            started = true
            startedAt = android.os.SystemClock.uptimeMillis()
            onFirstFrame()
            if (motion) {
                ValueAnimator.ofFloat(0f, 1_100f).apply {
                    duration = 1_100
                    interpolator = LinearInterpolator()
                    addUpdateListener { t = it.animatedValue as Float; invalidate() }
                    start()
                }
            } else {
                // Reduced motion: the whole border glows in 200 ms, no run.
                ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = 200
                    addUpdateListener { t = 1_100f * (it.animatedValue as Float); invalidate() }
                    start()
                }
            }
            handler.postDelayed({ startFade() }, MAX_HOLD)
            maybeFade()
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            val radius = cornerRadius()
            val inset = 3 * density
            val cx = w / 2f
            right = Path().apply {
                moveTo(cx, inset)
                lineTo(w - radius, inset)
                arcTo(RectF(w - 2 * radius + inset, inset, w - inset, 2 * radius - inset), -90f, 90f)
                lineTo(w - inset, h - radius)
                arcTo(RectF(w - 2 * radius + inset, h - 2 * radius + inset, w - inset, h - inset), 0f, 90f)
                lineTo(cx, h - inset)
            }
            left = Path().apply {
                moveTo(cx, inset)
                lineTo(radius, inset)
                arcTo(RectF(inset, inset, 2 * radius - inset, 2 * radius - inset), -90f, -90f)
                lineTo(inset, h - radius)
                arcTo(RectF(inset, h - 2 * radius + inset, 2 * radius - inset, h - inset), 180f, -90f)
                lineTo(cx, h - inset)
            }
            length = PathMeasure(right, false).length
        }

        /** The screen's own corner radius where Android reports it; 40 dp otherwise. */
        private fun cornerRadius(): Float {
            if (Build.VERSION.SDK_INT >= 31) {
                val r = rootWindowInsets?.getRoundedCorner(RoundedCorner.POSITION_TOP_LEFT)?.radius ?: 0
                if (r > 0) return r.toFloat()
            }
            return 40 * density
        }

        private fun phase(from: Float, to: Float) = ((t - from) / (to - from)).coerceIn(0f, 1f)

        override fun onDraw(canvas: Canvas) {
            if (!started) start()
            val w = width.toFloat()
            val h = height.toFloat()
            val alpha = 1f - fade
            if (alpha <= 0f) return

            val progress = if (motion) traceEase.getInterpolation(phase(0f, 800f)) else 1f
            val reveal = if (motion) 1f else phase(0f, 1_100f)
            // A soft glow once the ring closes (and all along with reduced motion).
            val glow = if (motion) phase(650f, 1_000f) else 1f
            if (progress > 0f) {
                val layers = listOf(
                    Triple(16f, pink, 0.12f * glow),
                    Triple(9f, edge, 1f),
                    Triple(5f, pink, 1f),
                )
                for ((widthDp, color, a) in layers) {
                    if (a <= 0f) continue
                    stroke.strokeWidth = widthDp * density
                    stroke.color = color
                    stroke.alpha = (255 * a * alpha * reveal).toInt()
                    for (path in listOf(right, left)) {
                        segment.reset()
                        PathMeasure(path, false).getSegment(0f, length * progress, segment, true)
                        canvas.drawPath(segment, stroke)
                    }
                }
            }

            // Where the two strokes meet: a quick glow that grows and fades (800–1050 ms).
            if (motion) {
                val meet = phase(800f, 1_050f)
                if (meet > 0f && meet < 1f) {
                    fill.color = pink
                    fill.alpha = (255 * (1f - meet) * 0.5f * alpha).toInt()
                    canvas.drawCircle(w / 2, h - 3 * density, 10 * density * (1f + 1.6f * meet), fill)
                }
            }
        }
    }
}
