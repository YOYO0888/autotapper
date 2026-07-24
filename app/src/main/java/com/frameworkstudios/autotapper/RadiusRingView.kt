package com.frameworkstudios.autotapper

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View

/** Draws a dashed-looking ring to visualize the tap-randomization zone. Purely visual, not touchable. */
class RadiusRingView(context: Context) : View(context) {

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF4081")
        style = Paint.Style.STROKE
        strokeWidth = 4f
        alpha = 200
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF4081")
        style = Paint.Style.FILL
        alpha = 25
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val radius = (minOf(width, height) / 2f) - 4f
        val cx = width / 2f
        val cy = height / 2f
        canvas.drawCircle(cx, cy, radius, fillPaint)
        canvas.drawCircle(cx, cy, radius, ringPaint)
    }
}
