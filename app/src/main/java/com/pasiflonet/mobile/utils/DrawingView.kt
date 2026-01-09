package com.pasiflonet.mobile.utils

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.max
import kotlin.math.min

class DrawingView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // RectF values are RELATIVE (0..1) inside validBounds
    private val relativeRects = mutableListOf<RectF>()

    private val fillPaint = Paint().apply {
        color = 0x55000000
        style = Paint.Style.FILL
    }

    private val borderPaint = Paint().apply {
        color = Color.RED
        style = Paint.Style.STROKE
        strokeWidth = 5f
    }

    var isBlurMode = false
    private var startX = 0f
    private var startY = 0f
    private var currentX = 0f
    private var currentY = 0f
    private var isDrawing = false

    private var validBounds = RectF(0f, 0f, 0f, 0f)

    fun setValidBounds(bounds: RectF) {
        validBounds = bounds
        invalidate()
    }

    val rects: List<RectF>
        get() = relativeRects.toList()

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isBlurMode) return false
        if (validBounds.width() <= 0f || validBounds.height() <= 0f) return false

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

                var left = min(startX, currentX)
                var right = max(startX, currentX)
                var top = min(startY, currentY)
                var bottom = max(startY, currentY)

                // clip to valid bounds
                left = left.coerceIn(validBounds.left, validBounds.right)
                right = right.coerceIn(validBounds.left, validBounds.right)
                top = top.coerceIn(validBounds.top, validBounds.bottom)
                bottom = bottom.coerceIn(validBounds.top, validBounds.bottom)

                if (right - left > 10f && bottom - top > 10f) {
                    val relLeft = (left - validBounds.left) / validBounds.width()
                    val relRight = (right - validBounds.left) / validBounds.width()
                    val relTop = (top - validBounds.top) / validBounds.height()
                    val relBottom = (bottom - validBounds.top) / validBounds.height()
                    relativeRects.add(RectF(relLeft, relTop, relRight, relBottom))
                }

                invalidate()
                return true
            }
        }
        return false
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (validBounds.width() <= 0f || validBounds.height() <= 0f) return

        // draw saved rects
        for (r in relativeRects) {
            val absLeft = validBounds.left + (r.left * validBounds.width())
            val absRight = validBounds.left + (r.right * validBounds.width())
            val absTop = validBounds.top + (r.top * validBounds.height())
            val absBottom = validBounds.top + (r.bottom * validBounds.height())

            canvas.drawRect(absLeft, absTop, absRight, absBottom, fillPaint)
            canvas.drawRect(absLeft, absTop, absRight, absBottom, borderPaint)
        }

        // draw current drag rect
        if (isDrawing) {
            val l = min(startX, currentX)
            val r = max(startX, currentX)
            val t = min(startY, currentY)
            val b = max(startY, currentY)
            canvas.drawRect(l, t, r, b, borderPaint)
        }
    }

    fun undo() {
        if (relativeRects.isNotEmpty()) {
            relativeRects.removeAt(relativeRects.lastIndex)
            invalidate()
        }
    }

    fun clear() {
        relativeRects.clear()
        invalidate()
    }

    fun setRects(newRects: List<RectF>) {
        relativeRects.clear()
        relativeRects.addAll(newRects)
        invalidate()
    }
}
