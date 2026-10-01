package com.camscan.app.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.camscan.app.domain.model.FilterMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class SettingsViewModel : ViewModel() {

    val defaultFilter = MutableStateFlow(FilterMode.AUTO)
    val includePageNumbers = MutableStateFlow(false)
    val cacheSizeBytes = MutableStateFlow(0L)

    fun calculateCacheSize(context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            val cacheDir = context.cacheDir
            val size = getFolderSize(cacheDir)
            cacheSizeBytes.value = size
        }
    }

    fun clearCache(context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            context.cacheDir.deleteRecursively()
            context.cacheDir.mkdirs()
            calculateCacheSize(context)
        }
    }

    private fun getFolderSize(file: File): Long {
        if (!file.exists()) return 0L
        if (file.isFile) return file.length()
        var size = 0L
        file.listFiles()?.forEach {
            size += getFolderSize(it)
        }
        return size
    }
}
