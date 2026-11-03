package com.blurrer.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Shows the blurred photo. Tap a face to switch its blur off or on; drag to add a box over a missed face.
 */
class FaceEditView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {
    var photo: Photo? = null
        set(v) { field = v; rendered = null; invalidate() }
    var rendered: Bitmap? = null
        set(v) { field = v; invalidate() }
    var showOutlines = false
        set(v) { field = v; invalidate() }

    /** Called after the user changes the faces (tap or drag). */
    var onFacesChanged: (() -> Unit)? = null

    private val toView = Matrix()
    private val toImage = Matrix()
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val dp = resources.displayMetrics.density
    private val onPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2 * dp; color = Color.argb(240, 124, 145, 255)
    }
    private val offPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2 * dp; color = Color.argb(240, 255, 190, 90)
        pathEffect = DashPathEffect(floatArrayOf(6 * dp, 4 * dp), 0f)
    }
    private val dragPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2 * dp; color = Color.WHITE
        pathEffect = DashPathEffect(floatArrayOf(6 * dp, 4 * dp), 0f)
    }

    private var downX = 0f
    private var downY = 0f
    private var dragRect: RectF? = null

    private fun updateMatrix() {
        val p = photo ?: return
        val bw = p.bitmap.width.toFloat()
        val bh = p.bitmap.height.toFloat()
        val s = min(width / bw, height / bh)
        toView.reset()
        toView.postScale(s, s)
        toView.postTranslate((width - bw * s) / 2f, (height - bh * s) / 2f)
        toView.invert(toImage)
    }

    override fun onDraw(canvas: Canvas) {
        val p = photo ?: return
        updateMatrix()
        canvas.drawBitmap(rendered ?: p.bitmap, toView, bmpPaint)
        val anyOff = p.faces.any { !it.on }
        if (showOutlines || anyOff) {
            for (f in p.faces) {
                val r = RectF(Blur.region(f, p.bitmap.width, p.bitmap.height))
                toView.mapRect(r)
                val paint = if (f.on) onPaint else offPaint
                if (f.manual) canvas.drawRect(r, paint) else canvas.drawOval(r, paint)
            }
        }
        dragRect?.let { canvas.drawRect(it, dragPaint) }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val p = photo ?: return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                downX = e.x; downY = e.y; dragRect = null
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragRect != null || abs(e.x - downX) > 12 * dp || abs(e.y - downY) > 12 * dp) {
                    dragRect = RectF(min(downX, e.x), min(downY, e.y), max(downX, e.x), max(downY, e.y))
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP -> {
                val d = dragRect
                dragRect = null
                if (d != null) {
                    val r = RectF(d); toImage.mapRect(r)
                    r.intersect(0f, 0f, p.bitmap.width.toFloat(), p.bitmap.height.toFloat())
                    if (r.width() > 4 && r.height() > 4) p.faces.add(Face(r, on = true, manual = true))
                } else {
                    toggleAt(p, e.x, e.y)
                    performClick()
                }
                invalidate()
                onFacesChanged?.invoke()
            }
            MotionEvent.ACTION_CANCEL -> { dragRect = null; invalidate() }
        }
        return true
    }

    override fun performClick(): Boolean { super.performClick(); return true }

    private fun toggleAt(p: Photo, x: Float, y: Float) {
        val pt = floatArrayOf(x, y); toImage.mapPoints(pt)
        val hit = p.faces
            .filter { Blur.region(it, p.bitmap.width, p.bitmap.height).contains(pt[0], pt[1]) }
            .minByOrNull { val r = Blur.region(it, p.bitmap.width, p.bitmap.height); r.width() * r.height() }
            ?: return
        if (hit.manual && hit.on) p.faces.remove(hit) else hit.on = !hit.on
    }
}
