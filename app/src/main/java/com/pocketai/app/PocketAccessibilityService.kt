package com.pocketai.app

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
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
        return parts.joinToString(" ").take(80)
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

    fun tapOrdinal(oneBasedIndex: Int): String? {
        val rect = Rect()
        val candidates = nodeMap.entries.filter { (_, pair) ->
            val (label, node) = pair
            node.getBoundsInScreen(rect)
            !node.isEditable &&
                label.length >= 12 &&
                rect.height() >= 120 &&
                rect.top >= 220
        }
        val chosen = candidates.getOrNull(oneBasedIndex - 1) ?: return null
        val ok = chosen.value.second.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        val preview = chosen.value.first.take(35)
        return if (ok) "Tapped #$oneBasedIndex \"$preview\"" else "Failed to tap #$oneBasedIndex"
    }

    fun findEditableId(): Int? {
        return nodeMap.entries.firstOrNull { it.value.second.isEditable }?.key
    }

    fun scroll(direction: String): String {
        val root = rootInActiveWindow ?: return "Screen: unavailable"
        val scrollables = mutableListOf<AccessibilityNodeInfo>()
        fun collect(node: AccessibilityNodeInfo) {
            if (node.isScrollable) scrollables.add(node)
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { collect(it) }
            }
        }
        collect(root)
        val rect = Rect()
        val dir = direction.lowercase().trim()
        val wantHorizontal = (dir == "left" || dir == "right")
        val target = scrollables
            .filter {
                it.getBoundsInScreen(rect)
                if (wantHorizontal) rect.width() > rect.height() else rect.height() > rect.width()
            }
            .maxByOrNull {
                it.getBoundsInScreen(rect)
                rect.width() * rect.height()
            } ?: scrollables.firstOrNull()
            ?: return "No scrollable view found"

        val action = if (dir == "up" || dir == "left") {
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        } else {
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        }
        val ok = target.performAction(action)
        return if (ok) "Scrolled $dir" else "Cannot scroll $dir"
    }

    fun scrollDown(): String = scroll("down")

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
            else "Typed \"$text\" into [$id] (IME enter unsupported)"
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
