package com.camscan.app.domain.processor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import com.camscan.app.data.storage.StorageManager
import com.camscan.app.domain.model.FilterMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object PdfImporter {

    fun getPdfPageCount(context: Context, pdfUri: Uri): Int {
        var pfd: ParcelFileDescriptor? = null
        var renderer: PdfRenderer? = null
        return try {
            pfd = context.contentResolver.openFileDescriptor(pdfUri, "r")
            if (pfd != null) {
                renderer = PdfRenderer(pfd)
                renderer.pageCount
            } else 0
        } catch (e: Exception) {
            e.printStackTrace()
            0
        } finally {
            try {
                renderer?.close()
                pfd?.close()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    suspend fun importPdf(
        context: Context,
        pdfUri: Uri,
        storageManager: StorageManager,
        startPage: Int = 0,
        endPage: Int = -1,
        filterMode: FilterMode = FilterMode.AUTO,
        onProgress: suspend (current: Int, total: Int) -> Unit = { _, _ -> }
    ): List<Pair<String, String>> = withContext(Dispatchers.IO) {
        val resultPages = mutableListOf<Pair<String, String>>()
        var pfd: ParcelFileDescriptor? = null
        var renderer: PdfRenderer? = null

        try {
            pfd = context.contentResolver.openFileDescriptor(pdfUri, "r")
            if (pfd != null) {
                renderer = PdfRenderer(pfd)
                val totalPages = renderer.pageCount
                val start = startPage.coerceIn(0, totalPages - 1)
                val end = if (endPage < 0) totalPages - 1 else endPage.coerceIn(start, totalPages - 1)
                val countToProcess = (end - start + 1).coerceAtLeast(1)

                var processed = 0
                for (i in start..end) {
                    onProgress(processed + 1, countToProcess)
                    val page = renderer.openPage(i)
                    // Render page at 2x scale for sharp text scan quality (~200 DPI)
                    val scale = 2.0f
                    val renderW = (page.width * scale).toInt().coerceAtLeast(1)
                    val renderH = (page.height * scale).toInt().coerceAtLeast(1)

                    val bitmap = Bitmap.createBitmap(renderW, renderH, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(bitmap)
                    canvas.drawColor(Color.WHITE)

                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    page.close()

                    val origPath = storageManager.saveBitmap(bitmap, isOriginal = true)
                    val processedBitmap = if (filterMode == FilterMode.ORIGINAL) {
                        DocumentProcessor.formatToA4Canvas(bitmap)
                    } else {
                        DocumentProcessor.processImage(bitmap, filterMode)
                    }
                    val procPath = storageManager.saveBitmap(processedBitmap, isOriginal = false)

                    resultPages.add(Pair(origPath, procPath))
                    bitmap.recycle()
                    if (processedBitmap != bitmap) {
                        processedBitmap.recycle()
                    }
                    processed++
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            try {
                renderer?.close()
                pfd?.close()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        resultPages
    }

    fun getFileName(context: Context, uri: Uri): String? {
        var result: String? = null
        if (uri.scheme == "content") {
            val cursor = context.contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val nameIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex != -1) {
                        result = it.getString(nameIndex)
                    }
                }
            }
        }
        if (result == null) {
            result = uri.path
            val cut = result?.lastIndexOf('/') ?: -1
            if (cut != -1) {
                result = result?.substring(cut + 1)
            }
        }
        return result
    }
}
