package com.camscan.app.domain.processor

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import com.camscan.app.domain.model.CornerPoints
import com.camscan.app.domain.model.FilterMode

object DocumentProcessor {

    // Standard A4 dimensions at high print resolution (300 DPI equivalent)
    private const val A4_WIDTH_PX = 2480
    private const val A4_HEIGHT_PX = 3508

    /**
     * Complete professional document processing pipeline:
     * 1. Detect corners (if not provided)
     * 2. Perspective warp (homography correction)
     * 3. Document enhancement
     * 4. A4 canvas formatting preserving natural aspect ratio with intelligent margins
     */
    fun processImage(
        bitmap: Bitmap,
        filterMode: FilterMode = FilterMode.AUTO,
        corners: CornerPoints? = null
    ): Bitmap {
        val detectedCorners = corners ?: DocumentDetector.detectCorners(bitmap)
        val warped = PerspectiveWarper.warpToRectangle(bitmap, detectedCorners)
        val enhanced = DocumentEnhancer.enhance(warped, filterMode)
        val a4Formatted = formatToA4Canvas(enhanced)

        if (warped != bitmap && warped != enhanced && warped != a4Formatted) {
            warped.recycle()
        }
        if (enhanced != bitmap && enhanced != a4Formatted) {
            enhanced.recycle()
        }

        return a4Formatted
    }

    /**
     * Places the processed document content onto a standardized A4 canvas
     * preserving natural aspect ratio with intelligent padding/margins.
     */
    fun formatToA4Canvas(sourceBitmap: Bitmap): Bitmap {
        val srcW = sourceBitmap.width
        val srcH = sourceBitmap.height

        val a4Bitmap = Bitmap.createBitmap(A4_WIDTH_PX, A4_HEIGHT_PX, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(a4Bitmap)
        canvas.drawColor(Color.WHITE)

        // Calculate scaling to fit within A4 canvas with 5% margin
        val maxW = A4_WIDTH_PX * 0.92f
        val maxH = A4_HEIGHT_PX * 0.92f

        val scale = minOf(
            maxW / srcW.toFloat(),
            maxH / srcH.toFloat()
        )

        val scaledW = (srcW * scale).toInt().coerceAtLeast(1)
        val scaledH = (srcH * scale).toInt().coerceAtLeast(1)

        val left = (A4_WIDTH_PX - scaledW) / 2
        val top = (A4_HEIGHT_PX - scaledH) / 2
        val dstRect = Rect(left, top, left + scaledW, top + scaledH)
        val srcRect = Rect(0, 0, srcW, srcH)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        canvas.drawBitmap(sourceBitmap, srcRect, dstRect, paint)

        return a4Bitmap
    }
}
