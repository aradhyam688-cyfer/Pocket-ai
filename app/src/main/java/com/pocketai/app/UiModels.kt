package com.pocketai.app

import android.graphics.Rect

data class UiElement(
    val id: Int,
    val className: String,
    val text: String,
    val contentDescription: String,
    val bounds: Rect,
    val isClickable: Boolean,
    val isEditable: Boolean,
    val isScrollable: Boolean
) {
    val displayLabel: String
        get() = text.ifBlank { contentDescription }.trim()
}

data class ScreenObservation(
    val packageName: String,
    val timestamp: Long = System.currentTimeMillis(),
    val elements: List<UiElement>
) {
    fun toCompressedText(): String {
        val sb = StringBuilder("Package: $packageName\n")
        for (el in elements) {
            val tag = when {
                el.isEditable -> "input"
                el.isScrollable -> "scroll"
                else -> "tap"
            }
            sb.append("[${el.id}] ${el.className} \"${el.displayLabel}\" ($tag)\n")
        }
        return sb.toString().trimEnd()
    }

    fun findPdfCandidates(): List<UiElement> {
        return elements.filter { el ->
            el.isClickable && !el.isEditable &&
                (el.displayLabel.contains(".pdf", ignoreCase = true) ||
                    Regex("""(?i)\bpdf\b""").containsMatchIn(el.displayLabel))
        }
    }
}
