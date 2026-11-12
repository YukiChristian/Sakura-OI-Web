package com.sakura.webapp

import android.animation.ValueAnimator
import android.content.Context
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
import android.provider.Settings
import android.util.Log
import android.view.RoundedCorner
import android.view.View
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import android.view.animation.PathInterpolator
import java.util.concurrent.atomic.AtomicBoolean

/**
 * What happens when «Oye, Sakura» is heard, from "Oye, Sakura · proceso de activación", slowed down
 * by [WakeActivation.SLOWER] and, for now, without the activation tone: the flower blooms at the top
 * centre, a Sakura-pink line runs down both edges of the screen dropping petals, the two strokes meet
 * at the bottom (≈ 1.6 s) and the line fades (1.6–2.4 s). Voice mode starts loading at the first
 * frame, under the animation, so the app is ready sooner. Drawn over any app (and over Sakura itself)
 * when Sakura may draw over other apps.
 */
object WakeActivation {
    private const val TAG = "SakuraWebApp"
    /** The design's timeline (1.35 s) runs this many times slower, to cover the app while it loads. */
    const val SLOWER = 1.8f
    /** How long the overlay stays: the slowed timeline plus a short margin. */
    private val TOTAL = (1_350 * SLOWER).toLong() + 100

    /** The activation tone is off for now; [playTone] is kept for a future sound. */
    private const val TONE = false

    fun playTone(context: Context) {
        if (!TONE) return
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
    }

    fun canDraw(context: Context) = Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(context)

    /**
     * Shows the border animation and, on its first frame, calls [open] (and would play the tone), so Sakura
     * loads beneath the animation from the moment it starts. The overlay lives on its own thread:
     * starting Sakura (the WebView) keeps the main thread busy, and the animation must not freeze.
     * If the overlay cannot draw within 700 ms, Sakura opens anyway.
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
            view = BorderTraceView(app) {
                begin()
                border.postDelayed({
                    runCatching { wm.removeView(view) }
                    thread.quitSafely()
                }, TOTAL)
            }
            try {
                wm.addView(view, overlayParams())
            } catch (e: Exception) {
                Log.w(TAG, "Could not show the activation border", e)
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
}

/** The border trace, the blooming flower, the meeting flash and the petals, on a 1.35 s clock. */
private class BorderTraceView(context: Context, private val onFirstFrame: () -> Unit) : View(context) {
    private val density = resources.displayMetrics.density
    private val pink = 0xFFD6336C.toInt()
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = pink
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val logo = context.getDrawable(R.drawable.sakura_logo)
    private val traceEase = PathInterpolator(0.6f, 0.05f, 0.3f, 1f)
    private var t = 0f // milliseconds
    private var right = Path()
    private var left = Path()
    private var length = 0f
    private val segment = Path()

    // Petal start (fraction of width/height), drift (dp) and colour, as in the design.
    private val petals = listOf(
        Petal(0.154f, 0.142f, -30f, 60f, 0xFFF3A6C0.toInt(), 0),
        Petal(0.872f, 0.308f, -40f, 50f, 0xFFD6336C.toInt(), 1),
        Petal(0.954f, 0.664f, -60f, 30f, 0xFFF7C6D6.toInt(), 2),
        Petal(0.077f, 0.498f, 50f, 40f, 0xFFE8789E.toInt(), 3),
        Petal(0.538f, 0.972f, 10f, -70f, 0xFFD6336C.toInt(), 4),
        Petal(0.046f, 0.829f, 40f, -30f, 0xFFF3A6C0.toInt(), 5),
        Petal(0.974f, 0.107f, -50f, 40f, 0xFFE8789E.toInt(), 6),
    )

    private data class Petal(val x: Float, val y: Float, val dx: Float, val dy: Float, val color: Int, val index: Int)

    private var started = false

