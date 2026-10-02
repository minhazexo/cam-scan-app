package com.camscan.app.ui.editor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.camscan.app.CamScanApplication
import com.camscan.app.data.repository.DocumentRepository
import com.camscan.app.domain.model.FilterMode
import com.camscan.app.domain.model.PageModel
import com.camscan.app.domain.processor.DocumentEnhancer
import com.camscan.app.domain.processor.OcrEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PageEditorViewModel(private val repository: DocumentRepository) : ViewModel() {

    val currentPage = MutableStateFlow<PageModel?>(null)
    val previewBitmap = MutableStateFlow<Bitmap?>(null)
    val selectedFilter = MutableStateFlow(FilterMode.AUTO)
    val rotationDegrees = MutableStateFlow(0)
    val brightnessOffset = MutableStateFlow(0)
    val contrastMultiplier = MutableStateFlow(1.0f)

    val isProcessing = MutableStateFlow(false)
    val ocrTextResult = MutableStateFlow<String?>(null)

    fun loadPage(context: Context, documentId: String, pageId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val pages = repository.getPagesForDocument(documentId)
            val page = pages.firstOrNull { it.id == pageId }
            if (page != null) {
                currentPage.value = page
                selectedFilter.value = page.filterMode
                rotationDegrees.value = page.rotationDegrees

                updatePreview(context, page)
            }
        }
    }

    /**
     * Normal filter/brightness/contrast/rotation editing operates on the
     * PROCESSED (rectified) page, never on the raw camera photograph. Pulling
     * the original back in here would reintroduce the surrounding background
     * into an already-corrected scan.
     *
     * The original is reserved for recrop / reset / re-detection, which happen
     * in the corner editor. See [PageEditorSource].
     */
    private fun updatePreview(context: Context, page: PageModel) {
        val app = context.applicationContext as CamScanApplication
        val bitmap = app.storageManager.loadBitmap(PageEditorSource.resolve(page))
        if (bitmap != null) {
            var rotated = bitmap
            if (rotationDegrees.value % 360 != 0) {
                val matrix = Matrix().apply { postRotate(rotationDegrees.value.toFloat()) }
                rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            }

            val enhanced = DocumentEnhancer.enhance(
                bitmap = rotated,
                mode = selectedFilter.value,
                brightnessOffset = brightnessOffset.value,
                contrastMultiplier = contrastMultiplier.value
            )
            previewBitmap.value = enhanced
        }
    }

    fun onFilterSelected(context: Context, filterMode: FilterMode) {
        selectedFilter.value = filterMode
        currentPage.value?.let { updatePreview(context, it) }
    }

    fun rotateLeft(context: Context) {
        rotationDegrees.value = (rotationDegrees.value - 90 + 360) % 360
        currentPage.value?.let { updatePreview(context, it) }
    }

    fun rotateRight(context: Context) {
        rotationDegrees.value = (rotationDegrees.value + 90) % 360
        currentPage.value?.let { updatePreview(context, it) }
    }

    fun onBrightnessChanged(context: Context, brightness: Int) {
        brightnessOffset.value = brightness
        currentPage.value?.let { updatePreview(context, it) }
    }

    fun onContrastChanged(context: Context, contrast: Float) {
        contrastMultiplier.value = contrast
        currentPage.value?.let { updatePreview(context, it) }
    }

    fun runOcr() {
        viewModelScope.launch(Dispatchers.IO) {
            val bitmap = previewBitmap.value ?: return@launch
            isProcessing.value = true
            val text = OcrEngine.recognizeTextFromBitmap(bitmap)
            ocrTextResult.value = text
            isProcessing.value = false
        }
    }

    fun saveChanges(context: Context, onComplete: () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val page = currentPage.value ?: return@launch
            val bitmap = previewBitmap.value ?: return@launch

            isProcessing.value = true
            val app = context.applicationContext as com.camscan.app.CamScanApplication
            val newProcPath = app.storageManager.saveBitmap(bitmap, isOriginal = false)

            val updatedPage = page.copy(
                processedImagePath = newProcPath,
                filterMode = selectedFilter.value,
                rotationDegrees = rotationDegrees.value,
                ocrText = ocrTextResult.value ?: page.ocrText,
                // Saving an edited (non-original) page resolves the pending
                // state, so the page may now be exported.
                needsManualCorrection = false
            )

            repository.updatePage(updatedPage)

            withContext(Dispatchers.Main) {
                isProcessing.value = false
                onComplete()
            }
        }
    }

    class Factory(private val repository: DocumentRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return PageEditorViewModel(repository) as T
        }
    }
}
