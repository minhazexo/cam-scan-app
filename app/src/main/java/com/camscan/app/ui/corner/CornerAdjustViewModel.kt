package com.camscan.app.ui.corner

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PointF
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.camscan.app.CamScanApplication
import com.camscan.app.data.repository.DocumentRepository
import com.camscan.app.domain.model.CornerPoints
import com.camscan.app.domain.processor.DocumentDetector
import com.camscan.app.domain.processor.DocumentEnhancer
import com.camscan.app.domain.processor.PerspectiveWarper
import com.camscan.app.domain.model.FilterMode
import com.camscan.app.domain.processor.DocumentProcessor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CornerAdjustViewModel(private val repository: DocumentRepository) : ViewModel() {

    val corners = MutableStateFlow(CornerPoints.defaultNormalized())
    val originalBitmap = MutableStateFlow<Bitmap?>(null)
    val activeDragPoint = MutableStateFlow<PointF?>(null)
    val isProcessing = MutableStateFlow(false)

    fun loadImage(context: Context, imagePath: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val app = context.applicationContext as com.camscan.app.CamScanApplication
            val bitmap = app.storageManager.loadBitmap(imagePath)
            if (bitmap != null) {
                originalBitmap.value = bitmap
                val detected = DocumentDetector.detectCorners(bitmap)
                corners.value = detected
            }
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

    fun resetCorners() {
        corners.value = CornerPoints.defaultNormalized()
    }

    fun applyPerspectiveWarpAndSave(
        context: Context,
        documentId: String?,
        imagePath: String,
        onComplete: (String) -> Unit
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            isProcessing.value = true
            val bitmap = originalBitmap.value ?: return@launch
            val warped = PerspectiveWarper.warpToRectangle(bitmap, corners.value)
            val enhanced = DocumentEnhancer.enhance(warped, FilterMode.AUTO)
            val a4Formatted = DocumentProcessor.formatToA4Canvas(enhanced)

            val app = context.applicationContext as CamScanApplication
            val procPath = app.storageManager.saveBitmap(a4Formatted, isOriginal = false)

            val targetDocId = if (documentId.isNullOrBlank()) {
                val doc = repository.createDocument(
                    title = "Scan_${System.currentTimeMillis() / 1000}",
                    pages = listOf(Pair(imagePath, procPath))
                )
                doc.id
            } else {
                repository.addPageToDocument(documentId, imagePath, procPath)
                documentId
            }

            withContext(Dispatchers.Main) {
                isProcessing.value = false
                onComplete(targetDocId)
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
