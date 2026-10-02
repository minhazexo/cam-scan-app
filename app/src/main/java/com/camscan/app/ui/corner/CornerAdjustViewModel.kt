package com.camscan.app.ui.corner

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.PointF
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.camscan.app.CamScanApplication
import com.camscan.app.data.repository.DocumentRepository
import com.camscan.app.domain.model.CornerPoints
import com.camscan.app.domain.model.DetectionConfidence
import com.camscan.app.domain.model.DetectionResult
import com.camscan.app.domain.processor.DocumentDetector
import com.camscan.app.domain.processor.DocumentProcessor
import com.camscan.app.domain.processor.NeedsManualCornersException
import com.camscan.app.domain.model.FilterMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Manual 4-corner editor ViewModel.
 *
 * This screen is one of the two ONLY valid document sources (the other is a
 * high-confidence automatic quad). It therefore treats the user-selected
 * quadrilateral as authoritative and funnels it through the strict pipeline
 * ([DocumentProcessor.processImageStrict] with explicit corners), which
 * extracts document-only pixels before any enhancement or A4 composition.
 */
class CornerAdjustViewModel(private val repository: DocumentRepository) : ViewModel() {

    val corners = MutableStateFlow(CornerPoints.defaultNormalized())
    val originalBitmap = MutableStateFlow<Bitmap?>(null)
    val activeDragPoint = MutableStateFlow<PointF?>(null)
    val isProcessing = MutableStateFlow(false)

    /** Latest automatic detection (null while loading / before first run). */
    val detection = MutableStateFlow<DetectionResult?>(null)
    val isDetecting = MutableStateFlow(false)
    val errorMessage = MutableStateFlow<String?>(null)

    /** Debug stage previews for the development overlay (Step 19). */
    val debugInfo = MutableStateFlow<DocumentProcessor.ScanDebugInfo?>(null)
    val showDebug = MutableStateFlow(false)

