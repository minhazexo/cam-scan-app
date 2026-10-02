package com.camscan.app.ui.document

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.camscan.app.CamScanApplication
import com.camscan.app.data.repository.DocumentRepository
import com.camscan.app.domain.model.DocumentModel
import com.camscan.app.domain.model.PageModel
import com.camscan.app.domain.processor.DocumentEnhancer
import com.camscan.app.domain.processor.OcrEngine
import com.camscan.app.domain.processor.PdfGenerator
import com.camscan.app.domain.model.FilterMode
import com.camscan.app.domain.processor.DocumentProcessor
import com.camscan.app.domain.processor.PdfImporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class DocumentDetailViewModel(private val repository: DocumentRepository) : ViewModel() {

    val documentId = MutableStateFlow<String?>(null)

    val document = documentId.flatMapLatest { id ->
        if (id.isNullOrBlank()) flowOf(null)
        else flowOf(repository.getDocumentById(id))
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val pages: StateFlow<List<PageModel>> = documentId.flatMapLatest { id ->
        if (id.isNullOrBlank()) flowOf(emptyList())
        else repository.getPagesForDocumentFlow(id)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val isProcessing = MutableStateFlow(false)
    val isProcessingPdf = MutableStateFlow(false)
    val pdfProgressText = MutableStateFlow("Importing PDF...")
    val batchOcrResult = MutableStateFlow<String?>(null)

    fun loadDocument(id: String) {
        documentId.value = id
    }

    fun movePage(fromIndex: Int, toIndex: Int) {
        viewModelScope.launch {
            val currentList = pages.value.toMutableList()
            if (fromIndex in currentList.indices && toIndex in currentList.indices) {
                val moved = currentList.removeAt(fromIndex)
                currentList.add(toIndex, moved)
                val docId = documentId.value ?: return@launch
                repository.reorderPages(docId, currentList)
            }
        }
    }

    fun deletePage(page: PageModel) {
        viewModelScope.launch {
            repository.deletePage(page)
        }
    }

    fun addPagesFromGallery(context: Context, uris: List<Uri>) {
        viewModelScope.launch(Dispatchers.IO) {
            val docId = documentId.value ?: return@launch
            val app = context.applicationContext as CamScanApplication

            uris.forEach { uri ->
                val origPath = app.storageManager.saveUriToOriginalFile(uri)
                if (origPath != null) {
                    val bitmap = app.storageManager.loadBitmap(origPath)
                    if (bitmap != null) {
                        try {
                            val result = DocumentProcessor.processImageStrict(bitmap, FilterMode.AUTO, null)
                            result.documentOnly.recycle()
                            val procPath = app.storageManager.saveBitmap(result.a4, isOriginal = false)
                            if (!result.a4.isRecycled) result.a4.recycle()
                            repository.addPageToDocument(docId, origPath, procPath)
                        } catch (e: com.camscan.app.domain.processor.NeedsManualCornersException) {
                            // LOW confidence: keep pending manual correction.
                            repository.addPageToDocument(docId, origPath, origPath)
                        } finally {
                            if (!bitmap.isRecycled) bitmap.recycle()
                        }
                    }
                }
            }
        }
    }

    fun addPdfPagesFromGallery(context: Context, pdfUri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val docId = documentId.value ?: return@launch
            withContext(Dispatchers.Main) {
                isProcessingPdf.value = true
                pdfProgressText.value = "Importing PDF pages..."
            }
            val app = context.applicationContext as CamScanApplication
            val pagePairs = PdfImporter.importPdf(
                context = context,
                pdfUri = pdfUri,
                storageManager = app.storageManager
            ) { current, total ->
                withContext(Dispatchers.Main) {
                    pdfProgressText.value = "Scanning page $current of $total..."
                }
            }

            pagePairs.forEach { pair ->
                repository.addPageToDocument(docId, pair.first, pair.second)
            }
            withContext(Dispatchers.Main) {
                isProcessingPdf.value = false
            }
        }
    }

    fun runBatchOcr() {
        viewModelScope.launch(Dispatchers.IO) {
            isProcessing.value = true
            val pageList = pages.value
            val sb = StringBuilder()
            pageList.forEachIndexed { idx, page ->
                val text = OcrEngine.recognizeText(page.processedImagePath)
                if (text.isNotBlank()) {
                    sb.append("--- Page ${idx + 1} ---\n").append(text).append("\n\n")
                }
            }
            batchOcrResult.value = sb.toString().ifBlank { "No text recognized" }
            isProcessing.value = false
        }
    }

    fun exportPdf(context: Context, onComplete: (Uri?) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val doc = document.value ?: return@launch
            val app = context.applicationContext as CamScanApplication
            val pagePaths = pages.value.map { it.processedImagePath }

            val pdfFile = File(context.cacheDir, "${doc.title}.pdf")
            PdfGenerator.generatePdf(context, pagePaths, pdfFile)
            val exported = app.storageManager.exportScannedPdfToDedicatedFolder(pdfFile, doc.title)

            withContext(Dispatchers.Main) {
                onComplete(exported)
            }
        }
    }

    class Factory(private val repository: DocumentRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return DocumentDetailViewModel(repository) as T
        }
    }
}
