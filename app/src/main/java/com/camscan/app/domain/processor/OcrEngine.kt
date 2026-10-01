package com.camscan.app.domain.processor

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume

object OcrEngine {

    private val recognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    suspend fun recognizeText(imagePath: String): String = withContext(Dispatchers.IO) {
        val file = File(imagePath)
        if (!file.exists()) return@withContext ""

        val bitmap = BitmapFactory.decodeFile(file.absolutePath) ?: return@withContext ""
        val text = recognizeTextFromBitmap(bitmap)
        bitmap.recycle()
        text
    }

    suspend fun recognizeTextFromBitmap(bitmap: Bitmap): String = suspendCancellableCoroutine { continuation ->
        val inputImage = InputImage.fromBitmap(bitmap, 0)
        recognizer.process(inputImage)
            .addOnSuccessListener { visionText ->
                if (continuation.isActive) {
                    continuation.resume(visionText.text)
                }
            }
            .addOnFailureListener { e ->
                e.printStackTrace()
                if (continuation.isActive) {
                    continuation.resume("")
                }
            }
    }
}