    fun loadImage(context: Context, imagePath: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val app = context.applicationContext as com.camscan.app.CamScanApplication
            val bitmap = app.storageManager.loadBitmap(imagePath)
            if (bitmap != null) {
                originalBitmap.value = bitmap
                runAutoDetection(bitmap)
            }
        }
    }

    /** AUTO button: re-run full detection on the current image. */
    fun autoDetect() {
        val bitmap = originalBitmap.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            runAutoDetection(bitmap)
        }
    }

    private suspend fun runAutoDetection(bitmap: Bitmap) {
        isDetecting.value = true
        errorMessage.value = null
        try {
            val result = withContext(Dispatchers.Default) {
                DocumentDetector.detectDocument(bitmap, fast = false)
            }
            detection.value = result
            if (result.corners != null) {
                // HIGH or MEDIUM: show detected quad (MEDIUM asks confirmation).
                corners.value = result.corners
            } else {
                // LOW: no trustworthy quad — present a clearly-inset manual
                // starting point. NEVER the full photograph.
                corners.value = CornerPoints(
                    PointF(0.15f, 0.15f), PointF(0.85f, 0.15f),
                    PointF(0.85f, 0.85f), PointF(0.15f, 0.85f)
                )
                errorMessage.value = result.reason.ifBlank {
                    "Automatic detection failed. Drag the 4 corners onto the page."
                }
            }
            // Refresh debug previews alongside.
            if (showDebug.value) buildDebug()
        } catch (e: Exception) {
            detection.value = DetectionResult(
                null, DetectionConfidence.LOW, 0f, emptyList(),
                "Detection error: ${e.message}"
            )
            errorMessage.value = "Detection error. Drag the 4 corners onto the page."
        } finally {
            isDetecting.value = false
        }
    }

    fun updateCorner(cornerIndex: Int, normalizedPoint: PointF) {
        val current = corners.value
        val clampedX = normalizedPoint.x.coerceIn(0f, 1f)
        val clampedY = normalizedPoint.y.coerceIn(0f, 1f)
        val newPoint = PointF(clampedX, clampedY)

        corners.value = when (cornerIndex) {
            0 -> current.copy(topLeft = newPoint)
            1 -> current.copy(topRight = newPoint)
            2 -> current.copy(bottomRight = newPoint)
            3 -> current.copy(bottomLeft = newPoint)
            else -> current
        }
        activeDragPoint.value = newPoint
    }

    fun onDragEnd() {
        activeDragPoint.value = null
    }

    /** RESET button: back to the neutral inset quad. */
    fun resetCorners() {
        corners.value = CornerPoints.defaultNormalized()
        errorMessage.value = null
    }

    /**
     * ROTATE button: rotates the source image 90° clockwise and re-runs
     * detection, for sideways captures.
     */
    fun rotateImage90() {
        val bitmap = originalBitmap.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            isDetecting.value = true
            try {
                val matrix = Matrix().apply { postRotate(90f) }
                val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                if (rotated != bitmap) {
                    originalBitmap.value = rotated
                }
                runAutoDetection(rotated)
            } finally {
                isDetecting.value = false
            }
        }
    }

    fun toggleDebug() {
        showDebug.value = !showDebug.value
        if (showDebug.value && debugInfo.value == null) {
            viewModelScope.launch(Dispatchers.IO) {
                buildDebug()
            }
        }
    }

    fun buildDebug() {
        val bitmap = originalBitmap.value ?: return
        try {
            val old = debugInfo.value
            debugInfo.value = DocumentProcessor.buildDebugInfo(bitmap, detection.value)
            recycleDebugBitmaps(old)
        } catch (e: Exception) {
            errorMessage.value = "Debug preview failed: ${e.message}"
        }
    }

    private fun recycleDebugBitmaps(info: DocumentProcessor.ScanDebugInfo?) {
        if (info == null) return
        listOf(
            info.edgePreview, info.candidatesPreview, info.selectedPreview,
            info.perspectivePreview, info.deskewedPreview, info.dewarpedPreview,
            info.enhancedPreview
        ).forEach { bmp ->
            try {
                if (bmp != null && !bmp.isRecycled) bmp.recycle()
            } catch (e: Exception) {
                // best effort
            }
        }
    }

    fun dismissError() {
        errorMessage.value = null
    }

    /**
     * CONFIRM button: warps the EXACT user-selected quadrilateral through the
     * strict pipeline (document-only extraction, deskew, optional dewarp,
     * enhancement, A4) and saves the result.
     */
    fun applyPerspectiveWarpAndSave(
        context: Context,
        documentId: String?,
        imagePath: String,
        onComplete: (String) -> Unit
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            isProcessing.value = true
            errorMessage.value = null
            try {
                val bitmap = originalBitmap.value
                if (bitmap == null) {
                    withContext(Dispatchers.Main) {
                        isProcessing.value = false
                        errorMessage.value = "Image not loaded yet."
                    }
                    return@launch
                }
                val userCorners = corners.value
                val result = withContext(Dispatchers.Default) {
                    DocumentProcessor.processImageStrict(bitmap, FilterMode.AUTO, userCorners)
                }
                result.documentOnly.recycle()

                val app = context.applicationContext as CamScanApplication
                val procPath = app.storageManager.saveBitmap(result.a4, isOriginal = false)
                if (!result.a4.isRecycled) result.a4.recycle()

                val targetDocId = if (documentId.isNullOrBlank()) {
                val doc = repository.createDocument(
                    title = "Scan_${System.currentTimeMillis() / 1000}",
                    pages = listOf(Pair(imagePath, procPath))
                )
                doc.id
            } else {
                // If this original already belongs to a page of the document
                // (a pending low-confidence page), fix that page in place
                // instead of appending a duplicate.
                val updated = repository.applyCorners(imagePath, procPath, result.cornersUsed)
                if (!updated) {
                    repository.addPageToDocument(documentId, imagePath, procPath)
                }
                documentId
            }

                withContext(Dispatchers.Main) {
                    isProcessing.value = false
                    onComplete(targetDocId)
                }
            } catch (e: NeedsManualCornersException) {
                withContext(Dispatchers.Main) {
                    isProcessing.value = false
                    errorMessage.value = e.message ?: "Adjust the four corners and try again."
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    isProcessing.value = false
                    errorMessage.value = "Scan failed: ${e.message}"
                }
            }
        }
    }

    class Factory(private val repository: DocumentRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return CornerAdjustViewModel(repository) as T
        }
    }
}
