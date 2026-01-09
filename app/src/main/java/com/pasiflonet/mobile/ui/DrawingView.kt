package com.pasiflonet.mobile.ui

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.max
import kotlin.math.min

class DrawingView(context: Context, attrs: AttributeSet) : View(context, attrs) {

    private val borderPaint = Paint().apply {
        color = Color.RED
        style = Paint.Style.STROKE
        strokeWidth = 5f
        pathEffect = DashPathEffect(floatArrayOf(10f, 20f), 0f)
    }

    private val fillPaint = Paint().apply {
        color = 0x55000000
        style = Paint.Style.FILL
    }

    var isBlurMode = false
    private var startX = 0f
    private var startY = 0f
    private var currentX = 0f
    private var currentY = 0f
    private var isDrawing = false

    // RectF values are RELATIVE (0..1)
    val rects = mutableListOf<RectF>()

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isBlurMode) return false

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.x
                startY = event.y
                currentX = event.x
                currentY = event.y
                isDrawing = true
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                currentX = event.x
                currentY = event.y
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isDrawing = false
                if (width > 0 && height > 0) {
                    val l = min(startX, currentX) / width
                    val t = min(startY, currentY) / height
                    val r = max(startX, currentX) / width
                    val b = max(startY, currentY) / height
                    rects.add(RectF(l, t, r, b))
                }
                invalidate()
                return true
            }
        }
        return false
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        for (r in rects) {
            val l = r.left * width
            val t = r.top * height
            val rr = r.right * width
            val bb = r.bottom * height
            canvas.drawRect(l, t, rr, bb, fillPaint)
            canvas.drawRect(l, t, rr, bb, borderPaint)
        }

        if (isDrawing) {
            canvas.drawRect(
                min(startX, currentX),
                min(startY, currentY),
                max(startX, currentX),
                max(startY, currentY),
                borderPaint
            )
        }
    }

    fun setRects(newRects: List<RectF>) {
        rects.clear()
        rects.addAll(newRects)
        invalidate()
    }

    fun undo() {
        if (rects.isNotEmpty()) {
            rects.removeAt(rects.lastIndex)
            invalidate()
        }
    }

    fun clear() {
        rects.clear()
        invalidate()
    }
}
