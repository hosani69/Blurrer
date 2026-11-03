package com.blurrer.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Rect
import android.graphics.RectF
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

enum class BlurStyle { BLUR, PIXEL, SOLID, FACELESS }

data class Settings(
    val style: BlurStyle = BlurStyle.BLUR,
    val strength: Int = 4,      // 1..5
    val detection: Int = 2,     // 1..3
    val outlines: Boolean = false,
)

/** A face (or a box the user drew) in photo pixel coordinates. */
class Face(val box: RectF, var on: Boolean = true, val manual: Boolean = false)

class Photo(val name: String, val bitmap: Bitmap, var faces: MutableList<Face>)

object Blur {
    // Downscale divisors per strength level (index 1..5). These are 4x the original values.
    private val BLUR_DIV = intArrayOf(0, 20, 32, 48, 72, 104)
    private val PIXEL_DIV = intArrayOf(0, 24, 36, 48, 64, 88)

    /** The area that gets blurred: the detector box padded to cover forehead, hair, ears and chin. */
    fun region(f: Face, w: Int, h: Int): RectF {
        if (f.manual) return RectF(f.box)
        val b = f.box
        val px = b.width() * 0.32f
        val pt = b.height() * 0.48f
        val pb = b.height() * 0.26f
        return RectF(
            max(0f, b.left - px), max(0f, b.top - pt),
            min(w.toFloat(), b.right + px), min(h.toFloat(), b.bottom + pb)
        )
    }

    private fun scaled(src: Bitmap, w: Int, h: Int, filter: Boolean): Bitmap =
        Bitmap.createScaledBitmap(src, max(1, w), max(1, h), filter)

