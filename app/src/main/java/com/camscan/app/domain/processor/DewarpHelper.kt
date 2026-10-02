package com.camscan.app.domain.processor

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Optional curved-page (book / notebook) flattening.
 *
 * Conservative by design: it measures horizontal ruling/text-line bow across
 * three vertical strips and only remaps rows when consistent cylindrical
 * curvature evidence exists. Normal flat documents are returned untouched
 * (same instance, no copy).
 *
 * Like deskew, this stage only ever receives the extracted document bitmap.
 */
object DewarpHelper {

    data class WarpEvidence(val bowPx: Float, val consistent: Boolean)

    /** Measure page-curl evidence; used by debug UI as well. */
    fun measureCurl(documentBitmap: Bitmap): WarpEvidence {
        try {
            val w = documentBitmap.width
            val h = documentBitmap.height
            if (w < 200 || h < 200) return WarpEvidence(0f, false)

            val sw = 240
            val sh = (h.toFloat() / w * sw).toInt().coerceIn(120, 340)
            val small = Bitmap.createScaledBitmap(documentBitmap, sw, sh, true)
            try {
                val pixels = IntArray(sw * sh)
                small.getPixels(pixels, 0, sw, 0, 0, sw, sh)
                // Dark-pixel rows per vertical third (text/ruling mass).
                fun rowProfile(x0: Int, x1: Int): FloatArray {
                    val rows = FloatArray(sh)
                    for (y in 0 until sh) {
                        var dark = 0
                        var n = 0
                        for (x in x0 until x1 step 2) {
                            val c = pixels[y * sw + x]
                            val lum = (0.299f * ((c shr 16) and 0xFF) +
                                0.587f * ((c shr 8) and 0xFF) + 0.114f * (c and 0xFF))
                            if (lum < 150) dark++
                            n++
                        }
                        rows[y] = if (n == 0) 0f else dark.toFloat() / n
                    }
                    return rows
                }
                val left = rowProfile(0, sw / 3)
                val mid = rowProfile(sw / 3, 2 * sw / 3)
                val right = rowProfile(2 * sw / 3, sw)

                // Dominant text-row positions via peak picking on mid strip.
                val peaks = findPeaks(mid, 6)
                if (peaks.size < 3) return WarpEvidence(0f, false)

                var totalBow = 0f
                var consistentVotes = 0
                for (pk in peaks) {
                    val lPk = nearestPeak(left, pk, 14)
                    val rPk = nearestPeak(right, pk, 14)
                    if (lPk < 0 || rPk < 0) continue
                    // Cylindrical curl: centre rows shift vs edges.
                    val edgeAvg = (lPk + rPk) / 2f
                    val bow = pk - edgeAvg
                    totalBow += bow
                    if (abs(bow) > 1.5f) consistentVotes++
                }
                if (consistentVotes < max(2, peaks.size / 2)) {
                    return WarpEvidence(0f, false)
                }
                val avgBow = totalBow / peaks.size
                // Scale back to full resolution.
                val fullBow = avgBow * (h.toFloat() / sh)
                val threshold = h * 0.008f
                return if (abs(fullBow) > threshold && abs(fullBow) < h * 0.12f) {
                    WarpEvidence(fullBow, true)
                } else {
                    WarpEvidence(0f, false)
                }
            } finally {
                if (!small.isRecycled) small.recycle()
            }
        } catch (e: Exception) {
            return WarpEvidence(0f, false)
        }
    }

    /**
     * Returns a dewarped copy when curl evidence is reliable, otherwise the
     * input bitmap itself.
     */
    fun maybeDewarp(documentBitmap: Bitmap): Bitmap {
        val ev = measureCurl(documentBitmap)
        if (!ev.consistent || abs(ev.bowPx) < 2f) return documentBitmap
        return try {
            applyCylindricalUnwarp(documentBitmap, ev.bowPx)
        } catch (e: Exception) {
            documentBitmap
        }
    }

    /**
     * Inverse cylindrical model: rows are vertically shifted by a parabola
     * peaking at the centre column, magnitude = -bow.
     */
    private fun applyCylindricalUnwarp(src: Bitmap, bowPx: Float): Bitmap {
        val w = src.width
        val h = src.height
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)
        val out = IntArray(w * h) { Color.WHITE }
        val cx = w / 2f
        for (y in 0 until h) {
            for (x in 0 until w) {
                val t = (x - cx) / cx // -1..1
                val shift = -bowPx * (1f - t * t)
                val srcY = (y + shift).toInt()
                if (srcY in 0 until h) {
                    out[y * w + x] = pixels[srcY * w + x]
                }
            }
        }
        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        result.setPixels(out, 0, w, 0, 0, w, h)
        return result
    }

    private fun findPeaks(rows: FloatArray, minGap: Int): List<Int> {
        val peaks = mutableListOf<Int>()
        var last = -minGap * 2
        for (y in 1 until rows.size - 1) {
            if (rows[y] > rows[y - 1] && rows[y] >= rows[y + 1] && rows[y] > 0.02f) {
                if (y - last >= minGap) {
                    peaks.add(y)
                    last = y
                }
            }
        }
        return peaks
    }

    private fun nearestPeak(rows: FloatArray, near: Int, window: Int): Int {
        var best = -1
        var bestV = -1f
        for (y in max(0, near - window)..min(rows.size - 1, near + window)) {
            if (rows[y] > bestV) {
                bestV = rows[y]
                best = y
            }
        }
        return if (bestV > 0.015f) best else -1
    }
}
