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
import com.camscan.app.domain.processor.DocumentDetector
import com.camscan.app.domain.processor.DocumentEnhancer
import com.camscan.app.domain.model.FilterMode
import com.camscan.app.domain.processor.DocumentProcessor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CameraScanViewModel(private val repository: DocumentRepository) : ViewModel() {

    val detectedCorners = MutableStateFlow(CornerPoints.defaultNormalized())
    val isBatchMode = MutableStateFlow(false)
    val capturedPages = MutableStateFlow<List<Pair<String, String>>>(emptyList())
    val isProcessing = MutableStateFlow(false)

    fun onFrameAnalyzed(imageProxy: ImageProxy) {
        viewModelScope.launch(Dispatchers.Default) {
            try {
                val bitmap = imageProxy.toBitmap()
                val matrix = Matrix().apply { postRotate(imageProxy.imageInfo.rotationDegrees.toFloat()) }
                val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                val corners = DocumentDetector.detectCorners(rotated)
                detectedCorners.value = corners
                if (rotated != bitmap) rotated.recycle()
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                imageProxy.close()
            }
        }
    }

    fun toggleBatchMode() {
        isBatchMode.value = !isBatchMode.value
    }

    fun processCapturedPhoto(
        context: Context,
        imageProxy: ImageProxy,
        documentId: String?,
        onComplete: (String?, String) -> Unit // documentId, savedOriginalPath
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
                val processed = DocumentProcessor.processImage(rotatedBitmap, FilterMode.AUTO)
                val procPath = storageManager.saveBitmap(processed, isOriginal = false)

                if (isBatchMode.value) {
                    capturedPages.value = capturedPages.value + Pair(origPath, procPath)
                    isProcessing.value = false
                } else {
                    if (documentId.isNullOrBlank()) {
                        val doc = repository.createDocument(
                            title = "Scan_${System.currentTimeMillis() / 1000}",
                            pages = listOf(Pair(origPath, procPath))
                        )
                        withContext(Dispatchers.Main) {
                            isProcessing.value = false
                            onComplete(doc.id, origPath)
                        }
                    } else {
                        repository.addPageToDocument(documentId, origPath, procPath)
                        withContext(Dispatchers.Main) {
                            isProcessing.value = false
                            onComplete(documentId, origPath)
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                isProcessing.value = false
            } finally {
                imageProxy.close()
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
                        val processed = DocumentProcessor.processImage(bitmap, FilterMode.AUTO)
                        val procPath = storageManager.saveBitmap(processed, isOriginal = false)
                        pagePairs.add(Pair(origPath, procPath))
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

    class Factory(private val repository: DocumentRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return CameraScanViewModel(repository) as T
        }
    }
}
