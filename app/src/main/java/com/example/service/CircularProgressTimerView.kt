package com.example.service

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

class CircularProgressTimerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E50C101B") // Dark translucent background
        style = Paint.Style.FILL
    }
    
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1500F0FF") // Soft cyan track
        style = Paint.Style.STROKE
        strokeWidth = 6f
    }
    
    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00F0FF") // Bright neon cyan active progress
        style = Paint.Style.STROKE
        strokeWidth = 6f
        strokeCap = Paint.Cap.ROUND
    }

    private val rectF = RectF()
    private var progressFraction = 1.0f

    fun setProgress(fraction: Float) {
        progressFraction = fraction.coerceIn(0.0f, 1.0f)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val radius = Math.min(w, h) / 2f
        val stroke = progressPaint.strokeWidth

        // Draw circle background
        canvas.drawCircle(w / 2f, h / 2f, radius - stroke / 2f, backgroundPaint)

        // Draw track
        canvas.drawCircle(w / 2f, h / 2f, radius - stroke / 2f, borderPaint)

        // Draw active progress arc (clockwise sweeping countdown)
        rectF.set(stroke / 2f, stroke / 2f, w - stroke / 2f, h - stroke / 2f)
        val sweepAngle = 360f * progressFraction
        canvas.drawArc(rectF, -90f, sweepAngle, false, progressPaint)
    }
}
