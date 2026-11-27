package com.pocketai.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

object OcrHelper {
    fun extractText(bitmap: Bitmap): String {
        val latch = CountDownLatch(1)
        var result = ""
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        recognizer.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { visionText -> result = visionText.text.trim(); latch.countDown() }
            .addOnFailureListener { latch.countDown() }
        latch.await(8, TimeUnit.SECONDS)
        runCatching { recognizer.close() }
        return result
    }

    fun downscaleImageToTempFile(context: Context, uri: Uri, maxDim: Int): File? = runCatching {
        val raw = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) } ?: return null
        val scale = minOf(1f, maxDim.toFloat() / maxOf(raw.width, raw.height).coerceAtLeast(1))
        val bmp = if (scale < 1f) Bitmap.createScaledBitmap(raw, (raw.width * scale).toInt().coerceAtLeast(1), (raw.height * scale).toInt().coerceAtLeast(1), true) else raw
        val out = File(context.cacheDir, "vision_input.jpg")
        out.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 88, it) }
        out
    }.getOrNull()

    fun extractTextFromUri(context: Context, uri: Uri): String = runCatching {
        val bmp = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) } ?: return ""
        extractText(bmp)
    }.getOrDefault("")
}
