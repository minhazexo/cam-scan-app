package com.camscan.app

import android.app.Application
import com.camscan.app.data.local.AppDatabase
import com.camscan.app.data.repository.DocumentRepository
import com.camscan.app.data.storage.StorageManager

class CamScanApplication : Application() {

    val database: AppDatabase by lazy { AppDatabase.getDatabase(this) }
    val storageManager: StorageManager by lazy { StorageManager(this) }
    val repository: DocumentRepository by lazy {
        DocumentRepository(
            documentDao = database.documentDao(),
            pageDao = database.pageDao(),
            storageManager = storageManager
        )
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    companion object {
        lateinit var instance: CamScanApplication
            private set
    }
}
