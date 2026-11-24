package com.pocketai.app

import android.accessibilityservice.AccessibilityService
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class PocketAccessibilityService : AccessibilityService() {

    private val nodeMap = mutableMapOf<Int, Pair<String, AccessibilityNodeInfo>>()

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    private fun norm(s: String) = s.lowercase().replace(Regex("[^a-z0-9]"), "")

    private fun collectText(node: AccessibilityNodeInfo, depth: Int = 0): String {
        if (node.isPassword || depth > 5) return ""
        val own = (node.text ?: node.contentDescription ?: "").toString().trim()
        if (own.isNotEmpty()) return own
        val parts = mutableListOf<String>()
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let {
                val t = collectText(it, depth + 1)
                if (t.isNotEmpty()) parts.add(t)
            }
        }
        return parts.joinToString(" ").take(60)
    }

    fun getCompressedScreen(maxNodes: Int = 35): String {
        val root = rootInActiveWindow ?: return "Screen: unavailable"
        nodeMap.clear()
        val lines = mutableListOf<String>()
        lines.add("Package: ${root.packageName ?: "unknown"}")
        var nextId = 1

        fun walk(node: AccessibilityNodeInfo) {
            if (nextId > maxNodes) return
            if (node.isPassword) return
            val isInteractive = node.isClickable || node.isEditable
            if (isInteractive) {
                val label = collectText(node)
                if (label.isNotEmpty() || node.isEditable) {
                    val id = nextId++
                    nodeMap[id] = label to node
                    val type = node.className?.toString()?.substringAfterLast('.') ?: "View"
                    val tag = if (node.isEditable) "input" else "tap"
                    lines.add("[$id] $type \"$label\" ($tag)")
                    return
                }
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { walk(it) }
            }
        }

        walk(root)
        return lines.joinToString("\n")
    }

    fun tap(id: Int): String {
        val entry = nodeMap[id] ?: return "Error: element [$id] not found"
        val ok = entry.second.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        return if (ok) "Tapped [$id] \"${entry.first}\"" else "Failed to tap [$id]"
    }

    fun findAndTapByText(target: String): String? {
        val tNorm = norm(target)
        val match = nodeMap.entries.firstOrNull {
            norm(it.value.first).contains(tNorm)
        } ?: return null
        val ok = match.value.second.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        return if (ok) "Tapped [${match.key}] \"${match.value.first}\"" else "Failed to tap \"${match.value.first}\""
    }

    fun findEditableId(): Int? {
        return nodeMap.entries.firstOrNull { it.value.second.isEditable }?.key
    }

    fun scrollDown(): String {
        val root = rootInActiveWindow ?: return "Screen: unavailable"
        fun findScrollable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (node.isScrollable) return node
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { findScrollable(it)?.let { s -> return s } }
            }
            return null
        }
        val target = findScrollable(root) ?: return "No scrollable view found"
        val ok = target.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
        return if (ok) "Scrolled down" else "Reached bottom"
    }

    fun typeText(id: Int, text: String, submit: Boolean = true): String {
        val entry = nodeMap[id] ?: return "Error: element [$id] not found"
        val node = entry.second
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        val typed = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        if (!typed) return "Failed to type into [$id]"

        if (submit && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val submitted = node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
            return if (submitted) "Typed \"$text\" and pressed Enter on [$id]"
            else "Typed \"$text\" into [$id] (IME enter unsupported; tap Search if needed)"
        }
        return "Typed \"$text\" into [$id]"
    }

    fun pressBack(): String {
        performGlobalAction(GLOBAL_ACTION_BACK)
        return "Pressed Back"
    }

    companion object {
        @Volatile
        var instance: PocketAccessibilityService? = null
            private set
    }
}
