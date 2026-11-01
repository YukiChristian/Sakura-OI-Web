package com.sakura.webapp

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.SweepGradient
import android.view.View
import android.view.animation.LinearInterpolator

/** Loading spinner: a rotating arc painted with the rainbow outline of the Sakura OI Web logo. */
class LogoSpinnerView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val bounds = RectF()
    private var rotationDegrees = 0f
    private val animator = ValueAnimator.ofFloat(0f, 360f).apply {
        duration = 1_100L
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            rotationDegrees = it.animatedValue as Float
            invalidate()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val stroke = minOf(w, h) * 0.12f
        paint.strokeWidth = stroke
        bounds.set(stroke / 2, stroke / 2, w - stroke / 2, h - stroke / 2)
        paint.shader = SweepGradient(w / 2f, h / 2f, LOGO_COLORS, null)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.save()
        canvas.rotate(rotationDegrees, width / 2f, height / 2f)
        canvas.drawArc(bounds, 0f, 300f, false, paint)
        canvas.restore()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        animator.start()
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (isVisible) animator.resume() else animator.pause()
    }

    private companion object {
        /** Colours of the logo's petal outline, going round the flower. */
        val LOGO_COLORS = intArrayOf(
            Color.rgb(255, 0, 128),
            Color.rgb(255, 16, 16),
            Color.rgb(255, 128, 0),
            Color.rgb(255, 200, 0),
            Color.rgb(0, 176, 64),
            Color.rgb(0, 190, 255),
            Color.rgb(16, 64, 255),
            Color.rgb(140, 16, 255),
            Color.rgb(255, 0, 128),
        )
    }
}
