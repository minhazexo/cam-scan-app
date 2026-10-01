package com.camscan.app.ui.home

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.camscan.app.data.repository.DocumentRepository
import com.camscan.app.domain.model.DocumentModel
import com.camscan.app.domain.processor.DocumentEnhancer
import com.camscan.app.domain.processor.PdfGenerator
import com.camscan.app.domain.model.FilterMode
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
            val app = context.applicationContext as com.camscan.app.CamScanApplication
            val storageManager = app.storageManager

            val pagePairs = mutableListOf<Pair<String, String>>()
            uris.forEach { uri ->
                val origPath = storageManager.saveUriToOriginalFile(uri)
                if (origPath != null) {
                    val bitmap = storageManager.loadBitmap(origPath)
                    if (bitmap != null) {
                        val enhanced = DocumentEnhancer.enhance(bitmap, FilterMode.AUTO)
                        val procPath = storageManager.saveBitmap(enhanced, isOriginal = false)
                        pagePairs.add(Pair(origPath, procPath))
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

    fun importPdfFromGallery(
        context: Context,
        pdfUri: Uri,
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
                val rawName = com.camscan.app.domain.processor.PdfImporter.getFileName(context, pdfUri)
                    ?: "Imported_PDF_${System.currentTimeMillis() / 1000}"
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

    fun getInputPdfs(context: Context): List<File> {
        val app = context.applicationContext as com.camscan.app.CamScanApplication
        return app.storageManager.getPdfsFromInputFolder()
    }

    fun importPdfFile(
        context: Context,
        pdfFile: File,
        onSuccess: (String) -> Unit
    ) {
        importPdfFromGallery(context, Uri.fromFile(pdfFile), onSuccess)
    }

    fun exportDocumentPdf(
        context: Context,
        document: DocumentModel,
        onComplete: (Uri?) -> Unit
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val app = context.applicationContext as com.camscan.app.CamScanApplication
            val pages = repository.getPagesForDocument(document.id)
            val paths = pages.map { it.processedImagePath }

            val pdfFile = File(context.cacheDir, "${document.title}.pdf")
            PdfGenerator.generatePdf(context, paths, pdfFile)
            val exportedUri = app.storageManager.exportScannedPdfToDedicatedFolder(pdfFile, document.title)

            withContext(Dispatchers.Main) {
                onComplete(exportedUri)
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
