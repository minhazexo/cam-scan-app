package com.camscan.app.domain.processor

import android.graphics.Bitmap
import android.graphics.PointF
import com.camscan.app.domain.model.CornerPoints
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

class InvalidQuadException(message: String) : IllegalArgumentException(message)

object PerspectiveWarper {

    /**
     * Warps a quad region of an image into a clean top-down rectangular bitmap.
     *
     * This is the ONLY place where pixels move from the photograph into the
     * document space. The returned bitmap contains ONLY the selected
     * quadrilateral; every pixel outside it is excluded. Callers must drop
     * the original photograph after this call and use the result for all
     * downstream stages (deskew, dewarp, enhance, A4).
     *
     * Uses an explicit projective homography (Direct Linear Transform solved
     * by Gaussian elimination) with inverse-mapped bilinear resampling — the
     * equivalent of getPerspectiveTransform + warpPerspective. Implemented
     * directly so behaviour is identical on every device and in unit tests.
     *
     * @throws InvalidQuadException when the quad fails geometric validation
     * or the homography is singular.
     */
    fun warpToRectangle(
        srcBitmap: Bitmap,
        corners: CornerPoints,
        rotationDegrees: Int = 0
    ): Bitmap {
        var bitmap = srcBitmap
        var rotatedTemp: Bitmap? = null
        val rot = ((rotationDegrees % 360) + 360) % 360
        if (rot != 0) {
            rotatedTemp = rotateBitmapExact(srcBitmap, rot)
            bitmap = rotatedTemp
        }

        try {
            val width = bitmap.width.toFloat()
            val height = bitmap.height.toFloat()

            val scaledCorners = if (corners.isNormalized()) {
                corners.scale(width, height)
            } else corners

            val validation = QuadValidator.validate(scaledCorners, width, height)
            if (!validation.valid) {
                throw InvalidQuadException("Refusing to warp invalid quad: ${validation.reason}")
            }

            // Expand ~0.5% about center guard to avoid clipping printed borders.
            // Kept small: a larger guard pulls surrounding background into the
            // rectification whenever the detected quad overshoots.
            val centerX = (scaledCorners.topLeft.x + scaledCorners.topRight.x + scaledCorners.bottomRight.x + scaledCorners.bottomLeft.x) / 4f
            val centerY = (scaledCorners.topLeft.y + scaledCorners.topRight.y + scaledCorners.bottomRight.y + scaledCorners.bottomLeft.y) / 4f

            fun expandPoint(p: PointF): PointF {
                val ex = centerX + (p.x - centerX) * 1.005f
                val ey = centerY + (p.y - centerY) * 1.005f
                return PointF(ex.coerceIn(0f, width - 1f), ey.coerceIn(0f, height - 1f))
            }

            val tl = expandPoint(scaledCorners.topLeft)
            val tr = expandPoint(scaledCorners.topRight)
            val br = expandPoint(scaledCorners.bottomRight)
            val bl = expandPoint(scaledCorners.bottomLeft)

            // Destination size from real edge lengths (no stretching).
            val widthA = hypot((br.x - bl.x).toDouble(), (br.y - bl.y).toDouble())
            val widthB = hypot((tr.x - tl.x).toDouble(), (tr.y - tl.y).toDouble())
            var outW = max(2, max(widthA, widthB).toInt())

            val heightA = hypot((tr.x - br.x).toDouble(), (tr.y - br.y).toDouble())
            val heightB = hypot((tl.x - bl.x).toDouble(), (tl.y - bl.y).toDouble())
            var outH = max(2, max(heightA, heightB).toInt())

            // Cap output to bound memory/time on huge inputs (keeps aspect).
            val maxPixels = 3000L * 3000L
            if (outW.toLong() * outH > maxPixels) {
                val s = kotlin.math.sqrt(maxPixels.toDouble() / (outW.toLong() * outH))
                outW = max(2, (outW * s).toInt())
                outH = max(2, (outH * s).toInt())
            }

            val srcPts = doubleArrayOf(
                tl.x.toDouble(), tl.y.toDouble(),
                tr.x.toDouble(), tr.y.toDouble(),
                br.x.toDouble(), br.y.toDouble(),
                bl.x.toDouble(), bl.y.toDouble()
            )
            val dstPts = doubleArrayOf(
                0.0, 0.0,
                outW.toDouble(), 0.0,
                outW.toDouble(), outH.toDouble(),
                0.0, outH.toDouble()
            )
            // Capture path prefers OpenCV's native warpPerspective when the
            // native library is loaded; it returns null otherwise so the
            // deterministic DLT implementation below stays the fallback (and
            // the only path exercised by JVM/Robolectric tests).
            if (OpenCvVision.isAvailable()) {
                val cv = try {
                    OpenCvVision.warpQuad(bitmap, srcPts, outW, outH)
                } catch (t: Throwable) {
                    null
                }
                if (cv != null) return cv
            }

            // Forward homography src->dst; warp uses its inverse.
            val h = computeHomography(srcPts, dstPts)
                ?: throw InvalidQuadException("Homography estimation failed for given quad")
            val hInv = invert3x3(h)
                ?: throw InvalidQuadException("Homography is singular for given quad")

            return warpInverse(bitmap, hInv, outW, outH)
        } finally {
            if (rotatedTemp != null && rotatedTemp != srcBitmap && !rotatedTemp.isRecycled) {
                rotatedTemp.recycle()
            }
        }
    }

