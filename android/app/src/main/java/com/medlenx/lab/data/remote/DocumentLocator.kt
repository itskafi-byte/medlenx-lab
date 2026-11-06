package com.medlenx.lab.data.remote

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.tasks.await

/** ML Kit supplies geometry only. Its recognized text is never used as transcription. */
class DocumentLocator : AutoCloseable {
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    suspend fun locate(bitmap: Bitmap): List<Rect> =
        recognizer.process(InputImage.fromBitmap(bitmap, 0)).await().textBlocks
            .flatMap { it.lines }
            .mapNotNull { line ->
                line.boundingBox?.let { source ->
                    Rect(source).takeIf { it.intersect(0, 0, bitmap.width, bitmap.height) && !it.isEmpty }
                }
            }

    override fun close() = recognizer.close()
}
