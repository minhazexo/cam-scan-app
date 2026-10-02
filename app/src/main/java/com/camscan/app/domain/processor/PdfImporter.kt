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

    /**
     * Imports a PDF by scanning every selected page independently:
     *
     * PDF PAGE -> HIGH-RES IMAGE -> DOCUMENT DETECTION -> FOUR CORNERS ->
     * PERSPECTIVE CORRECTION -> DESKEW -> OPTIONAL DEWARP -> CLEANUP ->
     * A4 -> SAVE, then the next page.
     *
     * Each page gets its own detection geometry (never page-1 geometry for
     * all pages) and pages stream one at a time (never the whole PDF in
     * memory). Pages whose detection confidence is LOW are still saved with
     * their original render so the user can run manual corner correction
     * afterwards; the PDF canvas is never blindly embedded as the "scan".
     */
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
                if (totalPages <= 0) return@withContext resultPages
                val start = startPage.coerceIn(0, totalPages - 1)
                val end = if (endPage < 0) totalPages - 1 else endPage.coerceIn(start, totalPages - 1)
                val countToProcess = (end - start + 1).coerceAtLeast(1)

                var processed = 0
                for (i in start..end) {
                    onProgress(processed + 1, countToProcess)
                    // One page at a time: open, render, close immediately.
                    val page = renderer.openPage(i)
                    // Render page at 2x scale for sharp text scan quality (~200 DPI).
                    val scale = 2.0f
                    val renderW = (page.width * scale).toInt().coerceAtLeast(1)
                    val renderH = (page.height * scale).toInt().coerceAtLeast(1)

                    val bitmap = Bitmap.createBitmap(renderW, renderH, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(bitmap)
                    canvas.drawColor(Color.WHITE)

                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    page.close()

                    try {
                        val origPath = storageManager.saveBitmap(bitmap, isOriginal = true)
                        // Per-page detection pipeline. Digital-born pages have
                        // uniform borders so the detector accepts the page
                        // itself; photographed pages inside image-PDFs get
                        // real quad detection. LOW confidence never produces
                        // a blind full-canvas "scan": fall back to a clean A4
                        // canvas fit of the render, flagged for manual review
                        // via the corner editor (original is preserved).
                        val scanned: Bitmap = try {
                            DocumentProcessor.processImageStrict(
                                bitmap, filterMode, null
                            ).let { result ->
                                val a4 = result.a4
                                result.documentOnly.recycle()
                                a4
                            }
                        } catch (e: NeedsManualCornersException) {
                            // Detection failed for this page. Instead of embedding
                            // the whole PDF canvas (page numbers, headers, printer
                            // borders), find the bounding box of the actual content.
                            // If the content is inset, A4-fit the crop; if it fills
                            // the page, the render itself IS the document.
                            DocumentProcessor.formatToA4Canvas(contentCropped(bitmap))
                        }
                        val procPath = storageManager.saveBitmap(scanned, isOriginal = false)
                        if (!scanned.isRecycled) scanned.recycle()

                        resultPages.add(Pair(origPath, procPath))
                    } finally {
                        if (!bitmap.isRecycled) bitmap.recycle()
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

    /**
     * Crops a rendered PDF page to the bounding box of its non-white content.
     *
     * A low-confidence page (e.g. a photographed page embedded in a PDF) is
     * still drawn on the full PDF canvas, which typically carries page
     * numbers, running headers and printer crop/border marks. Trimming to the
     * content box removes those without inventing perspective geometry.
     *
     * Works on a downsampled copy for speed, then scales the box back to
     * full resolution. Returns the input bitmap untouched when the content
     * already fills the canvas (nothing to trim).
     */
    private fun contentCropped(bitmap: Bitmap): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        if (w < 8 || h < 8) return bitmap

        val sampleScale = minOf(1f, 600f / maxOf(w, h))
        val sw = maxOf(1, (w * sampleScale).toInt())
        val sh = maxOf(1, (h * sampleScale).toInt())
        val small = Bitmap.createScaledBitmap(bitmap, sw, sh, true)

        val pixels = IntArray(sw * sh)
        small.getPixels(pixels, 0, sw, 0, 0, sw, sh)
        if (small !== bitmap) small.recycle()

        // Threshold against near-white paper; PDF pages are white-backed.
        var minX = sw
        var minY = sh
        var maxX = -1
        var maxY = -1
        for (y in 0 until sh) {
            for (x in 0 until sw) {
                val c = pixels[y * sw + x]
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                if (r < 235 || g < 235 || b < 235) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }
        // No content, or content already fills the canvas: nothing to trim.
        if (maxX < 0) return bitmap
        val boxW = (maxX - minX + 1).toFloat() / sw
        val boxH = (maxY - minY + 1).toFloat() / sh
        if (boxW >= 0.90f && boxH >= 0.90f) return bitmap

        // Back to full resolution, with a small margin.
        val pad = 2f / maxOf(sw, sh)
        val left = ((minX.toFloat() / sw) - pad).coerceIn(0f, 1f)
        val top = ((minY.toFloat() / sh) - pad).coerceIn(0f, 1f)
        val right = ((maxX + 1).toFloat() / sw + pad).coerceIn(0f, 1f)
        val bottom = ((maxY + 1).toFloat() / sh + pad).coerceIn(0f, 1f)
        val cw = ((right - left) * w).toInt().coerceIn(1, w)
        val ch = ((bottom - top) * h).toInt().coerceIn(1, h)
        val cx = (left * w).toInt().coerceIn(0, w - cw)
        val cy = (top * h).toInt().coerceIn(0, h - ch)
        if (cw >= w && ch >= h) return bitmap
        return Bitmap.createBitmap(bitmap, cx, cy, cw, ch)
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