    // ------------------------------------------------------------------
    // Homography (DLT + Gaussian elimination)
    // ------------------------------------------------------------------

    /** Solves src->dst projective transform; null when singular. */
    fun computeHomography(src: DoubleArray, dst: DoubleArray): DoubleArray? {
        // 8 unknowns (h0..h7), h8 = 1.
        val a = Array(8) { DoubleArray(9) }
        for (i in 0 until 4) {
            val x = src[2 * i]
            val y = src[2 * i + 1]
            val u = dst[2 * i]
            val v = dst[2 * i + 1]
            a[2 * i][0] = -x
            a[2 * i][1] = -y
            a[2 * i][2] = -1.0
            a[2 * i][6] = u * x
            a[2 * i][7] = u * y
            a[2 * i][8] = u
            a[2 * i + 1][3] = -x
            a[2 * i + 1][4] = -y
            a[2 * i + 1][5] = -1.0
            a[2 * i + 1][6] = v * x
            a[2 * i + 1][7] = v * y
            a[2 * i + 1][8] = v
        }
        // Gaussian elimination with partial pivoting.
        for (col in 0 until 8) {
            var pivot = col
            var best = abs(a[col][col])
            for (row in col + 1 until 8) {
                val v = abs(a[row][col])
                if (v > best) {
                    best = v
                    pivot = row
                }
            }
            if (best < 1e-12) return null
            if (pivot != col) {
                val tmp = a[col]
                a[col] = a[pivot]
                a[pivot] = tmp
            }
            val div = a[col][col]
            for (k in col until 9) a[col][k] /= div
            for (row in 0 until 8) {
                if (row == col) continue
                val factor = a[row][col]
                if (factor != 0.0) {
                    for (k in col until 9) a[row][k] -= factor * a[col][k]
                }
            }
        }
        val h = DoubleArray(9)
        for (i in 0 until 8) h[i] = a[i][8]
        h[8] = 1.0
        return h
    }

