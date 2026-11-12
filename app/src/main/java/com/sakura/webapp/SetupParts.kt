package com.sakura.webapp

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.PI
import kotlin.math.max

/**
 * Pieces of the "App Android · widgets y «Oye, Sakura»" screens, on top of [SetupUi]: a navigation bar,
 * section labels, chips, the permission banner, rows with a small action, the segmented control,
 * the black switch, progress bars and the animated voice level bars.
 */
class SetupParts(private val ui: SetupUi) {
    private val context: Context get() = ui.context
    private fun pick(light: Long, dark: Long) = (if (ui.night) dark else light).toInt()

    val pink = 0xFFD6336C.toInt()
    val pinkTile = pick(0xFFFDE8F1, 0xFF3A1F2A)
    val blue = pick(0xFF2361C7, 0xFF6F9CEB)
    val blueSoft = pick(0x142361C7, 0x266F9CEB)
    val blueText = pick(0xFF1D4FA3, 0xFF9DBDF2)
    val green = pick(0xFF2B8A5A, 0xFF5CC28A)
    val greenTile = pick(0xFFE5F3E7, 0xFF173A26)
    val greenText = pick(0xFF1F6B45, 0xFF8BD6A8)
    val redTile = pick(0xFFFBE6E3, 0xFF3F1C19)
    val redText = pick(0xFFA32A22, 0xFFF2A49B)
    val panel = pick(0xFFFAFAFA, 0xFF18181B)
    val panelRing = pick(0xFFECECEE, 0xFF2E2E33)
    val divider = pick(0xFFF0F0F2, 0xFF2A2A2F)
    val chipInk = pick(0xFF3F3F46, 0xFFD4D4D8)
    val pending = pick(0xFFE4E4E7, 0xFF3F3F46)

    private fun rounded(radiusDp: Float, color: Int, strokeColor: Int? = null, strokeDp: Float = 1f) =
        GradientDrawable().apply {
            cornerRadius = ui.dp(radiusDp).toFloat()
            setColor(color)
            if (strokeColor != null) setStroke(ui.dp(strokeDp), strokeColor)
        }

    fun icon(res: Int, color: Int, sizeDp: Int) = ImageView(context).apply {
        setImageResource(res)
        imageTintList = ColorStateList.valueOf(color)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        layoutParams = LinearLayout.LayoutParams(ui.dp(sizeDp), ui.dp(sizeDp))
    }

