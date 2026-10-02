package com.camscan.app.domain.processor

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Residual skew correction applied AFTER perspective warp.
 *
 * Estimates the dominant line angle in the rectified document (text
 * baselines, ruling lines, table lines, page borders) with a lightweight
 * Hough transform, then rotates by the negative residual. Only small
 * corrections (|angle| in 0.4..15 deg) are applied so already-straight
 * pages are never degraded.
 *
 * Primary estimate comes from HORIZONTAL baselines (line normal near 90 deg,
 * skew = 90 - normal); text rows are far more abundant than vertical rules, so
 * this works on text-heavy pages that have no vertical structure at all.
 * Vertical lines (normal near 0/180 deg) are the fallback.
 *
 * This is rotation only — perspective itself is handled by
 * [PerspectiveWarper]. It never receives the original photograph, only the
 * already-extracted document bitmap (pipeline rule).
 */
object DeskewHelper {

    /** Accepted skew range in degrees. */
    const val MIN_SKEW_DEG = 0.4f
    const val MAX_SKEW_DEG = 15f

    /** Normal-angle band (in degrees around 90) treated as "horizontal". */
    private const val HORIZONTAL_BAND = 30f

    /** Minimum projection-histogram peakiness to trust an estimate. */
    private const val MIN_PEAKINESS = 0.08

    /** Estimated skew angle in degrees (positive = clockwise). NaN if unknown. */
    fun estimateSkewAngle(documentBitmap: Bitmap): Float {
        try {
            val w = documentBitmap.width
            val h = documentBitmap.height
            if (w < 100 || h < 100) return Float.NaN

            val maxDim = 400
            val scale = minOf(1f, maxDim.toFloat() / maxOf(w, h))
            val sw = maxOf(80, (w * scale).toInt())
            val sh = maxOf(80, (h * scale).toInt())
            val small = if (sw != w || sh != h) {
                Bitmap.createScaledBitmap(documentBitmap, sw, sh, true)
            } else documentBitmap

            try {
                val pixels = IntArray(sw * sh)
                small.getPixels(pixels, 0, sw, 0, 0, sw, sh)
                val gray = FloatArray(sw * sh)
                for (i in pixels.indices) {
                    val c = pixels[i]
                    gray[i] = (0.299f * ((c shr 16) and 0xFF) +
                        0.587f * ((c shr 8) and 0xFF) + 0.114f * (c and 0xFF))
                }
                // Sobel magnitude, simple threshold -> binary edge map.
                val edges = ByteArray(sw * sh)
                for (y in 1 until sh - 1) {
                    for (x in 1 until sw - 1) {
                        val i = y * sw + x
                        val gx = (-gray[i - sw - 1] - 2 * gray[i - 1] - gray[i + sw - 1] +
                            gray[i - sw + 1] + 2 * gray[i + 1] + gray[i + sw + 1])
                        val gy = (-gray[i - sw - 1] - 2 * gray[i - sw] - gray[i - sw + 1] +
                            gray[i + sw - 1] + 2 * gray[i + sw] + gray[i + sw + 1])
                        if (hypot(gx.toDouble(), gy.toDouble()) > 90.0) edges[i] = 1.toByte()
                    }
                }

                // Collect edge coordinates once (2px subsampled).
                val ex = IntArray(sw * sh)
                val ey = IntArray(sw * sh)
                var n = 0
                for (y in 0 until sh step 2) {
                    for (x in 0 until sw step 2) {
                        if (edges[y * sw + x] == 0.toByte()) continue
                        ex[n] = x
                        ey[n] = y
                        n++
                    }
                }
                if (n < 300) return Float.NaN

                // Full 180 deg sweep of line-normal angles, 0.5 deg bins.
                val step = 0.5f
                val bins = (180f / step).toInt() + 1
                val cosT = FloatArray(bins)
                val sinT = FloatArray(bins)
                for (b in 0 until bins) {
                    val rad = Math.toRadians((b * step).toDouble())
                    cosT[b] = cos(rad).toFloat()
                    sinT[b] = sin(rad).toFloat()
                }
                val maxRho = hypot(sw.toDouble(), sh.toDouble()).toFloat()
                val histBins = 180

                var bestHorizontal = Float.NaN
                var bestHorizontalScore = -1.0
                var bestVertical = Float.NaN
                var bestVerticalScore = -1.0

                for (b in 0 until bins) {
                    val deg = b * step
                    val isHorizontalBand = abs(deg - 90f) <= HORIZONTAL_BAND
                    val isVerticalBand = deg <= 30f || deg >= 150f
                    if (!isHorizontalBand && !isVerticalBand) continue

                    val hist = IntArray(histBins)
                    for (i in 0 until n) {
                        val rho = ex[i] * cosT[b] + ey[i] * sinT[b]
                        val hb = (((rho + maxRho) / (2 * maxRho)) * histBins).toInt()
                            .coerceIn(0, histBins - 1)
                        hist[hb]++
                    }
                    // Peakiness = top-8 histogram mass / number of sampled edges.
                    hist.sortDescending()
                    var top = 0
                    for (k in 0 until 8) top += hist[k]
                    val score = top.toDouble() / n
                    if (isHorizontalBand) {
                        if (score > bestHorizontalScore) {
                            bestHorizontalScore = score
                            // Skew = deviation of the normal from 90 deg.
                            bestHorizontal = 90f - deg
                        }
                    } else {
                        if (score > bestVerticalScore) {
                            bestVerticalScore = score
                            val signed = if (deg > 90f) 180f - deg else deg
                            bestVertical = signed
                        }
                    }
                }

                val primary = if (bestHorizontalScore >= bestVerticalScore) bestHorizontal else bestVertical
                val primaryScore = maxOf(bestHorizontalScore, bestVerticalScore)
                if (primaryScore < MIN_PEAKINESS) return Float.NaN

                var angle = primary
                // Reflect into (-90, 90]: a rotation of +a and -a are the two
                // plausible readings of an unoriented line set.
                while (angle > 90f) angle -= 180f
                while (angle < -90f) angle += 180f
                if (abs(angle) < MIN_SKEW_DEG || abs(angle) > MAX_SKEW_DEG) return Float.NaN
                return angle
            } finally {
                if (small != documentBitmap) small.recycle()
            }
        } catch (e: Exception) {
            return Float.NaN
        }
    }

    /**
     * Returns a deskewed copy, or the input bitmap itself when no reliable
     * skew was found (no-op, no copy).
     */
    fun deskew(documentBitmap: Bitmap): Bitmap {
        val angle = estimateSkewAngle(documentBitmap)
        if (angle.isNaN() || abs(angle) < MIN_SKEW_DEG || abs(angle) > MAX_SKEW_DEG) {
            return documentBitmap
        }
        return try {
            val m = Matrix().apply { postRotate(-angle) }
            val out = Bitmap.createBitmap(
                documentBitmap, 0, 0,
                documentBitmap.width, documentBitmap.height, m, true
            )
            // Rotation introduces transparent corners; flatten onto white.
            val flat = Bitmap.createBitmap(out.width, out.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(flat)
            canvas.drawColor(Color.WHITE)
            canvas.drawBitmap(out, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
            if (out != documentBitmap) out.recycle()
            flat
        } catch (e: Exception) {
            documentBitmap
        }
    }
}
