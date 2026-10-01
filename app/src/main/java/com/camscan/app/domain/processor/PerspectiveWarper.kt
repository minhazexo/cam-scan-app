package com.camscan.app.domain.processor

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PointF
import com.camscan.app.domain.model.CornerPoints
import kotlin.math.hypot
import kotlin.math.max

object PerspectiveWarper {

    /**
     * Warps a quad region of an image into a clean top-down rectangular bitmap.
     * Expands quad by ~1% about its center to prevent clipping edges (matching auto_scan.py).
     */
    fun warpToRectangle(
        srcBitmap: Bitmap,
        corners: CornerPoints,
        rotationDegrees: Int = 0
    ): Bitmap {
        var bitmap = srcBitmap
        if (rotationDegrees % 360 != 0) {
            val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
            bitmap = Bitmap.createBitmap(
                srcBitmap, 0, 0, srcBitmap.width, srcBitmap.height, matrix, true
            )
        }

        val width = bitmap.width.toFloat()
        val height = bitmap.height.toFloat()

        val scaledCorners = if (corners.isNormalized()) {
            corners.scale(width, height)
        } else corners

        // Expand ~1% about center guard
        val centerX = (scaledCorners.topLeft.x + scaledCorners.topRight.x + scaledCorners.bottomRight.x + scaledCorners.bottomLeft.x) / 4f
        val centerY = (scaledCorners.topLeft.y + scaledCorners.topRight.y + scaledCorners.bottomRight.y + scaledCorners.bottomLeft.y) / 4f

        fun expandPoint(p: PointF): PointF {
            val ex = centerX + (p.x - centerX) * 1.01f
            val ey = centerY + (p.y - centerY) * 1.01f
            return PointF(ex.coerceIn(0f, width - 1f), ey.coerceIn(0f, height - 1f))
        }

        val tl = expandPoint(scaledCorners.topLeft)
        val tr = expandPoint(scaledCorners.topRight)
        val br = expandPoint(scaledCorners.bottomRight)
        val bl = expandPoint(scaledCorners.bottomLeft)

        // Calculate destination rectangle dimensions
        val widthA = hypot((br.x - bl.x).toDouble(), (br.y - bl.y).toDouble())
        val widthB = hypot((tr.x - tl.x).toDouble(), (tr.y - tl.y).toDouble())
        val targetWidth = max(1, max(widthA, widthB).toInt())

        val heightA = hypot((tr.x - br.x).toDouble(), (tr.y - br.y).toDouble())
        val heightB = hypot((tl.x - bl.x).toDouble(), (tl.y - bl.y).toDouble())
        val targetHeight = max(1, max(heightA, heightB).toInt())

        val srcPoints = floatArrayOf(
            tl.x, tl.y,
            tr.x, tr.y,
            br.x, br.y,
            bl.x, bl.y
        )

        val dstPoints = floatArrayOf(
            0f, 0f,
            targetWidth.toFloat(), 0f,
            targetWidth.toFloat(), targetHeight.toFloat(),
            0f, targetHeight.toFloat()
        )

        val matrix = Matrix()
        matrix.setPolyToPoly(srcPoints, 0, dstPoints, 0, 4)

        val resultBitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(resultBitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        canvas.drawBitmap(bitmap, matrix, paint)
        return resultBitmap
    }
}
