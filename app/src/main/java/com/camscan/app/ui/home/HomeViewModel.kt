package com.camscan.app.ui.home

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.camscan.app.CamScanApplication
import com.camscan.app.data.repository.DocumentRepository
import com.camscan.app.domain.model.DocumentModel
import com.camscan.app.domain.processor.DocumentEnhancer
import com.camscan.app.domain.processor.PdfGenerator
import com.camscan.app.domain.model.FilterMode
import com.camscan.app.domain.processor.DocumentProcessor
import com.camscan.app.domain.processor.PdfImporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class HomeViewModel(private val repository: DocumentRepository) : ViewModel() {

    val searchQuery = MutableStateFlow("")

    val documents: StateFlow<List<DocumentModel>> = searchQuery
        .flatMapLatest { query ->
            if (query.isBlank()) {
                repository.getAllDocuments()
            } else {
                repository.searchDocuments(query)
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    // PDF Import Options & Progress States
    val isImportingPdf = MutableStateFlow(false)
    val importProgressText = MutableStateFlow("Preparing PDF import...")
    val selectedPdfUriForOptions = MutableStateFlow<Uri?>(null)
    val selectedPdfPageCount = MutableStateFlow(0)
    val selectedPdfFileName = MutableStateFlow("")

    fun onSearchQueryChanged(query: String) {
        searchQuery.value = query
    }

    fun renameDocument(documentId: String, newTitle: String) {
        viewModelScope.launch {
            repository.updateDocumentTitle(documentId, newTitle)
        }
    }

    fun deleteDocument(documentId: String) {
        viewModelScope.launch {
            repository.deleteDocument(documentId)
        }
    }

    fun importImagesFromGallery(
        context: Context,
        uris: List<Uri>,
        onSuccess: (String) -> Unit
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            if (uris.isEmpty()) return@launch
            val app = context.applicationContext as CamScanApplication
            val storageManager = app.storageManager

            val pagePairs = mutableListOf<Pair<String, String>>()
            uris.forEach { uri ->
                val origPath = storageManager.saveUriToOriginalFile(uri)
                if (origPath != null) {
                    val bitmap = storageManager.loadBitmap(origPath)
                    if (bitmap != null) {
                        // HIGH -> processed A4. MEDIUM / LOW -> original only,
                        // pending the corner editor. Never auto-save an
                        // uncertain quad.
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
                val doc = repository.createDocument(
                    title = "Imported_${System.currentTimeMillis() / 1000}",
                    pages = pagePairs
                )
                withContext(Dispatchers.Main) {
                    onSuccess(doc.id)
                }
            }
        }
    }

    fun preparePdfImport(context: Context, pdfUri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val pageCount = PdfImporter.getPdfPageCount(context, pdfUri)
            val fileName = PdfImporter.getFileName(context, pdfUri) ?: "Document"
            withContext(Dispatchers.Main) {
                selectedPdfPageCount.value = pageCount
                selectedPdfFileName.value = fileName
                selectedPdfUriForOptions.value = pdfUri
            }
        }
    }

    fun dismissPdfOptions() {
        selectedPdfUriForOptions.value = null
    }

    fun confirmAndImportPdf(
        context: Context,
        pdfUri: Uri,
        startPage: Int,
        endPage: Int,
        filterMode: FilterMode,
        onSuccess: (String) -> Unit
    ) {
        dismissPdfOptions()
        isImportingPdf.value = true
        importProgressText.value = "Scanning PDF pages..."

        viewModelScope.launch(Dispatchers.IO) {
            val app = context.applicationContext as CamScanApplication
            val pagePairs = PdfImporter.importPdf(
                context = context,
                pdfUri = pdfUri,
                storageManager = app.storageManager,
                startPage = startPage,
                endPage = endPage,
                filterMode = filterMode
            ) { current, total ->
                importProgressText.value = "Scanning page $current of $total..."
            }

            isImportingPdf.value = false

            if (pagePairs.isNotEmpty()) {
                val rawName = selectedPdfFileName.value.ifBlank {
                    PdfImporter.getFileName(context, pdfUri) ?: "Imported_PDF_${System.currentTimeMillis() / 1000}"
                }
                val titleName = rawName.removeSuffix(".pdf").removeSuffix(".PDF")

                val doc = repository.createDocument(
                    title = titleName,
                    pages = pagePairs
                )
                withContext(Dispatchers.Main) {
                    onSuccess(doc.id)
                }
            }
        }
    }

    fun importPdfFromGallery(
        context: Context,
        pdfUri: Uri,
        onSuccess: (String) -> Unit
    ) {
        preparePdfImport(context, pdfUri)
    }

    fun getInputPdfs(context: Context): List<File> {
        val app = context.applicationContext as CamScanApplication
        return app.storageManager.getPdfsFromInputFolder()
    }

    fun importPdfFile(
        context: Context,
        pdfFile: File,
        onSuccess: (String) -> Unit
    ) {
        preparePdfImport(context, Uri.fromFile(pdfFile))
    }

    fun exportDocumentPdf(
        context: Context,
        document: DocumentModel,
        onComplete: (Uri?, String?) -> Unit
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val app = context.applicationContext as CamScanApplication
            val pages = repository.getPagesForDocument(document.id)

            // Never ship a placeholder page into a PDF: pages still awaiting
            // corner correction are raw renders, not scans.
            val pending = pages.count { it.needsManualCorrection }
            if (pending > 0) {
                withContext(Dispatchers.Main) {
                    onComplete(
                        null,
                        "Please correct $pending page${if (pending == 1) "" else "s"} before exporting"
                    )
                }
                return@launch
            }

            val paths = pages.map { it.processedImagePath }

            val pdfFile = File(context.cacheDir, "${document.title}.pdf")
            PdfGenerator.generatePdf(context, paths, pdfFile)
            val exportedUri = app.storageManager.exportScannedPdfToDedicatedFolder(pdfFile, document.title)

            withContext(Dispatchers.Main) {
                onComplete(exportedUri, null)
            }
        }
    }

class Factory(private val repository: DocumentRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return HomeViewModel(repository) as T
        }
    }
}
