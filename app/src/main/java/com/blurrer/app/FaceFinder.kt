package com.blurrer.app

import android.graphics.Bitmap
import android.graphics.RectF
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlinx.coroutines.tasks.await
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Finds faces on the device with ML Kit. Large photos are also scanned in tiles to catch small faces. */
class FaceFinder {
    private val detectors = HashMap<Float, FaceDetector>()

    private fun detector(minFace: Float): FaceDetector = detectors.getOrPut(minFace) {
        FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
                .setMinFaceSize(minFace)
                .build()
        )
    }

    private suspend fun run(bmp: Bitmap, minFace: Float, scale: Float, ox: Float, oy: Float, into: MutableList<RectF>) {
        val faces = detector(minFace).process(InputImage.fromBitmap(bmp, 0)).await()
        for (f in faces) {
            val b = f.boundingBox
            into += RectF(b.left / scale + ox, b.top / scale + oy, b.right / scale + ox, b.bottom / scale + oy)
        }
    }

    private fun fit(src: Bitmap, x: Int, y: Int, w: Int, h: Int, maxSide: Int): Pair<Bitmap, Float> {
        val s = min(1f, maxSide.toFloat() / max(w, h))
        val crop = if (x == 0 && y == 0 && w == src.width && h == src.height) src else Bitmap.createBitmap(src, x, y, w, h)
        if (s >= 1f) return crop to 1f
        return Bitmap.createScaledBitmap(crop, max(1, (w * s).roundToInt()), max(1, (h * s).roundToInt()), true) to s
    }

    suspend fun find(src: Bitmap, detection: Int): MutableList<Face> {
        val minFace = when (detection) { 1 -> 0.1f; 2 -> 0.05f; else -> 0.03f }
        val found = mutableListOf<RectF>()

        // 1) the whole photo
        val (whole, s) = fit(src, 0, 0, src.width, src.height, 1600)
        run(whole, minFace, s, 0f, 0f, found)

        // 2) overlapping tiles (2x2, or 3x3 on "catch everything") for small faces in group shots
        if (detection >= 2 && max(src.width, src.height) > 1200) {
            val n = if (detection >= 3) 3 else 2
            val tw = src.width / n.toFloat()
            val th = src.height / n.toFloat()
            val ov = 0.15f
            for (ty in 0 until n) for (tx in 0 until n) {
                val x0 = max(0f, (tx - ov) * tw).toInt()
                val y0 = max(0f, (ty - ov) * th).toInt()
                val x1 = min(src.width.toFloat(), (tx + 1 + ov) * tw).toInt()
                val y1 = min(src.height.toFloat(), (ty + 1 + ov) * th).toInt()
                if (x1 - x0 < 32 || y1 - y0 < 32) continue
                val (tile, ts) = fit(src, x0, y0, x1 - x0, y1 - y0, 1280)
                run(tile, 0.1f, ts, x0.toFloat(), y0.toFloat(), found)
            }
        }
        return merge(found).map { Face(it) }.toMutableList()
    }

    companion object {
        private fun iou(a: RectF, b: RectF): Float {
            val iw = max(0f, min(a.right, b.right) - max(a.left, b.left))
            val ih = max(0f, min(a.bottom, b.bottom) - max(a.top, b.top))
            val i = iw * ih
            val u = a.width() * a.height() + b.width() * b.height() - i
            return if (u <= 0f) 0f else i / u
        }

        private fun centreInside(outer: RectF, inner: RectF) = outer.contains(inner.centerX(), inner.centerY())

        /** Merges duplicates from overlapping passes; keeps the larger box of each pair. */
        fun merge(boxes: List<RectF>): List<RectF> {
            val sorted = boxes.sortedByDescending { it.width() * it.height() }
            val keep = mutableListOf<RectF>()
            for (b in sorted) {
                val dup = keep.any { k ->
                    iou(k, b) > 0.3f || (centreInside(k, b) && b.width() * b.height() > k.width() * k.height() * 0.12f)
                }
                if (!dup) keep += b
            }
            return keep
        }
    }
}
