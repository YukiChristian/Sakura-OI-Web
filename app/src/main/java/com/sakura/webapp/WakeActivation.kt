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

/**
 * What happens when «Oye, Sakura» is heard: at that very moment the «Marimba de madera» tone with a light
 * vibration, Sakura starting, and the activation on screen — a veil that dims and blurs the screen, a few
 * abstract petals drifting down and the Rosa Sakura line running down both edges — until voice mode is listening,
 * when it all fades and uncovers the call screen. With reduced motion only the veil and the glowing border.
 * Drawn over any app (and over Sakura itself) when Sakura may draw over other apps.
 */
object WakeActivation {
    private const val TAG = "SakuraWebApp"
    /**
     * The activation tone: «Marimba de madera» at 100 % (res/raw/oye_sakura_activacion.wav, 0.6 s), the one the
     * user picked; the design's own 1.6 s tone was too long and too high.
     */
    private const val TONE = true

    /**
     * The activation waits at most this long for voice mode before it fades anyway: starting Sakura from
     * scratch and connecting can take several seconds. A connection error ends it at once.
     */
    private const val MAX_HOLD = 16_000L

    @Volatile private var current: ActivationView? = null

    /** The tone (see [TONE]) and a light vibration. */
    fun playTone(context: Context) {
        if (!TONE) { vibrate(context); return }
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

    /** True while the activation is on screen, waiting for voice mode. */
    val isShowing: Boolean get() = current != null

    /** Called by MainActivity once voice mode listens: the activation may fade now. */
    fun appShown() {
        current?.release()
    }

    /**
     * At the very moment the phrase is heard: the tone, Sakura starting ([open]) and the activation on screen,
     * all at once. The overlay lives on its own thread: starting Sakura (the WebView) keeps the main thread
     * busy, and the animation must not freeze.
     */
    fun run(context: Context, open: () -> Unit) {
        val app = context.applicationContext
        playTone(app)
        Handler(Looper.getMainLooper()).post(open)
        if (!canDraw(app)) return
        val wm = app.getSystemService(WindowManager::class.java)
        val thread = HandlerThread("SakuraWakeActivation").apply { start() }
        val handler = Handler(thread.looper)
        handler.post {
            val params = overlayParams(wm)
            lateinit var view: ActivationView
            view = ActivationView(app, handler, blur = { radius ->
                // Android 12+: what is behind (the app or the home screen) blurs as the veil comes in.
                if (Build.VERSION.SDK_INT >= 31 && view.isAttachedToWindow && params.flags and WindowManager.LayoutParams.FLAG_BLUR_BEHIND != 0) {
                    params.blurBehindRadius = radius
                    runCatching { wm.updateViewLayout(view, params) }
                }
            }) {
                if (current === view) current = null
                runCatching { wm.removeView(view) }
                thread.quitSafely()
            }
            current = view
            try {
                wm.addView(view, params)
            } catch (e: Exception) {
                Log.w(TAG, "Could not show the activation", e)
                current = null
                thread.quitSafely()
            }
        }
    }

    private fun overlayParams(wm: WindowManager) = WindowManager.LayoutParams(
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
        if (Build.VERSION.SDK_INT >= 31) {
            // Android 12+ only lets touches through another app's overlay at 80 % opacity or less.
            alpha = 0.8f
            if (wm.isCrossWindowBlurEnabled) {
                flags = flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
                blurBehindRadius = 0
            }
        }
    }

    /**
     * The activation: a veil that dims and blurs the screen (0–500 ms), a few loose petals that drift down across
     * it, and the Rosa Sakura line with its dark pink border running down both edges (0–800 ms). It all stays
     * until [release] (voice mode listening; at most [MAX_HOLD]) and then fades in 450 ms, uncovering the call
     * screen. With reduced motion: the veil and the glowing border only, no petals and no run.
     */
    private class ActivationView(
        context: Context,
        private val handler: Handler,
        private val blur: (Int) -> Unit,
        private val onDone: () -> Unit,
    ) : View(context) {
        private val density = resources.displayMetrics.density
        private val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        private val pink = if (night) 0xFFF06595.toInt() else 0xFFD6336C.toInt()
        private val edge = if (night) 0xFFA61E4D.toInt() else 0xFF9C1C48.toInt()
        // The veil: a pink-white mist in light mode, graphite in dark mode.
        private val veil = if (night) 0xFF0E0E12.toInt() else 0xFFFFF4F7.toInt()
        private val veilAlpha = if (night) 0.62f else 0.66f
        private val motion = ValueAnimator.areAnimatorsEnabled()
        private val maxBlur = (36 * density).toInt()
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val traceEase = PathInterpolator(0.6f, 0.05f, 0.3f, 1f)
        private val veilEase = PathInterpolator(0.2f, 0.8f, 0.2f, 1f)
        private var t = 0f // milliseconds since the first frame
        private var fade = 0f // 0 → 1 while everything fades
        private var right = Path()
        private var left = Path()
        private var length = 0f
        private val segment = Path()
        private var started = false
        private var released = false
        private var fading = false
        private var startedAt = 0L
        private var lastBlur = -1
        private val flower = petalPath()
        private val blossoms = mutableListOf<Blossom>()
        private val random = java.util.Random()
        /** A few loose petals drifting across, not a screen full of flowers. */
        private val PETALS = 9

        /** One floating blossom: where it is, how it drifts, sways and turns, and its size and colour. */
        private class Blossom(
            var x: Float, var y: Float, val fall: Float, val swayAmp: Float, val swayHz: Float, val phase: Float,
            var angle: Float, val spin: Float, val size: Float, val color: Int, val alpha: Float,
        )

        init {
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }

        /** Voice mode is listening: fade once the line has closed (≈ 1 s). */
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
                duration = if (motion) 450 else 200
                interpolator = veilEase
                addUpdateListener { fade = it.animatedValue as Float; invalidate() }
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) { onDone() }
                })
                start()
            }
        }

        /** The clock starts with the first frame on screen. */
        private fun start() {
            started = true
            startedAt = android.os.SystemClock.uptimeMillis()
            if (motion) {
                // Runs until the fade ends: the blossoms keep floating while Sakura loads.
                ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = 1_000
                    repeatCount = ValueAnimator.INFINITE
                    interpolator = LinearInterpolator()
                    addUpdateListener { t = (android.os.SystemClock.uptimeMillis() - startedAt).toFloat(); invalidate() }
                    start()
                }
            } else {
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
            // The petals are already spread over the screen on the first frame.
            blossoms.clear()
            if (motion) repeat(PETALS) { blossoms += newBlossom(w, h, random.nextFloat() * h) }
        }

        private val petalColors = intArrayOf(0xFFF3A6C0.toInt(), 0xFFE8789E.toInt(), 0xFFF7B6CC.toInt(), 0xFFEC8FB0.toInt())

        private fun newBlossom(w: Int, h: Int, y: Float) = Blossom(
            x = random.nextFloat() * w,
            y = y,
            fall = (38 + random.nextFloat() * 52) * density, // px per second
            swayAmp = (8 + random.nextFloat() * 22) * density,
            swayHz = 0.25f + random.nextFloat() * 0.45f,
            phase = random.nextFloat() * 6.283f,
            angle = random.nextFloat() * 360f,
            spin = (random.nextFloat() - 0.5f) * 90f, // degrees per second
            size = (7 + random.nextFloat() * 7) * density,
            color = petalColors[random.nextInt(petalColors.size)],
            alpha = 0.45f + random.nextFloat() * 0.4f,
        )

        /** One abstract petal about 2 units tall, centred on its middle, with a soft notch at the tip. */
        private fun petalPath() = Path().apply {
            moveTo(0f, 1f)
            cubicTo(-0.7f, 0.55f, -0.62f, -0.6f, -0.2f, -0.95f)
            quadTo(-0.08f, -1.02f, 0f, -0.86f)
            quadTo(0.08f, -1.02f, 0.2f, -0.95f)
            cubicTo(0.62f, -0.6f, 0.7f, 0.55f, 0f, 1f)
            close()
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

        private var lastFrame = 0f

        override fun onDraw(canvas: Canvas) {
            if (!started) start()
            val w = width.toFloat()
            val h = height.toFloat()
            val alpha = 1f - fade
            if (alpha <= 0f) return

            // The veil and the blur behind it come in over 500 ms and leave with the fade.
            val veilIn = if (motion) veilEase.getInterpolation(phase(0f, 500f)) else phase(0f, 1_100f)
            val strength = veilIn * alpha
            fill.color = veil
            fill.alpha = (255 * veilAlpha * strength).toInt()
            canvas.drawRect(0f, 0f, w, h, fill)
            val radius = (maxBlur * strength).toInt()
            if (radius != lastBlur) { lastBlur = radius; blur(radius) }

            // A few loose petals drifting down, tumbling as they go; a new one starts above the top as one leaves.
            if (motion && blossoms.isNotEmpty()) {
                val dt = ((t - lastFrame) / 1000f).coerceIn(0f, 0.1f)
                lastFrame = t
                val bloom = phase(0f, 300f) * alpha
                for (i in blossoms.indices) {
                    val b = blossoms[i]
                    b.y += b.fall * dt
                    b.angle += b.spin * dt
                    if (b.y - b.size > h) blossoms[i] = newBlossom(width, height, -b.size * 2)
                    val x = b.x + b.swayAmp * kotlin.math.sin(b.phase + t / 1000f * b.swayHz * 6.283f)
                    canvas.save()
                    canvas.translate(x, b.y)
                    canvas.rotate(b.angle)
                    canvas.scale(b.size * (0.35f + 0.65f * kotlin.math.abs(kotlin.math.cos(b.phase + t / 1000f * 1.3f))), b.size)
                    fill.color = b.color
                    fill.alpha = (255 * b.alpha * bloom).toInt()
                    canvas.drawPath(flower, fill)
                    canvas.restore()
                }
            }

            // The Rosa Sakura line with its dark pink border, born at the top centre (0–800 ms).
            val progress = if (motion) traceEase.getInterpolation(phase(0f, 800f)) else 1f
            val glow = if (motion) phase(650f, 1_000f) else 1f
            val layers = listOf(Triple(16f, pink, 0.14f * glow), Triple(9f, edge, 1f), Triple(5f, pink, 1f))
            for ((widthDp, color, a) in layers) {
                if (a <= 0f || progress <= 0f) continue
                stroke.strokeWidth = widthDp * density
                stroke.color = color
                stroke.alpha = (255 * a * alpha * (if (motion) 1f else veilIn)).toInt()
                for (path in listOf(right, left)) {
                    segment.reset()
                    PathMeasure(path, false).getSegment(0f, length * progress, segment, true)
                    canvas.drawPath(segment, stroke)
                }
            }
        }
    }
}
