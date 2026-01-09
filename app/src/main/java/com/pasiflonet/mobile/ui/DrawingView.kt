package com.pasiflonet.mobile.ui

import android.graphics.RectF
import com.pasiflonet.mobile.BlurRect

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.pasiflonet.mobile.utils.BlurRect

class DrawingView(context: Context, attrs: AttributeSet) : View(context, attrs) {
    private val paint = Paint().apply { 
        color = Color.RED
        style = Paint.Style.STROKE
        strokeWidth = 5f
        pathEffect = DashPathEffect(floatArrayOf(10f, 20f), 0f) 
    }
    private val blurFill = Paint().apply { 
        color = 0x55000000
        style = Paint.Style.FILL 
    }
    
    var isBlurMode = false
    private var startX = 0f; private var startY = 0f; private var currentX = 0f; private var currentY = 0f; private var isDrawing = false
    val rects = mutableListOf<BlurRect>()

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isBlurMode) return false
        when (event.action) {
            MotionEvent.ACTION_DOWN -> { startX = event.x; startY = event.y; currentX = event.x; currentY = event.y; isDrawing = true; invalidate() }
            MotionEvent.ACTION_MOVE -> { currentX = event.x; currentY = event.y; invalidate() }
            MotionEvent.ACTION_UP -> {
                isDrawing = false
                if (width > 0 && height > 0) rects.add(BlurRect(Math.min(startX, currentX)/width, Math.min(startY, currentY)/height, Math.max(startX, currentX)/width, Math.max(startY, currentY)/height))
                invalidate()
            }
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        rects.forEach { r -> canvas.drawRect(r.left * width, r.top * height, r.right * width, r.bottom * height, blurFill); canvas.drawRect(r.left * width, r.top * height, r.right * width, r.bottom * height, paint) }
        if (isDrawing) canvas.drawRect(Math.min(startX, currentX), Math.min(startY, currentY), Math.max(startX, currentX), Math.max(startY, currentY), paint)
    }

    fun setRects(newRects: List<com.pasiflonet.mobile.BlurRect>) {
        try {
            val field = this.javaClass.getDeclaredField("rects").apply { isAccessible = true }
            val cur = field.get(this)
            if (cur is MutableList<*>) {
                @Suppress("UNCHECKED_CAST")
                val rectList = cur as MutableList<android.graphics.RectF>
                rectList.clear()
                for (r in newRects) {
                    rectList.add(android.graphics.RectF(r.left, r.top, r.right, r.bottom))
                }
                invalidate()
            }
        } catch (_: Throwable) {
            // keep silent
        }
    }


    fun setRects(newRects: List<android.graphics.RectF>) {
        try {
            val field = this.javaClass.getDeclaredField("rects").apply { isAccessible = true }
            val cur = field.get(this)
            if (cur is MutableList<*>) {
                @Suppress("UNCHECKED_CAST")
                val rectList = cur as MutableList<android.graphics.RectF>
                rectList.clear()
                rectList.addAll(newRects)
                invalidate()
            }
        } catch (_: Throwable) {
            // keep silent
        }
    }



    fun setRects(newRects: List<RectF>) {
        try {
            // Prefer a real rects field if it exists
            val f = this.javaClass.getDeclaredField("rects").apply { isAccessible = true }
            val cur = f.get(this)
            if (cur is MutableList<*>) {
                @Suppress("UNCHECKED_CAST")
                val list = cur as MutableList<RectF>
                list.clear()
                list.addAll(newRects)
                invalidate()
                return
            }
        } catch (_: Throwable) {
            // fallback below
        }

        try {
            // fallback: look for property named rects via Kotlin getter pattern
            val m = this.javaClass.methods.firstOrNull { it.name == "getRects" && it.parameterCount == 0 }
            val cur = m?.invoke(this)
            if (cur is MutableList<*>) {
                @Suppress("UNCHECKED_CAST")
                val list = cur as MutableList<RectF>
                list.clear()
                list.addAll(newRects)
                invalidate()
            }
        } catch (_: Throwable) {
        }
    }

    fun setRects(newRects: List<BlurRect>) {
        val asRectF = ArrayList<RectF>(newRects.size)
        for (r in newRects) asRectF.add(RectF(r.left, r.top, r.right, r.bottom))
        setRects(asRectF)
    }

}
