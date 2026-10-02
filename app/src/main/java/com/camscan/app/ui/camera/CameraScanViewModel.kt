package com.camscan.app.ui.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.net.Uri
import androidx.camera.core.ImageProxy
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.camscan.app.data.repository.DocumentRepository
import com.camscan.app.domain.model.CornerPoints
import com.camscan.app.domain.model.DetectionOverlayState
import com.camscan.app.domain.model.LiveDetection
import com.camscan.app.domain.processor.DocumentDetector
import com.camscan.app.domain.processor.DocumentProcessor
import com.camscan.app.domain.processor.QuadValidator
import com.camscan.app.domain.model.FilterMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CameraScanViewModel(private val repository: DocumentRepository) : ViewModel() {

    /**
     * Live overlay state. NOT_DETECTED carries no corners, so the UI never
     * draws a fake document rectangle (Phase 10).
     */
    val liveDetection = MutableStateFlow(LiveDetection.NONE)
    val isBatchMode = MutableStateFlow(false)
    val capturedPages = MutableStateFlow<List<Pair<String, String>>>(emptyList())
    val isProcessing = MutableStateFlow(false)

    /** Throttle live analysis to ~8 fps and smooth corners across frames. */
    @Volatile
    private var lastAnalysisMs = 0L
    private var smoothedCorners: CornerPoints? = null

    fun onFrameAnalyzed(imageProxy: ImageProxy) {
        val now = System.currentTimeMillis()
        if (now - lastAnalysisMs < ANALYSIS_INTERVAL_MS) {
            // Skip this frame to keep the preview smooth; still release it.
            imageProxy.close()
            return
        }
        lastAnalysisMs = now
        viewModelScope.launch(Dispatchers.Default) {
            var bitmap: Bitmap? = null
            var rotated: Bitmap? = null
            try {
                bitmap = imageProxy.toBitmap()
                val matrix = Matrix().apply { postRotate(imageProxy.imageInfo.rotationDegrees.toFloat()) }
                rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                val raw = DocumentDetector.detectLive(rotated)
                liveDetection.value = stabilize(raw)
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                if (rotated != null && rotated != bitmap && !rotated.isRecycled) rotated.recycle()
                if (bitmap != null && !bitmap.isRecycled) bitmap.recycle()
                try {
                    imageProxy.close()
                } catch (e: Exception) {
                    // already closed
                }
            }
        }
    }

    /**
     * Temporal stability (Phase 12): do not jump between candidates every
     * frame. When the same document remains detected and corners move only a
     * little, blend with an exponential moving average. A large jump resets
     * the history so a new document snaps into place immediately.
     */
    private fun stabilize(next: LiveDetection): LiveDetection {
        val nextCorners = next.corners
        if (nextCorners == null || next.state == DetectionOverlayState.NOT_DETECTED) {
            smoothedCorners = null
            return next
        }
        val previous = smoothedCorners
        if (previous != null) {
            val moved = QuadValidator.maxCornerDistance(previous.toList(), nextCorners.toList(), 1f, 1f)
            if (moved < MAX_TRACK_JUMP) {
                val a = SMOOTHING_ALPHA
                fun lerp(p: android.graphics.PointF, q: android.graphics.PointF) =
                    android.graphics.PointF(p.x + (q.x - p.x) * a, p.y + (q.y - p.y) * a)
                val blended = CornerPoints(
                    lerp(previous.topLeft, nextCorners.topLeft),
                    lerp(previous.topRight, nextCorners.topRight),
                    lerp(previous.bottomRight, nextCorners.bottomRight),
                    lerp(previous.bottomLeft, nextCorners.bottomLeft)
                )
                smoothedCorners = blended
                return next.copy(corners = blended)
            }
        }
        smoothedCorners = nextCorners
        return next
    }

    fun toggleBatchMode() {
        isBatchMode.value = !isBatchMode.value
    }

    /**
     * Captures a photo and saves ONLY the original. The actual scan is built
     * in the corner-adjust screen (automatic HIGH quad shown for one-tap
     * confirm, MEDIUM shown for confirmation, LOW forcing manual corners).
     * This guarantees the full photograph can never become the "scan".
     *
     * Single mode: no document/page rows are created here; the corner editor
     * creates them on CONFIRM. Batch mode: each capture is scanned inline —
     * HIGH/MEDIUM quads produce real A4 pages, LOW captures are kept as
     * originals pending manual corner correction (never a fake scan).
     */
    fun processCapturedPhoto(
        context: Context,
        imageProxy: ImageProxy,
        documentId: String?,
        onComplete: (String?, String, Boolean) -> Unit // documentId, savedOriginalPath, needsManual
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            isProcessing.value = true
            try {
                val app = context.applicationContext as com.camscan.app.CamScanApplication
                val storageManager = app.storageManager

                val rawBitmap = imageProxy.toBitmap()
                val matrix = Matrix().apply { postRotate(imageProxy.imageInfo.rotationDegrees.toFloat()) }
                val rotatedBitmap = Bitmap.createBitmap(rawBitmap, 0, 0, rawBitmap.width, rawBitmap.height, matrix, true)

                val origPath = storageManager.saveBitmap(rotatedBitmap, isOriginal = true)

                if (isBatchMode.value) {
                    // Unattended batch: ONLY a HIGH-confidence detection may
                    // produce a saved scan. MEDIUM and LOW both keep the
                    // original, pending confirmation / manual corners.
                    var procPath: String? = null
                    var needsManual: Boolean
                    val scan = withContext(Dispatchers.Default) {
                        DocumentProcessor.processImageAutoOrNull(rotatedBitmap, FilterMode.AUTO)
                    }
                    if (scan != null) {
                        scan.documentOnly.recycle()
                        procPath = storageManager.saveBitmap(scan.a4, isOriginal = false)
                        if (!scan.a4.isRecycled) scan.a4.recycle()
                        needsManual = false
                    } else {
                        // MEDIUM -> confirm corners, LOW -> manual corners.
                        // Both stay pending: the user opens the corner editor.
                        needsManual = try {
                            withContext(Dispatchers.Default) {
                                DocumentDetector.detectDocument(rotatedBitmap, fast = false).action.requiresUser
                            }
                        } catch (e: Exception) {
                            true
                        }
                    }
                    val finalProc = procPath ?: origPath // pending: correct via corner editor
                    capturedPages.value = capturedPages.value + Pair(origPath, finalProc)
                    isProcessing.value = false
                    withContext(Dispatchers.Main) {
                        onComplete(documentId, origPath, needsManual)
                    }
                } else {
                    // Single: defer everything to the corner editor.
                    val needsManual = try {
                        withContext(Dispatchers.Default) {
                            DocumentDetector.detectDocument(rotatedBitmap, fast = false).needsManual
                        }
                    } catch (e: Exception) {
                        true
                    }
                    if (!rotatedBitmap.isRecycled) rotatedBitmap.recycle()
                    if (!rawBitmap.isRecycled && rawBitmap != rotatedBitmap) rawBitmap.recycle()
                    withContext(Dispatchers.Main) {
                        isProcessing.value = false
                        onComplete(documentId, origPath, needsManual)
                    }
                    return@launch
                }
                if (!rotatedBitmap.isRecycled) rotatedBitmap.recycle()
                if (!rawBitmap.isRecycled && rawBitmap != rotatedBitmap) rawBitmap.recycle()
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    isProcessing.value = false
                }
            } finally {
                try {
                    imageProxy.close()
                } catch (e: Exception) {
                    // already closed
                }
            }
        }
    }

    fun finishBatchScan(
        documentId: String?,
        onComplete: (String) -> Unit
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val pages = capturedPages.value
            if (pages.isEmpty()) return@launch

            if (documentId.isNullOrBlank()) {
                val doc = repository.createDocument(
                    title = "Scan_${System.currentTimeMillis() / 1000}",
                    pages = pages
                )
                // Pages that fell back to the raw capture (orig == proc) are
                // not scans; flag them so the user fixes corners before export.
                repository.markPagesPending(
                    doc.id,
                    pages.filter { it.first == it.second }.map { it.first }
                )
                withContext(Dispatchers.Main) {
                    onComplete(doc.id)
                }
            } else {
                pages.forEach { pair ->
                    repository.addPageToDocument(documentId, pair.first, pair.second)
                }
                withContext(Dispatchers.Main) {
                    onComplete(documentId)
                }
            }
        }
    }

    /**
     * Gallery import through the strict pipeline. Pages with LOW detection
     * are kept as originals pending manual corner correction instead of
     * being fabricated into full-photo "scans".
     */
    fun importImagesFromGallery(
        context: Context,
        uris: List<Uri>,
        documentId: String?,
        onSuccess: (String) -> Unit
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            if (uris.isEmpty()) return@launch
            val app = context.applicationContext as com.camscan.app.CamScanApplication
            val storageManager = app.storageManager

            val pagePairs = mutableListOf<Pair<String, String>>()
            uris.forEach { uri ->
                val origPath = storageManager.saveUriToOriginalFile(uri)
                if (origPath != null) {
                    val bitmap = storageManager.loadBitmap(origPath)
                    if (bitmap != null) {
                        // HIGH -> save the processed A4 page.
                        // MEDIUM / LOW -> save the original only, pending the
                        // corner editor. An uncertain quad is never a final scan.
                        val scan = DocumentProcessor.processImageAutoOrNull(bitmap, FilterMode.AUTO)
                        if (scan != null) {
                            scan.documentOnly.recycle()
                            val procPath = storageManager.saveBitmap(scan.a4, isOriginal = false)
                            if (!scan.a4.isRecycled) scan.a4.recycle()
                            pagePairs.add(Pair(origPath, procPath))
                        } else {
                            pagePairs.add(Pair(origPath, origPath))
                        }
                        if (!bitmap.isRecycled) bitmap.recycle()
                    }
                }
            }

            if (pagePairs.isNotEmpty()) {
                val docId = if (documentId.isNullOrBlank()) {
                    val doc = repository.createDocument(
                        title = "Imported_${System.currentTimeMillis() / 1000}",
                        pages = pagePairs
                    )
                    doc.id
                } else {
                    pagePairs.forEach { pair ->
                        repository.addPageToDocument(documentId, pair.first, pair.second)
                    }
                    documentId
                }
                withContext(Dispatchers.Main) {
                    onSuccess(docId)
                }
            }
        }
    }

    fun importPdfFromGallery(
        context: Context,
        pdfUri: Uri,
        documentId: String?,
        onSuccess: (String) -> Unit
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val app = context.applicationContext as com.camscan.app.CamScanApplication
            val pagePairs = com.camscan.app.domain.processor.PdfImporter.importPdf(
                context = context,
                pdfUri = pdfUri,
                storageManager = app.storageManager
            )

            if (pagePairs.isNotEmpty()) {
                val docId = if (documentId.isNullOrBlank()) {
                    val rawName = com.camscan.app.domain.processor.PdfImporter.getFileName(context, pdfUri)
                        ?: "Imported_PDF_${System.currentTimeMillis() / 1000}"
                    val titleName = rawName.removeSuffix(".pdf").removeSuffix(".PDF")
                    val doc = repository.createDocument(
                        title = titleName,
                        pages = pagePairs
                    )
                    doc.id
                } else {
                    pagePairs.forEach { pair ->
                        repository.addPageToDocument(documentId, pair.first, pair.second)
                    }
                    documentId
                }
                withContext(Dispatchers.Main) {
                    onSuccess(docId)
                }
            }
        }
    }

    companion object {
        /** ~8 live analyses per second. */
        private const val ANALYSIS_INTERVAL_MS = 120L
        private const val SMOOTHING_ALPHA = 0.4f
        /** Max normalized corner movement still considered the same document. */
        private const val MAX_TRACK_JUMP = 0.12f
    }

    class Factory(private val repository: DocumentRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return CameraScanViewModel(repository) as T
        }
    }
}
