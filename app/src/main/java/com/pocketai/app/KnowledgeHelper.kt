package com.pocketai.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor

object KnowledgeHelper {
    fun extractDocText(context: Context, uri: Uri, name: String): String {
        if (name.lowercase().endsWith(".pdf")) {
            return runCatching {
                val pfd: ParcelFileDescriptor = context.contentResolver.openFileDescriptor(uri, "r") ?: return ""
                val renderer = PdfRenderer(pfd)
                val sb = StringBuilder()
                val pages = minOf(renderer.pageCount, 8)
                for (i in 0 until pages) {
                    val page = renderer.openPage(i)
                    val bmp = Bitmap.createBitmap((page.width * 1.5f).toInt().coerceAtLeast(400), (page.height * 1.5f).toInt().coerceAtLeast(400), Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(android.graphics.Color.WHITE)
                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    page.close()
                    val txt = OcrHelper.extractText(bmp)
                    bmp.recycle()
                    if (txt.isNotBlank()) sb.append(txt).append("\n")
                }
                renderer.close(); pfd.close()
                sb.toString().trim()
            }.getOrDefault("")
        }
        return runCatching { context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: "" }.getOrDefault("")
    }

    fun chunkText(docName: String, text: String, size: Int = 380): List<KnowledgeChunkEntity> {
        val clean = text.replace(Regex("\\s+"), " ").trim()
        if (clean.isBlank()) return emptyList()
        val out = mutableListOf<KnowledgeChunkEntity>()
        var i = 0
        while (i < clean.length) {
            val end = (i + size).coerceAtMost(clean.length)
            out.add(KnowledgeChunkEntity(docName = docName, content = clean.substring(i, end)))
            i += (size - 60).coerceAtLeast(100)
        }
        return out
    }

    fun searchTopChunks(query: String, all: List<KnowledgeChunkEntity>, k: Int = 3): List<KnowledgeChunkEntity> {
        val tokens = query.lowercase().split(Regex("\\W+")).filter { it.length >= 3 }.toSet()
        if (tokens.isEmpty() || all.isEmpty()) return emptyList()
        return all.map { ch ->
            val low = ch.content.lowercase()
            val score = tokens.count { low.contains(it) }
            ch to score
        }.filter { it.second > 0 }.sortedByDescending { it.second }.take(k).map { it.first }
    }
}