    /** "‹ Ajustes" on the left and the screen title centred, 52 dp tall. */
    fun navBar(back: String, title: String, onBack: () -> Unit) = FrameLayout(context).apply {
        addView(ui.text(title, 18f, ui.ink, bold = true).apply { gravity = Gravity.CENTER },
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        addView(LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ui.dp(4), 0, ui.dp(10), 0)
            isClickable = true
            isFocusable = true
            contentDescription = back
            background = rounded(10f, Color.TRANSPARENT)
            addView(icon(R.drawable.ic_ui_chevron_left, ui.ink, 22))
            addView(ui.text(back, 16f, ui.ink, semibold = true).apply {
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            })
            setOnClickListener { onBack() }
        }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ui.dp(44), Gravity.CENTER_VERTICAL or Gravity.START))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(52))
    }

    /** White card of the design (radius 22); [padTop]/[padBottom] for list cards. */
    fun card(padTop: Int = 16, padBottom: Int = 16) = ui.card().apply {
        setPadding(ui.dp(16), ui.dp(padTop), ui.dp(16), ui.dp(padBottom))
    }

    /** "FRASE Y SENSIBILIDAD": 12.5 sp bold, upper case, tracked. */
    fun sectionLabel(text: String) = ui.text(text.uppercase(), 12.5f, ui.faint, bold = true).apply {
        letterSpacing = 0.06f
        setPadding(0, 0, 0, ui.dp(8))
    }

    /** 40 dp rounded tile with an icon; [tint] and [background] default to the neutral tile. */
    fun tile(res: Int, tint: Int = ui.ink, background: Int = ui.tile) = FrameLayout(context).apply {
        this.background = rounded(12f, background)
        addView(icon(res, tint, 20).apply { layoutParams = FrameLayout.LayoutParams(ui.dp(20), ui.dp(20), Gravity.CENTER) })
        layoutParams = LinearLayout.LayoutParams(ui.dp(40), ui.dp(40))
    }

    /** Small grey pill with an icon: "Sin internet", "Usa algo de batería"… */
    fun chip(res: Int, text: String) = LinearLayout(context).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(ui.dp(10), 0, ui.dp(10), 0)
        background = rounded(13f, ui.tile)
        addView(icon(res, ui.muted, 13))
        addView(ui.text(text, 12.5f, chipInk, semibold = true).apply { maxLines = 1 },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = ui.dp(5) })
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ui.dp(26))
    }

    /** Pill-shaped light button inside rows: "Cambiar", "Agregar", "Entrenar y probar". */
    fun ghostButton(label: String, onClick: () -> Unit) = TextView(context).apply {
        text = label
        setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13.5f)
        setTextColor(ui.ink)
        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        gravity = Gravity.CENTER
        maxLines = 1
        setPadding(ui.dp(14), 0, ui.dp(14), 0)
        background = rounded(17f, ui.tile, pick(0xFFE4E4E7, 0xFF3F3F46))
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ui.dp(34))
    }

    /** Black pill button ("Permitir"); white in dark mode. */
    fun darkButton(label: String, onClick: () -> Unit) = ghostButton(label, onClick).apply {
        setTextColor(pick(0xFFFFFFFF, 0xFF18181B))
        background = rounded(17f, ui.ink)
    }

    /** A row: icon tile, title and subtitle, and an optional trailing view; a hairline below unless [last]. */
    inner class Row(res: Int, title: String, subtitle: String, trailing: View?, last: Boolean, onClick: (() -> Unit)? = null) {
        val subtitle: TextView = ui.text(subtitle, 13f, ui.faint)
        val title: TextView = ui.text(title, 15.5f, ui.ink, semibold = true)
        val view = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, ui.dp(12), 0, ui.dp(12))
            addView(tile(res))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(this@Row.title)
                addView(this@Row.subtitle)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = ui.dp(12)
                marginEnd = ui.dp(8)
            })
            if (trailing != null) addView(trailing)
            if (onClick != null) {
                isClickable = true
                isFocusable = true
                setOnClickListener { onClick() }
            }
            if (!last) background = HairlineBelow(divider, ui.dp(1))
        }
    }

    /** Yellow notice with an icon and a button: "Para abrir Sakura al instante falta un permiso." */
    fun banner(text: String, button: String, onClick: () -> Unit) = LinearLayout(context).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(ui.dp(12), ui.dp(10), ui.dp(10), ui.dp(10))
        background = rounded(14f, pick(0xFFFFF7E0, 0xFF3A3012), pick(0xFFF2DD9B, 0xFF5C4A1A))
        addView(icon(R.drawable.ic_ui_warning, pick(0xFFA07800, 0xFFE0B84A), 18))
        addView(ui.text(text, 13f, pick(0xFF5C4600, 0xFFF2DD9B), semibold = true),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = ui.dp(10)
                marginEnd = ui.dp(10)
            })
        addView(darkButton(button, onClick))
    }

    /** Segmented control: grey track, the selected option on a white raised segment. */
    inner class Segmented(options: List<String>, private val onSelect: (Int) -> Unit) {
        private val items: List<TextView> = options.mapIndexed { i, label ->
            ui.text(label, 14.5f, ui.muted).apply {
                gravity = Gravity.CENTER
                isClickable = true
                isFocusable = true
                setOnClickListener { select(i); onSelect(i) }
                layoutParams = LinearLayout.LayoutParams(0, ui.dp(38), 1f)
            }
        }
        val view = LinearLayout(context).apply {
            setPadding(ui.dp(4), ui.dp(4), ui.dp(4), ui.dp(4))
            background = rounded(14f, pick(0xFFECECEE, 0xFF2A2A2F))
            items.forEach { addView(it) }
        }

        fun select(index: Int): Unit = items.forEachIndexed { i, item ->
            val on = i == index
            item.setTextColor(if (on) ui.ink else ui.muted)
            item.typeface = android.graphics.Typeface.create("sans-serif", if (on) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
            item.background = if (on) rounded(10f, pick(0xFFFFFFFF, 0xFF3F3F46)) else null
            item.elevation = if (on) ui.dp(1.5f).toFloat() else 0f
            item.isSelected = on
        }
    }

    /** Thin rounded progress bar (8 dp), blue on a pale blue track. */
    inner class Progress {
        private val fill = View(context).apply { background = rounded(4f, blue) }
        val view = FrameLayout(context).apply {
            background = rounded(4f, pick(0x262361C7, 0x336F9CEB))
            clipToOutline = true
            addView(fill, FrameLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(8))
        }

        fun set(percent: Int) = view.post {
            fill.layoutParams = (fill.layoutParams as FrameLayout.LayoutParams).apply {
                width = view.width * percent.coerceIn(0, 100) / 100
            }
        }
    }

    /** Five short bars: done (green), current (blue) and pending (grey). */
    inner class Steps(private val count: Int) {
        private val bars = List(count) { View(context) }
        val view = LinearLayout(context).apply {
            bars.forEachIndexed { i, bar ->
                addView(bar, LinearLayout.LayoutParams(0, ui.dp(6), 1f).apply { if (i > 0) marginStart = ui.dp(6) })
            }
        }

        fun set(done: Int, current: Boolean = true) = bars.forEachIndexed { i, bar ->
            bar.background = rounded(3f, when {
                i < done -> green
                i == done && current -> blue
                else -> pending
            })
        }
    }
}

