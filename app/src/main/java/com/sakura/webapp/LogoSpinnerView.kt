package com.sakura.webapp

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import android.view.animation.LinearInterpolator

/** Loading spinner shown under the Sakura OI Web logo: a rotating arc in a single [color]. */
class LogoSpinnerView(context: Context, color: Int) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        this.color = color
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
}
