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
 * vibration, Sakura starting, and the activation on screen — frosted glass with a soft colour mesh, the Sakura
 * logo fading in at the centre, out-of-focus lights drifting down behind it and the Rosa Sakura line running
 * down both edges as a living neon — until voice mode is listening, when it all fades and uncovers the call
 * screen. With reduced motion: the glass, the logo and a still border. Drawn over any app (and over Sakura
 * itself) when Sakura may draw over other apps.
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
     * The activation, as chosen on the preview page: the screen turns into frosted glass with a soft colour mesh
     * («Aurora Sakura» in light mode, «Aurora nocturna» in dark), the Sakura logo fades in at the centre, out-of-
     * focus lights (bokeh) drift down behind it, and the Rosa Sakura line runs down both edges as a living neon.
     * It stays until [release] (voice mode listening; at most [MAX_HOLD]); then the logo fades out (800 ms) and,
     * from 300 ms on, the glass, the lights and the line clear, uncovering the call screen. With reduced motion:
     * the glass, the logo and a still border, no lights and no flow.
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
        private val pinkLight = if (night) 0xFFFFD1E1.toInt() else 0xFFFFB3CD.toInt()
        private val edge = if (night) 0xFFA61E4D.toInt() else 0xFF9C1C48.toInt()
        private val motion = ValueAnimator.areAnimatorsEnabled()
        private val maxBlur = (36 * density).toInt()
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val logoPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val logo = android.graphics.BitmapFactory.decodeResource(resources, R.drawable.sakura_logo)
        private val traceEase = PathInterpolator(0.6f, 0.05f, 0.3f, 1f)
        private val soft = PathInterpolator(0.2f, 0.8f, 0.2f, 1f)
        private var t = 0f // milliseconds since the first frame
        private var fadeT = -1f // milliseconds since the fade started (-1: not fading)
        private var right = Path()
        private var left = Path()
        private var rightMeasure = PathMeasure()
        private var leftMeasure = PathMeasure()
        private var length = 0f
        private val segment = Path()
        private var started = false
        private var released = false
        private var fading = false
        private var startedAt = 0L
        private var lastBlur = -1
        private var lastFrame = 0f
        private val random = java.util.Random()
        private val lights = mutableListOf<Light>()
        private var k = 1f // the preview's 320-px phone → this screen
        private val colors = if (night)
            intArrayOf(0xFFFBD6E2.toInt(), 0xFFF7C0D2.toInt(), 0xFFF3A6C0.toInt(), 0xFFFDE4EC.toInt(), 0xFFF5B3C8.toInt())
        else intArrayOf(0xFFF3A6C0.toInt(), 0xFFEC8FB0.toInt(), 0xFFF5B3C8.toInt(), 0xFFE8789E.toInt())

        /** One out-of-focus light: where it is, how it drifts and sways, its depth, size and soft round shader. */
        private class Light(
            var x: Float, var y: Float, val depth: Float, val radius: Float, val fall: Float, val drift: Float,
            val sway: Float, val swayHz: Float, val phase: Float, val alpha: Float, val shader: android.graphics.Shader,
        )

        init {
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }

        /** Voice mode is listening: fade once the line has closed and the logo is in (≈ 1 s). */
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
            ValueAnimator.ofFloat(0f, 800f).apply {
                duration = 800
                interpolator = LinearInterpolator()
                addUpdateListener { fadeT = it.animatedValue as Float; invalidate() }
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) { onDone() }
                })
                start()
            }
        }

        /** The clock starts with the first frame on screen and runs until the fade ends. */
        private fun start() {
            started = true
            startedAt = android.os.SystemClock.uptimeMillis()
            ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 1_000
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
                addUpdateListener { t = (android.os.SystemClock.uptimeMillis() - startedAt).toFloat(); invalidate() }
                start()
            }
            handler.postDelayed({ startFade() }, MAX_HOLD)
            maybeFade()
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            k = w / (320f * density) * density // CSS px of the 320-px preview phone, in this screen's px
            val radius = cornerRadius()
            val inset = 2 * density
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
            rightMeasure = PathMeasure(right, false)
            leftMeasure = PathMeasure(left, false)
            length = rightMeasure.length
            seedLights(w, h)
        }

        // ── Bokeh: spread on purpose (one per cell of a 6 × 5 grid, the logo's middle left clear), never alike ──

        private fun seedLights(w: Int, h: Int) {
            lights.clear()
            if (!motion) return
            for (r in 0 until ROWS) for (c in 0 until COLS) {
                val x = (c + random.nextFloat()) / COLS * w
                val y = (r + random.nextFloat()) / ROWS * h
                val dx = (x - w / 2f) / w
                val dy = (y - h * 0.46f) / h
                if (kotlin.math.hypot(dx, dy * 0.7f) < 0.17f) continue
                lights += newLight(w, h, x, y)
            }
        }

        private fun newLight(w: Int, h: Int, x: Float, y: Float): Light {
            val depth = random.nextFloat() // 0 far … 1 near
            val size = (5 + depth * 8) * k
            val radius = size * (0.9f + (1 - depth) * 1.1f)
            val color = colors[random.nextInt(colors.size)]
            val clear = color and 0x00FFFFFF
            return Light(
                x = x, y = y, depth = depth, radius = radius,
                fall = (16 + depth * 26) / 700f * h,
                drift = 0.6f + random.nextFloat() * 0.6f,
                sway = (4 + random.nextFloat() * 8) * k,
                swayHz = 0.25f + random.nextFloat() * 0.35f,
                phase = random.nextFloat() * 6.283f,
                alpha = (0.35f + depth * 0.55f) * (0.55f + 0.45f * depth),
                shader = android.graphics.RadialGradient(0f, 0f, radius, intArrayOf(color, color, clear), floatArrayOf(0f, 0.62f, 1f),
                    android.graphics.Shader.TileMode.CLAMP),
            )
        }

        /** As one leaves, the next starts above the column that has the fewest, a little to the left (the wind). */
        private fun respawn(w: Int, h: Int, old: Light): Light {
            val counts = IntArray(COLS)
            for (l in lights) if (l !== old) counts[(l.x / w * COLS).toInt().coerceIn(0, COLS - 1)]++
            val min = counts.min()
            val choices = counts.indices.filter { counts[it] == min }
            val c = choices[random.nextInt(choices.size)]
            return newLight(w, h, (c + random.nextFloat()) / COLS * w - 0.12f * w, -(12 + random.nextFloat() * 24) * k)
        }

        /** The screen's own corner radius where Android reports it; 40 dp otherwise. */
        private fun cornerRadius(): Float {
            if (Build.VERSION.SDK_INT >= 31) {
                val r = rootWindowInsets?.getRoundedCorner(RoundedCorner.POSITION_TOP_LEFT)?.radius ?: 0
                if (r > 0) return r.toFloat()
            }
            return 40 * density
        }

        private fun phase(value: Float, from: Float, to: Float) = ((value - from) / (to - from)).coerceIn(0f, 1f)
        private fun sine(x: Float) = 0.5f - 0.5f * kotlin.math.cos(Math.PI.toFloat() * x)

        private fun mix(a: Int, b: Int, f: Float): Int {
            fun ch(shift: Int) = (((a shr shift) and 0xFF) * (1 - f) + ((b shr shift) and 0xFF) * f).toInt() shl shift
            return (0xFF shl 24) or ch(16) or ch(8) or ch(0)
        }

        // ── The glass: a colour mesh over the blur ──

        /** One soft blob of the mesh: an ellipse at ([fx], [fy]) of the screen, radii [rx] × [ry], gone at 70 %. */
        private fun blob(canvas: Canvas, w: Float, h: Float, fx: Float, fy: Float, rx: Float, ry: Float, color: Int, a: Float, strength: Float) {
            val argb = (((a * strength * 255).toInt().coerceIn(0, 255)) shl 24) or (color and 0xFFFFFF)
            fill.shader = android.graphics.RadialGradient(0f, 0f, 1f, intArrayOf(argb, argb and 0x00FFFFFF), floatArrayOf(0f, 0.7f),
                android.graphics.Shader.TileMode.CLAMP)
            fill.alpha = 255
            canvas.save()
            canvas.translate(fx * w, fy * h)
            canvas.scale(rx * w, ry * h)
            canvas.drawCircle(0f, 0f, 1f, fill)
            canvas.restore()
            fill.shader = null
        }

        private fun drawGlass(canvas: Canvas, w: Float, h: Float, strength: Float) {
            if (strength <= 0f) return
            if (night) {
                fill.color = 0xFF0A0A0E.toInt(); fill.alpha = (255 * 0.48f * strength).toInt(); canvas.drawRect(0f, 0f, w, h, fill)
                blob(canvas, w, h, 0.15f, 0.78f, 0.60f, 0.45f, 0x1E2E54, 0.50f, strength)
                blob(canvas, w, h, 0.65f, 0.88f, 0.70f, 0.50f, 0x782448, 0.45f, strength)
                blob(canvas, w, h, 0.85f, 0.35f, 0.55f, 0.42f, 0x283E6E, 0.55f, strength)
                blob(canvas, w, h, 0.20f, 0.20f, 0.60f, 0.45f, 0x96285A, 0.55f, strength)
            } else {
                fill.color = 0xFFFFFFFF.toInt(); fill.alpha = (255 * 0.42f * strength).toInt(); canvas.drawRect(0f, 0f, w, h, fill)
                blob(canvas, w, h, 0.20f, 0.80f, 0.60f, 0.45f, 0xFFD6E8, 0.55f, strength)
                blob(canvas, w, h, 0.70f, 0.85f, 0.70f, 0.50f, 0xD6E2FF, 0.55f, strength)
                blob(canvas, w, h, 0.85f, 0.30f, 0.55f, 0.42f, 0xFFDEC4, 0.55f, strength)
                blob(canvas, w, h, 0.18f, 0.22f, 0.60f, 0.45f, 0xFFC4D6, 0.62f, strength)
            }
        }

        // ── The living neon line ──

        private fun strokeRange(canvas: Canvas, measure: PathMeasure, from: Float, to: Float) {
            if (to <= from) return
            segment.reset()
            measure.getSegment(from, to, segment, true)
            canvas.drawPath(segment, stroke)
        }

        private fun drawNeon(canvas: Canvas, timeS: Float, alpha: Float) {
            val progress = if (motion) traceEase.getInterpolation(phase(t, 0f, 800f)) else 1f
            if (progress <= 0f || alpha <= 0f) return
            val reach = length * progress
            val glowIn = if (motion) phase(t, 650f, 1_000f) else 1f
            val breathe = if (motion) 0.55f + 0.45f * kotlin.math.sin(timeS * 2.4f) else 0.7f
            val flow = if (motion) timeS * 3.2f else 0f
            for (measure in arrayOf(rightMeasure, leftMeasure)) {
                // The dark pink border under it…
                stroke.color = edge; stroke.alpha = (255 * alpha).toInt(); stroke.strokeWidth = 4.5f * k
                strokeRange(canvas, measure, 0f, reach)
                // …the glow that breathes…
                stroke.color = pink; stroke.alpha = (255 * (0.12f + 0.16f * breathe) * glowIn * alpha).toInt(); stroke.strokeWidth = 14f * k
                strokeRange(canvas, measure, 0f, reach)
                // …and the core, whose colour flows along the line between Rosa Sakura and light pink.
                stroke.strokeWidth = 2.6f * k
                val n = 20
                for (i in 0 until n) {
                    val d0 = reach * i / n
                    val d1 = reach * (i + 1) / n
                    val f = 0.5f + 0.5f * kotlin.math.sin((d0 / length) * Math.PI.toFloat() * 4 - flow)
                    stroke.color = mix(pink, pinkLight, f)
                    stroke.alpha = (255 * alpha).toInt()
                    strokeRange(canvas, measure, d0, d1)
                }
            }
        }

        override fun onDraw(canvas: Canvas) {
            if (!started) start()
            val w = width.toFloat()
            val h = height.toFloat()
            val timeS = t / 1000f
            // The fade: the logo leaves over 800 ms; the glass, lights and line clear from 300 to 800 ms.
            val clear = if (fadeT < 0f) 0f else soft.getInterpolation(phase(fadeT, 300f, 800f))
            val alpha = 1f - clear
            val logoOut = if (fadeT < 0f) 0f else sine(phase(fadeT, 0f, 800f))

            val strength = soft.getInterpolation(phase(t, 0f, 700f)) * alpha
            val radius = (maxBlur * strength).toInt()
            if (radius != lastBlur) { lastBlur = radius; blur(radius) }
            drawGlass(canvas, w, h, strength)

            // Out-of-focus lights drifting down with the wind, behind the logo.
            if (motion && lights.isNotEmpty()) {
                val dt = ((t - lastFrame) / 1000f).coerceIn(0f, 0.1f)
                lastFrame = t
                val wind = 18 * k * (0.6f + 0.4f * kotlin.math.sin(timeS * 0.7f))
                val bloom = phase(t, 0f, 300f) * alpha
                for (i in lights.indices) {
                    var l = lights[i]
                    l.y += l.fall * dt
                    l.x += wind * l.drift * (0.4f + l.depth) * dt
                    if (l.y - l.radius > h || l.x - l.radius > w) { l = respawn(width, height, l); lights[i] = l }
                    val x = l.x + l.sway * kotlin.math.sin(l.phase + timeS * l.swayHz * 6.283f)
                    canvas.save()
                    canvas.translate(x, l.y)
                    fill.shader = l.shader
                    fill.alpha = (255 * l.alpha * bloom).toInt()
                    canvas.drawCircle(0f, 0f, l.radius, fill)
                    canvas.restore()
                }
                fill.shader = null
            }

            // The logo, always in front and always the same size: it only fades in (300–1300 ms) and out.
            val logoAlpha = sine(phase(t, 300f, 1_300f)) * (1f - logoOut)
            if (logoAlpha > 0f && logo != null) {
                val size = w * 0.36f
                val cx = w / 2f
                val cy = h * 0.46f
                val haloColor = if (night) 0x47F06595 else 0xD9FFFFFF.toInt()
                fill.shader = android.graphics.RadialGradient(cx, cy, size * 0.95f, intArrayOf(haloColor, haloColor and 0x00FFFFFF), null,
                    android.graphics.Shader.TileMode.CLAMP)
                fill.alpha = (255 * logoAlpha).toInt()
                canvas.drawCircle(cx, cy, size * 0.95f, fill)
                fill.shader = null
                logoPaint.alpha = (255 * logoAlpha).toInt()
                canvas.drawBitmap(logo, null, RectF(cx - size / 2, cy - size / 2, cx + size / 2, cy + size / 2), logoPaint)
            }

            // The living neon line, on top of everything.
            drawNeon(canvas, timeS, alpha)
        }

        companion object {
            private const val COLS = 6
            private const val ROWS = 5
        }
    }
}
