package com.camscan.app.domain.processor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.pdf.PdfDocument
import com.camscan.app.data.storage.StorageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

object PdfGenerator {

    // A4 dimensions in points (1/72 inch): 595 x 842
    private const val A4_WIDTH = 595
    private const val A4_HEIGHT = 842

    suspend fun generatePdf(
        context: Context,
        pageImagePaths: List<String>,
        outputFile: File,
        includePageNumbers: Boolean = false
    ): File = withContext(Dispatchers.IO) {
        val pdfDocument = PdfDocument()

        pageImagePaths.forEachIndexed { index, imagePath ->
            val bitmap = BitmapFactory.decodeFile(imagePath)
            if (bitmap != null) {
                val pageInfo = PdfDocument.PageInfo.Builder(A4_WIDTH, A4_HEIGHT, index + 1).create()
                val page = pdfDocument.startPage(pageInfo)
                val canvas = page.canvas

                // Fit bitmap into A4 canvas while preserving aspect ratio
                val srcRect = Rect(0, 0, bitmap.width, bitmap.height)
                val scale = minOf(
                    A4_WIDTH.toFloat() / bitmap.width,
                    A4_HEIGHT.toFloat() / bitmap.height
                )

                val scaledWidth = (bitmap.width * scale).toInt()
                val scaledHeight = (bitmap.height * scale).toInt()

                val left = (A4_WIDTH - scaledWidth) / 2
                val top = (A4_HEIGHT - scaledHeight) / 2
                val dstRect = Rect(left, top, left + scaledWidth, top + scaledHeight)

                canvas.drawBitmap(bitmap, srcRect, dstRect, null)

                if (includePageNumbers) {
                    val paint = android.graphics.Paint().apply {
                        color = android.graphics.Color.GRAY
                        textSize = 12f
                        textAlign = android.graphics.Paint.Align.CENTER
                    }
                    canvas.drawText("${index + 1} / ${pageImagePaths.size}", A4_WIDTH / 2f, A4_HEIGHT - 15f, paint)
                }

                pdfDocument.finishPage(page)
                bitmap.recycle()
            }
        }

        FileOutputStream(outputFile).use { out ->
            pdfDocument.writeTo(out)
        }
        pdfDocument.close()

        outputFile
    }
}
