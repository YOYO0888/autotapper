package com.frameworkstudios.autotapper

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View

/** A small draggable circular crosshair used to mark a point on screen. */
class CrosshairView(context: Context, private val color: Int, private val label: String) :
    View(context) {

    private val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = this@CrosshairView.color
        style = Paint.Style.STROKE
        strokeWidth = 6f
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = this@CrosshairView.color
        alpha = 60
        style = Paint.Style.FILL
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = Color.WHITE
        textSize = 28f
        textAlign = Paint.Align.CENTER
    }

    /** Called with raw screen coordinates whenever the user drags this view. */
    var onMoved: ((rawX: Float, rawY: Float) -> Unit)? = null

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val radius = width / 2f - 8f

        canvas.drawCircle(cx, cy, radius, fillPaint)
        canvas.drawCircle(cx, cy, radius, circlePaint)
        canvas.drawLine(cx - radius, cy, cx + radius, cy, circlePaint)
        canvas.drawLine(cx, cy - radius, cx, cy + radius, circlePaint)
        canvas.drawText(label, cx, cy + radius + 32f, textPaint)
    }

    private var downRawX = 0f
    private var downRawY = 0f

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = event.rawX
                downRawY = event.rawY
            }
            MotionEvent.ACTION_MOVE -> {
                onMoved?.invoke(event.rawX, event.rawY)
            }
        }
        return true
    }
}
