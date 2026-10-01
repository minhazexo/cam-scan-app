package com.camscan.app.domain.processor

import android.graphics.Bitmap
import android.graphics.PointF
import com.camscan.app.domain.model.CornerPoints
import kotlin.math.abs
import kotlin.math.hypot

object DocumentDetector {

    /**
     * Detect document quad boundaries in the given bitmap.
     * Returns normalized (0..1) CornerPoints.
     */
    fun detectCorners(bitmap: Bitmap): CornerPoints {
        val width = bitmap.width
        val height = bitmap.height

        // Downsample for fast detection
        val targetWidth = 480
        val scale = if (width > targetWidth) targetWidth.toFloat() / width else 1.0f
        val sampleW = (width * scale).toInt().coerceAtLeast(100)
        val sampleH = (height * scale).toInt().coerceAtLeast(100)

        val scaledBitmap = if (scale < 1.0f) {
            Bitmap.createScaledBitmap(bitmap, sampleW, sampleH, true)
        } else {
            bitmap
        }

        val pixels = IntArray(sampleW * sampleH)
        scaledBitmap.getPixels(pixels, 0, sampleW, 0, 0, sampleW, sampleH)

        // Grayscale + Sobel gradient energy map for rectangular boundary finding
        val gray = IntArray(sampleW * sampleH)
        for (i in pixels.indices) {
            val c = pixels[i]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            gray[i] = (0.299 * r + 0.587 * g + 0.114 * b).toInt()
        }

        // Apply 3x3 Gaussian smoothing
        val blurred = IntArray(sampleW * sampleH)
        for (y in 1 until sampleH - 1) {
            for (x in 1 until sampleW - 1) {
                val sum = gray[(y - 1) * sampleW + (x - 1)] + 2 * gray[(y - 1) * sampleW + x] + gray[(y - 1) * sampleW + (x + 1)] +
                        2 * gray[y * sampleW + (x - 1)] + 4 * gray[y * sampleW + x] + 2 * gray[y * sampleW + (x + 1)] +
                        gray[(y + 1) * sampleW + (x - 1)] + 2 * gray[(y + 1) * sampleW + x] + gray[(y + 1) * sampleW + (x + 1)]
                blurred[y * sampleW + x] = sum / 16
            }
        }

        // Edge energy projection to locate dominant page boundaries
        var minX = (sampleW * 0.08f).toInt()
        var maxX = (sampleW * 0.92f).toInt()
        var minY = (sampleH * 0.08f).toInt()
        var maxY = (sampleH * 0.92f).toInt()

        val columnEnergy = FloatArray(sampleW)
        for (x in 1 until sampleW - 1) {
            var energy = 0f
            for (y in 1 until sampleH - 1) {
                val gx = abs(blurred[y * sampleW + (x + 1)] - blurred[y * sampleW + (x - 1)])
                energy += gx
            }
            columnEnergy[x] = energy
        }

        val rowEnergy = FloatArray(sampleH)
        for (y in 1 until sampleH - 1) {
            var energy = 0f
            for (x in 1 until sampleW - 1) {
                val gy = abs(blurred[(y + 1) * sampleW + x] - blurred[(y - 1) * sampleW + x])
                energy += gy
            }
            rowEnergy[y] = energy
        }

        // Find outer boundary jumps above mean threshold
        val colMean = columnEnergy.average().toFloat()
        val rowMean = rowEnergy.average().toFloat()

        for (x in (sampleW * 0.05f).toInt() until (sampleW * 0.4f).toInt()) {
            if (columnEnergy[x] > colMean * 1.25f) {
                minX = x
                break
            }
        }
        for (x in (sampleW * 0.95f).toInt() downTo (sampleW * 0.6f).toInt()) {
            if (columnEnergy[x] > colMean * 1.25f) {
                maxX = x
                break
            }
        }
        for (y in (sampleH * 0.05f).toInt() until (sampleH * 0.4f).toInt()) {
            if (rowEnergy[y] > rowMean * 1.25f) {
                minY = y
                break
            }
        }
        for (y in (sampleH * 0.95f).toInt() downTo (sampleH * 0.6f).toInt()) {
            if (rowEnergy[y] > rowMean * 1.25f) {
                maxY = y
                break
            }
        }

        if (scaledBitmap != bitmap) {
            scaledBitmap.recycle()
        }

        val normMinX = (minX.toFloat() / sampleW).coerceIn(0.02f, 0.4f)
        val normMaxX = (maxX.toFloat() / sampleW).coerceIn(0.6f, 0.98f)
        val normMinY = (minY.toFloat() / sampleH).coerceIn(0.02f, 0.4f)
        val normMaxY = (maxY.toFloat() / sampleH).coerceIn(0.6f, 0.98f)

        return CornerPoints(
            topLeft = PointF(normMinX, normMinY),
            topRight = PointF(normMaxX, normMinY),
            bottomRight = PointF(normMaxX, normMaxY),
            bottomLeft = PointF(normMinX, normMaxY)
        )
    }

    /**
     * Orders 4 points in (Top-Left, Top-Right, Bottom-Right, Bottom-Left) sequence.
     */
    fun orderPoints(pts: List<PointF>): CornerPoints {
        if (pts.size != 4) return CornerPoints.defaultNormalized()
        val sumList = pts.map { it.x + it.y }
        val diffList = pts.map { it.x - it.y }

        val topLeft = pts[sumList.indexOfMin()]
        val bottomRight = pts[sumList.indexOfMax()]
        val topRight = pts[diffList.indexOfMax()]
        val bottomLeft = pts[diffList.indexOfMin()]

        return CornerPoints(topLeft, topRight, bottomRight, bottomLeft)
    }

    private fun List<Float>.indexOfMin(): Int {
        var minIdx = 0
        var minVal = this[0]
        for (i in 1 until size) {
            if (this[i] < minVal) {
                minVal = this[i]
                minIdx = i
            }
        }
        return minIdx
    }

    private fun List<Float>.indexOfMax(): Int {
        var maxIdx = 0
        var maxVal = this[0]
        for (i in 1 until size) {
            if (this[i] > maxVal) {
                maxVal = this[i]
                maxIdx = i
            }
        }
        return maxIdx
    }
}