    /** The clock starts with the first frame on screen, when the window is really visible. */
    private fun start() {
        started = true
        onFirstFrame()
        // The drawing below uses the design's milliseconds (0–1350); the clock runs SLOWER times slower.
        ValueAnimator.ofFloat(0f, 1350f).apply {
            duration = (1350 * WakeActivation.SLOWER).toLong()
            interpolator = LinearInterpolator()
            addUpdateListener { t = it.animatedValue as Float; invalidate() }
            start()
        }
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

        // The line: drawn 100–900 ms, held, then faded 1000–1300 ms. Two softer, wider strokes make the glow.
        val progress = traceEase.getInterpolation(phase(100f, 900f))
        val lineAlpha = 1f - phase(1000f, 1300f)
        val glow = (phase(650f, 900f) * (1f - phase(1050f, 1300f))).coerceIn(0f, 1f)
        if (progress > 0f && lineAlpha > 0f) {
            for ((widthDp, alpha) in listOf(18f to 0.10f * glow, 12f to 0.20f * glow, 6f to 1f)) {
                if (alpha <= 0f) continue
                stroke.strokeWidth = widthDp * density
                stroke.alpha = (255 * alpha * lineAlpha).toInt()
                for (path in listOf(right, left)) {
                    segment.reset()
                    PathMeasure(path, false).getSegment(0f, length * progress, segment, true)
                    canvas.drawPath(segment, stroke)
                }
            }
        }

        // Where the two strokes meet: a quick flash that grows and fades (850–1150 ms).
        val meet = phase(850f, 1150f)
        if (meet > 0f && meet < 1f) {
            val grow = if (meet < 0.33f) 0.2f + 0.8f * (meet / 0.33f) else 1f + 1.4f * ((meet - 0.33f) / 0.67f)
            fill.color = pink
            fill.alpha = (255 * (if (meet < 0.33f) meet / 0.33f else 1f - (meet - 0.33f) / 0.67f) * 0.6f).toInt()
            canvas.drawCircle(w / 2, h - 3 * density, 12 * density * grow, fill)
        }

        // Petals drift off the line from 300 ms, 40 ms apart.
        for (p in petals) {
            val start = 300f + p.index * 40f
            val k = ((t - start) / 800f).coerceIn(0f, 1f)
            if (k <= 0f || k >= 1f) continue
            val alpha = if (k < 0.15f) k / 0.15f * 0.9f else 0.9f * (1f - (k - 0.15f) / 0.85f)
            fill.color = p.color
            fill.alpha = (255 * alpha).toInt()
            canvas.save()
            canvas.translate(p.x * w + p.dx * density * k, p.y * h + p.dy * density * k)
            canvas.rotate(220f * k)
            canvas.drawRoundRect(RectF(-4.5f * density, -6.5f * density, 4.5f * density, 6.5f * density), 4.5f * density, 4.5f * density, fill)
            canvas.restore()
        }

        // The flower blooms at the top centre (0–200 ms), settles, and fades as voice mode opens.
        val d = logo ?: return
        val bloom = phase(0f, 200f)
        val settle = phase(200f, 400f)
        val out = phase(800f, 1000f)
        if (out >= 1f) return
        val scale = when {
            bloom < 1f -> 0.3f + 0.88f * bloom
            settle < 1f -> 1.18f - 0.18f * settle
            else -> 1f - 0.3f * out
        }
        val rotation = -40f * (1f - bloom)
        val size = 40 * density
        val cx = w / 2
        val cy = 14 * density + size / 2
        canvas.save()
        canvas.translate(cx, cy)
        canvas.rotate(rotation)
        canvas.scale(scale, scale)
        d.setBounds((-size / 2).toInt(), (-size / 2 * 0.95f).toInt(), (size / 2).toInt(), (size / 2 * 0.95f).toInt())
        d.alpha = (255 * bloom * (1f - out)).toInt()
        d.draw(canvas)
        canvas.restore()
    }
}
