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
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Shows the blurred photo.
 * One finger: tap a face to switch its blur off or on, or drag to add a box over a missed face.
 * Two fingers: pinch to zoom and move around the photo.
 */
class FaceEditView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {
    var photo: Photo? = null
        set(v) { field = v; rendered = null; zoom.reset(); invalidate() }
    var rendered: Bitmap? = null
        set(v) { field = v; invalidate() }
    var showOutlines = false
        set(v) { field = v; invalidate() }

    /** Called after the user changes the faces (tap or drag). */
    var onFacesChanged: (() -> Unit)? = null

    private val fit = Matrix()
    private val zoom = Matrix()
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
    private var multiTouch = false      // true from the second finger down until every finger is up
    private var lastFocusX = 0f
    private var lastFocusY = 0f

    private val scaler = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            val cur = currentZoom()
            val f = (cur * d.scaleFactor).coerceIn(1f, 8f) / cur
            zoom.postScale(f, f, d.focusX, d.focusY)
            clampZoom()
            invalidate()
            return true
        }
    }).apply { isQuickScaleEnabled = false }

    private fun currentZoom(): Float { val v = FloatArray(9); zoom.getValues(v); return v[Matrix.MSCALE_X] }

    private fun updateMatrix() {
        val p = photo ?: return
        val bw = p.bitmap.width.toFloat()
        val bh = p.bitmap.height.toFloat()
        val s = min(width / bw, height / bh)
        fit.reset()
        fit.postScale(s, s)
        fit.postTranslate((width - bw * s) / 2f, (height - bh * s) / 2f)
        toView.set(fit)
        toView.postConcat(zoom)
        toView.invert(toImage)
    }

    /** Keeps the zoomed photo covering the view (or centred when it is smaller than the view). */
    private fun clampZoom() {
        val p = photo ?: return
        if (currentZoom() <= 1.001f) { zoom.reset(); return }
        updateMatrix()
        val r = RectF(0f, 0f, p.bitmap.width.toFloat(), p.bitmap.height.toFloat())
        toView.mapRect(r)
        var dx = 0f; var dy = 0f
        if (r.width() <= width) dx = (width - r.width()) / 2f - r.left
        else if (r.left > 0) dx = -r.left else if (r.right < width) dx = width - r.right
        if (r.height() <= height) dy = (height - r.height()) / 2f - r.top
        else if (r.top > 0) dy = -r.top else if (r.bottom < height) dy = height - r.bottom
        zoom.postTranslate(dx, dy)
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

    private fun focus(e: MotionEvent): Pair<Float, Float> {
        var x = 0f; var y = 0f
        val skip = if (e.actionMasked == MotionEvent.ACTION_POINTER_UP) e.actionIndex else -1
        var n = 0
        for (i in 0 until e.pointerCount) { if (i == skip) continue; x += e.getX(i); y += e.getY(i); n++ }
        return if (n == 0) e.x to e.y else x / n to y / n
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val p = photo ?: return false
        scaler.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                downX = e.x; downY = e.y; dragRect = null; multiTouch = false
            }
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> {
                multiTouch = true
                dragRect = null
                val (fx, fy) = focus(e); lastFocusX = fx; lastFocusY = fy
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                if (multiTouch) {
                    if (e.pointerCount >= 2) {
                        val (fx, fy) = focus(e)
                        zoom.postTranslate(fx - lastFocusX, fy - lastFocusY)
                        lastFocusX = fx; lastFocusY = fy
                        clampZoom()
                        invalidate()
                    }
                } else if (dragRect != null || abs(e.x - downX) > 12 * dp || abs(e.y - downY) > 12 * dp) {
                    dragRect = RectF(min(downX, e.x), min(downY, e.y), max(downX, e.x), max(downY, e.y))
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP -> {
                if (multiTouch) { multiTouch = false; return true }
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
            MotionEvent.ACTION_CANCEL -> { dragRect = null; multiTouch = false; invalidate() }
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