    fun invert3x3(m: DoubleArray): DoubleArray? {
        val a = m[0]
        val b = m[1]
        val c = m[2]
        val d = m[3]
        val e = m[4]
        val f = m[5]
        val g = m[6]
        val h = m[7]
        val i = m[8]
        val det = a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g)
        if (abs(det) < 1e-14) return null
        val inv = DoubleArray(9)
        inv[0] = (e * i - f * h) / det
        inv[1] = (c * h - b * i) / det
        inv[2] = (b * f - c * e) / det
        inv[3] = (f * g - d * i) / det
        inv[4] = (a * i - c * g) / det
        inv[5] = (c * d - a * f) / det
        inv[6] = (d * h - e * g) / det
        inv[7] = (b * g - a * h) / det
        inv[8] = (a * e - b * d) / det
        return inv
    }

    // ------------------------------------------------------------------
    // Inverse-mapped bilinear warp
    // ------------------------------------------------------------------

    private fun warpInverse(src: Bitmap, hInv: DoubleArray, outW: Int, outH: Int): Bitmap {
        val sw = src.width
        val sh = src.height
        val srcPixels = IntArray(sw * sh)
        src.getPixels(srcPixels, 0, sw, 0, 0, sw, sh)
        val out = IntArray(outW * outH)

        val h0 = hInv[0]
        val h1 = hInv[1]
        val h2 = hInv[2]
        val h3 = hInv[3]
        val h4 = hInv[4]
        val h5 = hInv[5]
        val h6 = hInv[6]
        val h7 = hInv[7]
        val h8 = hInv[8]

        var idx = 0
        for (y in 0 until outH) {
            for (x in 0 until outW) {
                val w = h6 * x + h7 * y + h8
                if (abs(w) < 1e-12) {
                    out[idx++] = -1 // white
                    continue
                }
                val sx = (h0 * x + h1 * y + h2) / w
                val sy = (h3 * x + h4 * y + h5) / w
                out[idx++] = if (sx < 0 || sy < 0 || sx > sw - 1 || sy > sh - 1) {
                    -1 // white outside the quad
                } else {
                    bilinear(srcPixels, sw, sh, sx, sy)
                }
            }
        }
        val result = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        result.setPixels(out, 0, outW, 0, 0, outW, outH)
        return result
    }

    private fun bilinear(pixels: IntArray, w: Int, h: Int, x: Double, y: Double): Int {
        val x0 = x.toInt().coerceIn(0, w - 2)
        val y0 = y.toInt().coerceIn(0, h - 2)
        val fx = (x - x0).coerceIn(0.0, 1.0).toFloat()
        val fy = (y - y0).coerceIn(0.0, 1.0).toFloat()
        val c00 = pixels[y0 * w + x0]
        val c10 = pixels[y0 * w + x0 + 1]
        val c01 = pixels[(y0 + 1) * w + x0]
        val c11 = pixels[(y0 + 1) * w + x0 + 1]

        fun ch(c: Int, shift: Int): Float = ((c shr shift) and 0xFF).toFloat()
        val a = (ch(c00, 24) * (1 - fx) + ch(c10, 24) * fx) * (1 - fy) +
            (ch(c01, 24) * (1 - fx) + ch(c11, 24) * fx) * fy
        val r = (ch(c00, 16) * (1 - fx) + ch(c10, 16) * fx) * (1 - fy) +
            (ch(c01, 16) * (1 - fx) + ch(c11, 16) * fx) * fy
        val g = (ch(c00, 8) * (1 - fx) + ch(c10, 8) * fx) * (1 - fy) +
            (ch(c01, 8) * (1 - fx) + ch(c11, 8) * fx) * fy
        val b = (ch(c00, 0) * (1 - fx) + ch(c10, 0) * fx) * (1 - fy) +
            (ch(c01, 0) * (1 - fx) + ch(c11, 0) * fx) * fy
        return (a.toInt().coerceIn(0, 255) shl 24) or
            (r.toInt().coerceIn(0, 255) shl 16) or
            (g.toInt().coerceIn(0, 255) shl 8) or
            b.toInt().coerceIn(0, 255)
    }

    /** Exact transpose rotation for multiples of 90°. */
    private fun rotateBitmapExact(src: Bitmap, degrees: Int): Bitmap {
        val w = src.width
        val h = src.height
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)
        return when (degrees) {
            90 -> {
                val out = Bitmap.createBitmap(h, w, Bitmap.Config.ARGB_8888)
                val o = IntArray(w * h)
                for (y in 0 until h) {
                    for (x in 0 until w) {
                        o[x * h + (h - 1 - y)] = pixels[y * w + x]
                    }
                }
                out.setPixels(o, 0, h, 0, 0, h, w)
                out
            }
            180 -> {
                val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                val o = IntArray(w * h)
                for (i in pixels.indices) o[i] = pixels[pixels.size - 1 - i]
                out.setPixels(o, 0, w, 0, 0, w, h)
                out
            }
            270 -> {
                val out = Bitmap.createBitmap(h, w, Bitmap.Config.ARGB_8888)
                val o = IntArray(w * h)
                for (y in 0 until h) {
                    for (x in 0 until w) {
                        o[(w - 1 - x) * h + y] = pixels[y * w + x]
                    }
                }
                out.setPixels(o, 0, h, 0, 0, h, w)
                out
            }
            else -> src
        }
    }
}
