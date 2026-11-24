package com.pocketai.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.view.Display
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
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



    fun isShortsAd(): Boolean {
        val root = rootInActiveWindow ?: return false
        var found = false
        val adRegex = Regex("""(?i)^(?:sponsored|ad|skip ads?)(?:\b.*)?$""")
        fun walk(node: AccessibilityNodeInfo) {
            if (found) return
            val t = (node.text ?: "").toString().trim()
            val d = (node.contentDescription ?: "").toString().trim()
            if ((t.isNotEmpty() && adRegex.matches(t)) || (d.isNotEmpty() && adRegex.matches(d))) {
                found = true
                return
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { walk(it) }
            }
        }
        walk(root)
        return found
    }

    fun getShortsProgress(): Float? {
        val root = rootInActiveWindow ?: return null
        var progress: Float? = null
        val timePair = Regex("""(\d+):(\d{2})\s*(?:/|of)\s*(\d+):(\d{2})""")
        fun walk(node: AccessibilityNodeInfo) {
            if (progress != null) return
            val range = node.rangeInfo
            if (range != null && range.max > range.min) {
                progress = ((range.current - range.min) / (range.max - range.min)).coerceIn(0f, 1f)
                return
            }
            val txt = "${node.text ?: ""} ${node.contentDescription ?: ""}"
            val m = timePair.find(txt)
            if (m != null) {
                val cur = m.groupValues[1].toFloat() * 60f + m.groupValues[2].toFloat()
                val tot = m.groupValues[3].toFloat() * 60f + m.groupValues[4].toFloat()
                if (tot > 0f) {
                    progress = (cur / tot).coerceIn(0f, 1f)
                    return
                }
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { walk(it) }
            }
        }
        walk(root)
        return progress
    }


    suspend fun getShortsBarProgress(): Float? {
        getShortsProgress()?.let { return it }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return suspendCancellableCoroutine { cont ->
            takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    var ratio: Float? = null
                    try {
                        val hw = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                        val bmp = hw?.copy(Bitmap.Config.ARGB_8888, false)
                        hw?.recycle()
                        if (bmp != null) {
                            val w = bmp.width
                            val h = bmp.height
                            val yStart = (h * 0.88f).toInt()
                            val yEnd = (h * 0.94f).toInt()
                            for (y in yStart..yEnd step 2) {
                                // Check if row starts bright (white or YT red) at x=10..30
                                var startBright = 0
                                for (x in 8..32 step 4) {
                                    val c = bmp.getPixel(x, y)
                                    val r = Color.red(c); val g = Color.green(c); val b = Color.blue(c)
                                    if ((r > 190 && g > 190 && b > 190) || (r > 190 && g < 80 && b < 80)) startBright++
                                }
                                if (startBright >= 4) {
                                    var lastX = 0
                                    for (x in 0 until w step 6) {
                                        val c = bmp.getPixel(x, y)
                                        val r = Color.red(c); val g = Color.green(c); val b = Color.blue(c)
                                        if ((r > 190 && g > 190 && b > 190) || (r > 190 && g < 80 && b < 80)) {
                                            lastX = x
                                        } else if (x > lastX + 36) {
                                            break
                                        }
                                    }
                                    if (lastX > 20) {
                                        ratio = (lastX.toFloat() / w.toFloat()).coerceIn(0f, 1f)
                                        break
                                    }
                                }
                            }
                            bmp.recycle()
                        }
                    } catch (_: Exception) {
                    } finally {
                        result.hardwareBuffer.close()
                    }
                    if (cont.isActive) cont.resume(ratio)
                }

                override fun onFailure(errorCode: Int) {
                    if (cont.isActive) cont.resume(null)
                }
            })
        }
    }

    fun findEditableId(): Int? {
        return nodeMap.entries.firstOrNull { it.value.second.isEditable }?.key
    }

    fun scroll(direction: String): String {
        val dir = direction.lowercase().trim()
        val dm = resources.displayMetrics
        val w = dm.widthPixels.toFloat()
        val h = dm.heightPixels.toFloat()
        val path = Path()
        when (dir) {
            "down" -> { path.moveTo(w * 0.5f, h * 0.72f); path.lineTo(w * 0.5f, h * 0.28f) }
            "up" -> { path.moveTo(w * 0.5f, h * 0.30f); path.lineTo(w * 0.5f, h * 0.74f) }
            "right" -> { path.moveTo(w * 0.80f, h * 0.5f); path.lineTo(w * 0.20f, h * 0.5f) }
            "left" -> { path.moveTo(w * 0.20f, h * 0.5f); path.lineTo(w * 0.80f, h * 0.5f) }
            else -> return "Cannot scroll $dir"
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, 280L)
        val ok = dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
        return if (ok) "Swiped $dir" else "Cannot scroll $dir"
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
