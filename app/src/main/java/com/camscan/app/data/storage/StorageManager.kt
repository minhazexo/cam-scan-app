package com.camscan.app.data.storage

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class StorageManager(private val context: Context) {

    private val documentsDir: File
        get() {
            val dir = File(context.filesDir, "scanned_documents")
            if (!dir.exists()) {
                dir.mkdirs()
            }
            return dir
        }

    fun createTempImageFile(): File {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return File.createTempFile("SCAN_${timeStamp}_", ".jpg", context.cacheDir)
    }

    fun saveBitmap(bitmap: Bitmap, isOriginal: Boolean = false, quality: Int = 90): String {
        val prefix = if (isOriginal) "ORIG_" else "PROC_"
        val filename = "${prefix}${UUID.randomUUID()}.jpg"
        val file = File(documentsDir, filename)

        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
        }
        return file.absolutePath
    }

    fun saveUriToOriginalFile(uri: Uri): String? {
        return try {
            val inputStream: InputStream? = context.contentResolver.openInputStream(uri)
            val bitmap = BitmapFactory.decodeStream(inputStream)
            inputStream?.close()
            if (bitmap != null) {
                saveBitmap(bitmap, isOriginal = true)
            } else null
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun loadBitmap(path: String): Bitmap? {
        return try {
            val file = File(path)
            if (file.exists()) {
                BitmapFactory.decodeFile(file.absolutePath)
            } else null
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun deleteFile(path: String?): Boolean {
        if (path.isNullOrBlank()) return false
        return try {
            val file = File(path)
            if (file.exists()) file.delete() else false
        } catch (e: Exception) {
            false
        }
    }

    fun getFileUri(path: String): Uri {
        val file = File(path)
        return FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
    }

    fun getInputPdfFolder(): File {
        val dir = try {
            File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
                "CamScan/InputPDFs"
            )
        } catch (e: Exception) {
            File(context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), "CamScan/InputPDFs")
        }
        if (!dir.exists()) {
            try {
                dir.mkdirs()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        return dir
    }

    fun getScannedPdfFolder(): File {
        val dir = try {
            File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
                "CamScan/ScannedPDFs"
            )
        } catch (e: Exception) {
            File(context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), "CamScan/ScannedPDFs")
        }
        if (!dir.exists()) {
            try {
                dir.mkdirs()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        return dir
    }

    fun getPdfsFromInputFolder(): List<File> {
        val folder = getInputPdfFolder()
        return folder.listFiles { file -> file.isFile && file.extension.equals("pdf", true) }?.toList() ?: emptyList()
    }

    fun exportScannedPdfToDedicatedFolder(pdfFile: File, title: String): Uri? {
        val sanitizedTitle = title.replace("[^a-zA-Z0-9._-]".toRegex(), "_")
        val displayName = if (sanitizedTitle.endsWith(".pdf", true)) sanitizedTitle else "$sanitizedTitle.pdf"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOCUMENTS + "/CamScan/ScannedPDFs")
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                ?: resolver.insert(MediaStore.Files.getContentUri("external"), contentValues)

            if (uri != null) {
                resolver.openOutputStream(uri)?.use { out ->
                    pdfFile.inputStream().use { input ->
                        input.copyTo(out)
                    }
                }
                return uri
            }
        } else {
            val targetDir = getScannedPdfFolder()
            val targetFile = File(targetDir, displayName)
            pdfFile.copyTo(targetFile, overwrite = true)
            return Uri.fromFile(targetFile)
        }
        return exportPdfToPublicStorage(pdfFile, title)
    }

    fun exportPdfToPublicStorage(pdfFile: File, title: String): Uri? {
        return exportScannedPdfToDedicatedFolder(pdfFile, title)
    }
}