/** A 1 px line along the bottom edge, for list rows. */
class HairlineBelow(color: Int, private val height: Int) : android.graphics.drawable.Drawable() {
    private val paint = Paint().apply { this.color = color }
    override fun draw(canvas: Canvas) {
        val b = bounds
        canvas.drawRect(b.left.toFloat(), (b.bottom - height).toFloat(), b.right.toFloat(), b.bottom.toFloat(), paint)
    }
    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) { paint.colorFilter = colorFilter }
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
}

/** Lays children out left to right and wraps to a new line, with [gap] between them. */
class FlowLayout(context: Context, private val gap: Int) : ViewGroup(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        var x = 0
        var y = 0
        var line = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            measureChild(c, MeasureSpec.makeMeasureSpec(width, MeasureSpec.AT_MOST), heightMeasureSpec)
            if (x > 0 && x + c.measuredWidth > width) { x = 0; y += line + gap; line = 0 }
            x += c.measuredWidth + gap
            line = max(line, c.measuredHeight)
        }
        setMeasuredDimension(width, y + line)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val width = r - l
        var x = 0
        var y = 0
        var line = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (x > 0 && x + c.measuredWidth > width) { x = 0; y += line + gap; line = 0 }
            c.layout(x, y, x + c.measuredWidth, y + c.measuredHeight)
            x += c.measuredWidth + gap
            line = max(line, c.measuredHeight)
        }
    }

    override fun generateDefaultLayoutParams() = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
}

/** The design's switch: 52 × 30 track, black when on (white in dark mode), white knob. */
class PillSwitch(context: Context, private val on: Int, private val off: Int, private val knob: Int) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var position = 0f
    var isOn = false
        private set
    var onToggle: ((Boolean) -> Unit)? = null

    init {
        isClickable = true
        isFocusable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        setOnClickListener { set(!isOn, animate = true); onToggle?.invoke(isOn) }
    }

    fun set(value: Boolean, animate: Boolean = false) {
        isOn = value
        if (!animate) { position = if (value) 1f else 0f; invalidate(); return }
        ValueAnimator.ofFloat(position, if (value) 1f else 0f).apply {
            duration = 160
            addUpdateListener { position = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onInitializeAccessibilityNodeInfo(info: android.view.accessibility.AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = "android.widget.Switch"
        info.isCheckable = true
        info.isChecked = isOn
    }

    override fun onDraw(canvas: Canvas) {
        val h = height.toFloat()
        val w = width.toFloat()
        paint.color = blend(off, on, position)
        canvas.drawRoundRect(RectF(0f, 0f, w, h), h / 2, h / 2, paint)
        val inset = h * 0.1f
        val r = h / 2 - inset
        val cx = inset + r + (w - 2 * (inset + r)) * position
        paint.color = knob
        paint.setShadowLayer(inset * 0.6f, 0f, inset * 0.3f, 0x33000000)
        canvas.drawCircle(cx, h / 2, r, paint)
        paint.clearShadowLayer()
    }

    private fun blend(a: Int, b: Int, t: Float): Int {
        fun ch(shift: Int) = (((a shr shift) and 0xFF) * (1 - t) + ((b shr shift) and 0xFF) * t).toInt() shl shift
        return ch(24) or ch(16) or ch(8) or ch(0)
    }
}

/** Animated voice level bars ("Te escucho…"); they move while [running] is true. */
class LevelBars(context: Context, private val count: Int, private val barWidth: Float, private val color: Int) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = this@LevelBars.color }
    private var phase = 0f
    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 900
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { phase = it.animatedValue as Float; invalidate() }
    }
    var running = false
        set(value) {
            field = value
            if (value && isAttachedToWindow) animator.start() else animator.cancel()
            invalidate()
        }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); if (running) animator.start() }
    override fun onDetachedFromWindow() { animator.cancel(); super.onDetachedFromWindow() }

    override fun onDraw(canvas: Canvas) {
        val gap = (width - count * barWidth) / (count - 1).coerceAtLeast(1)
        val h = height.toFloat()
        for (i in 0 until count) {
            // Each bar breathes between 30 % and 100 % of the height, 0.08 of a cycle after the previous one.
            val t = phase - i * 0.0889f
            val scale = if (running) 0.3f + 0.7f * (0.5f - 0.5f * kotlin.math.cos(2 * PI.toFloat() * t)) else 0.3f
            val bar = h * scale
            val x = i * (barWidth + gap)
            canvas.drawRoundRect(RectF(x, (h - bar) / 2, x + barWidth, (h + bar) / 2), barWidth / 2, barWidth / 2, paint)
        }
    }
}