    /** Builds the obscured patch for one region. Returns the patch and its top-left in the photo. */
    private fun patch(src: Bitmap, r: RectF, s: Settings): Pair<Bitmap, Rect> {
        val w = max(1, r.width().roundToInt())
        val h = max(1, r.height().roundToInt())
        val pad = (max(w, h) * 0.25f).roundToInt()
        val sx = max(0, r.left.roundToInt() - pad)
        val sy = max(0, r.top.roundToInt() - pad)
        val sw = max(1, min(src.width - sx, w + pad * 2))
        val sh = max(1, min(src.height - sy, h + pad * 2))
        val area = Rect(sx, sy, sx + sw, sy + sh)
        val out = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        if (s.style == BlurStyle.SOLID) {
            c.drawColor(Color.rgb(0x14, 0x16, 0x1b))
            return out to area
        }
        val level = s.strength.coerceIn(1, 5)
        val div = if (s.style == BlurStyle.PIXEL) PIXEL_DIV[level] else BLUR_DIV[level]
        val tw = max(1, sw / div)
        val th = max(1, sh / div)
        val crop = Bitmap.createBitmap(src, sx, sy, sw, sh)
        if (s.style == BlurStyle.PIXEL) {
            val small = scaled(crop, tw, th, true)
            c.drawBitmap(scaled(small, sw, sh, false), 0f, 0f, null)
        } else {
            // Halve repeatedly for a smooth average, then scale back up in two filtered steps.
            var cur = crop
            while (cur.width / 2 > tw && cur.height / 2 > 0) {
                cur = scaled(cur, cur.width / 2, max(1, cur.height / 2), true)
            }
            val small = scaled(cur, tw, th, true)
            val mid = scaled(small, tw * 3, th * 3, true)
            c.drawBitmap(scaled(mid, sw, sh, true), 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
        }
        return out to area
    }

    private fun median(v: IntArray, n: Int): Int { val a = v.copyOf(n); a.sort(); return a[n / 2] }

    /**
     * Faceless: paints the face oval with smooth skin so eyes, brows, nose and mouth disappear,
     * keeping head shape, hair, ears and the photo's lighting.
     */
    private fun faceless(canvas: Canvas, src: Bitmap, f: Face) {
        val b = f.box
        val cx = b.centerX()
        val cy: Float; val rx: Float; val ry: Float
        if (f.manual) { cy = b.centerY(); rx = b.width() / 2f; ry = b.height() / 2f }
        else { cy = b.top + b.height() * 0.47f; rx = b.width() * 0.56f; ry = b.height() * 0.62f }
        val x0 = max(0, (cx - rx).toInt()); val y0 = max(0, (cy - ry).toInt())
        val x1 = min(src.width, (cx + rx).toInt() + 1); val y1 = min(src.height, (cy + ry).toInt() + 1)
        val w = x1 - x0; val h = y1 - y0
        if (w < 2 || h < 2) return

        // Skin tone: per-channel median of the cheeks and forehead.
        val bx = max(0, b.left.toInt()); val by = max(0, b.top.toInt())
        val bw = min(src.width - bx, b.width().toInt()); val bh = min(src.height - by, b.height().toInt())
        if (bw < 2 || bh < 2) return
        val n = 48
        val sample = Bitmap.createScaledBitmap(Bitmap.createBitmap(src, bx, by, bw, bh), n, n, true)
        val px = IntArray(n * n); sample.getPixels(px, 0, n, 0, 0, n, n)
        val zones = if (f.manual) arrayOf(floatArrayOf(.2f, .8f, .2f, .8f))
        else arrayOf(floatArrayOf(.12f, .32f, .5f, .72f), floatArrayOf(.68f, .88f, .5f, .72f), floatArrayOf(.35f, .65f, .02f, .14f))
        val r = IntArray(n * n); val g = IntArray(n * n); val bl = IntArray(n * n); var k = 0
        for (z in zones) {
            for (y in (z[2] * n).toInt() until (z[3] * n).toInt()) for (x in (z[0] * n).toInt() until (z[1] * n).toInt()) {
                val c = px[y * n + x]; r[k] = Color.red(c); g[k] = Color.green(c); bl[k] = Color.blue(c); k++
            }
        }
        if (k == 0) return
        val skin = Color.rgb(median(r, k), median(g, k), median(bl, k))

        // Lighting: a very coarse version of the area keeps shading without any features.
        val lo = Bitmap.createScaledBitmap(Bitmap.createBitmap(src, x0, y0, w, h), 3, 4, true)
        val layer = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val lc = Canvas(layer)
        lc.drawBitmap(Bitmap.createScaledBitmap(lo, w, h, true), 0f, 0f, null)
        lc.drawRect(0f, 0f, w.toFloat(), h.toFloat(), Paint().apply { color = skin; alpha = 184 })

        // Feathered oval mask.
        val mask = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
            shader = RadialGradient(0f, 0f, 1f, intArrayOf(Color.BLACK, Color.BLACK, Color.TRANSPARENT),
                floatArrayOf(0f, 0.74f, 1f), Shader.TileMode.CLAMP)
        }
        lc.save()
        lc.translate(cx - x0, cy - y0); lc.scale(rx, ry)
        lc.drawRect(-1f, -1f, 1f, 1f, mask)
        lc.restore()
        canvas.drawBitmap(layer, x0.toFloat(), y0.toFloat(), null)
    }

    /** Draws the photo with every switched-on face obscured. */
    fun render(p: Photo, s: Settings): Bitmap {
        val src = p.bitmap
        val out = src.copy(Bitmap.Config.ARGB_8888, true)
        val c = Canvas(out)
        for (f in p.faces) {
            if (!f.on) continue
            if (s.style == BlurStyle.FACELESS) { faceless(c, src, f); continue }
            val r = region(f, src.width, src.height)
            if (r.width() < 1 || r.height() < 1) continue
            val (bmp, at) = patch(src, r, s)
            val clip = Path().apply {
                if (f.manual) addRect(r, Path.Direction.CW) else addOval(r, Path.Direction.CW)
            }
            c.save()
            c.clipPath(clip)
            c.drawBitmap(bmp, at.left.toFloat(), at.top.toFloat(), null)
            c.restore()
        }
        return out
    }
}
