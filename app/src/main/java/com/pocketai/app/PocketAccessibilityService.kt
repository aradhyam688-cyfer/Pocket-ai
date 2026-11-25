package com.pocketai.app

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
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
    private var overlayView: View? = null
    @Volatile var lastExternalScreenText: String = ""
        private set
    @Volatile var lastExternalPackage: String = ""
        private set
    private var lastCacheTime = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == "com.pocketai.app" || pkg == "com.android.systemui") return
        lastExternalPackage = pkg
        val now = System.currentTimeMillis()
        if (now - lastCacheTime > 2500L) {
            lastCacheTime = now
            val txt = readFullScreenText(1800)
            if (txt.length > 30) lastExternalScreenText = txt
        }
    }

    override fun onInterrupt() {}


    fun showFloatingChoices(
        choices: List<UiElement>,
        onPick: (UiElement) -> Unit,
        onCancel: () -> Unit
    ) {
        Handler(Looper.getMainLooper()).post {
            removeFloatingChoices()
            val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.parseColor("#EE1E1E24"))
                setPadding(36, 28, 36, 28)
            }
            val title = TextView(this).apply {
                text = "PocketAI: Pick one"
                setTextColor(Color.WHITE)
                textSize = 16f
                setPadding(0, 0, 0, 16)
            }
            box.addView(title)
            for (el in choices) {
                val btn = Button(this).apply {
                    text = el.displayLabel.take(45)
                    setOnClickListener {
                        removeFloatingChoices()
                        onPick(el)
                    }
                }
                box.addView(btn)
            }
            val cancelBtn = Button(this).apply {
                text = "Cancel"
                setOnClickListener {
                    removeFloatingChoices()
                    onCancel()
                }
            }
            box.addView(cancelBtn)

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.CENTER
            }
            overlayView = box
            wm.addView(box, params)
        }
    }

    fun removeFloatingChoices() {
        val v = overlayView ?: return
        overlayView = null
        try {
            val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            wm.removeView(v)
        } catch (_: Exception) {}
    }

    override fun onDestroy() {
        removeFloatingChoices()
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


    fun readFullScreenText(maxChars: Int = 2500): String {
        val root = rootInActiveWindow ?: return ""
        val sb = StringBuilder()
        fun walk(node: AccessibilityNodeInfo) {
            if (sb.length >= maxChars || node.isPassword) return
            val t = (node.text ?: node.contentDescription ?: "").toString().trim()
            if (t.isNotEmpty() && !isNoiseOrLink(t)) {
                sb.append(t).append("\n")
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { walk(it) }
            }
        }
        walk(root)
        return sb.toString().trim().take(maxChars)
    }

    fun isOnAppHome(pkg: String? = null): Boolean {
        val obs = observeScreen(45)
        val p = pkg ?: obs.packageName
        val labels = obs.elements.map { it.displayLabel.lowercase() }
        val hasBackArrow = labels.any { it == "navigate up" || it == "back" }
        return when {
            p.contains("whatsapp") -> {
                val hasBottomInput = obs.elements.any { it.isEditable && it.bounds.bottom > 1000 }
                val hasTabs = labels.any { it.contains("chats") } && labels.any { it.contains("updates") || it.contains("calls") }
                hasTabs && !hasBottomInput && !hasBackArrow
            }
            p.contains("youtube") -> {
                val hasTabs = labels.any { it == "home" } && labels.any { it == "shorts" }
                hasTabs && !hasBackArrow
            }
            p.contains("chrome") || p.contains("googlequicksearchbox") -> {
                !hasBackArrow && obs.elements.any { it.isEditable || it.displayLabel.contains("search", ignoreCase = true) }
            }
            else -> !hasBackArrow
        }
    }

    fun goToAppHome(context: Context, targetApp: String = ""): String {
        val obs = observeScreen(45)
        val p = obs.packageName
        if (isOnAppHome(p)) return "Already on home screen ($p)"

        // 1. Try direct Home / Chats tab tap
        val tab = obs.elements.firstOrNull {
            val l = it.displayLabel.lowercase()
            it.isClickable && (l == "home" || l == "chats" || l == "home tab")
        }
        if (tab != null) {
            tap(tab.id)
            return "Tapped ${tab.displayLabel} tab"
        }

        // 2. Bounded Back loop
        for (i in 1..4) {
            pressBack()
            Thread.sleep(550L)
            val now = observeScreen(45)
            if (isOnAppHome(now.packageName)) return "Returned to home screen after $i back(s)"
            if (now.packageName != p) break
        }

        // 3. Relaunch if backed out completely
        val pkgToLaunch = if (targetApp.isNotBlank()) targetApp else p
        context.packageManager.getLaunchIntentForPackage(pkgToLaunch)?.let {
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            context.startActivity(it)
            return "Relaunched $pkgToLaunch home"
        }
        return "Could not return to home screen"
    }

    fun observeScreen(maxNodes: Int = 40): ScreenObservation {
        val root = rootInActiveWindow ?: return ScreenObservation("unavailable", elements = emptyList())
        nodeMap.clear()
        val list = mutableListOf<UiElement>()
        var nextId = 1
        val rect = Rect()

        fun walk(node: AccessibilityNodeInfo) {
            if (nextId > maxNodes) return
            if (node.isPassword) return
            val isInteractive = node.isClickable || node.isEditable
            if (isInteractive) {
                val label = collectText(node)
                if (label.isNotEmpty() || node.isEditable) {
                    val id = nextId++
                    nodeMap[id] = label to node
                    node.getBoundsInScreen(rect)
                    list.add(
                        UiElement(
                            id = id,
                            className = node.className?.toString()?.substringAfterLast('.') ?: "View",
                            text = (node.text ?: "").toString().trim().ifBlank { label },
                            contentDescription = (node.contentDescription ?: "").toString().trim(),
                            bounds = Rect(rect),
                            isClickable = node.isClickable,
                            isEditable = node.isEditable,
                            isScrollable = node.isScrollable
                        )
                    )
                    return
                }
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { walk(it) }
            }
        }

        walk(root)
        return ScreenObservation(root.packageName?.toString() ?: "unknown", elements = list)
    }

    fun getCompressedScreen(maxNodes: Int = 35): String {
        val obs = observeScreen(maxNodes)
        return if (obs.packageName == "unavailable") "Screen: unavailable" else obs.toCompressedText()
    }

    private fun isNoiseOrLink(label: String): Boolean {
        val l = label.lowercase()
        return l.contains("'s status") ||
            l.contains("new update") ||
            l.contains("http://") ||
            l.contains("https://") ||
            l.contains("www.")
    }


    fun findPdfDocuments(): List<UiElement> {
        val obs = observeScreen(maxNodes = 50)
        val nonPdf = Regex("""(?i)\.(?:jpg|jpeg|png|webp|mp4|mkv|mp3|ogg|opus|doc|docx|xlsx|zip)\b""")
        val timeRegex = Regex("""\b(\d{1,2}):(\d{2})\s*(am|pm)?\b""", RegexOption.IGNORE_CASE)

        fun timeScore(label: String): Int {
            val m = timeRegex.find(label) ?: return -1
            var h = m.groupValues[1].toIntOrNull() ?: return -1
            val min = m.groupValues[2].toIntOrNull() ?: return -1
            val ampm = m.groupValues[3].lowercase()
            if (ampm == "pm" && h < 12) h += 12
            if (ampm == "am" && h == 12) h = 0
            return h * 60 + min
        }

        return obs.elements
            .filter { el ->
                val l = el.displayLabel
                el.isClickable && !el.isEditable &&
                    !isNoiseOrLink(l) &&
                    !nonPdf.containsMatchIn(l) &&
                    (l.contains(".pdf", ignoreCase = true) || Regex("""(?i)\bpdf\b""").containsMatchIn(l))
            }
            .sortedWith(
                compareByDescending<UiElement> { it.bounds.bottom }
                    .thenByDescending { timeScore(it.displayLabel) }
            )
    }

    fun checkFileStatusMessage(): String? {
        val obs = observeScreen(maxNodes = 35)
        for (el in obs.elements) {
            val l = el.displayLabel.lowercase()
            when {
                l == "download" || l.startsWith("download ") -> return "DOWNLOAD:${el.id}"
                l.contains("doesn't exist") || l.contains("not found") || l.contains("unavailable") || l.contains("can't open") -> {
                    return "UNAVAILABLE:${el.displayLabel}"
                }
            }
        }
        return null
    }

    fun findMatchingElements(target: String, skipEditable: Boolean = true): List<UiElement> {
        val obs = observeScreen()
        val tNorm = norm(target)
        val pool = (if (skipEditable) obs.elements.filter { !it.isEditable } else obs.elements).filter { !isNoiseOrLink(it.displayLabel) }
        val direct = pool.filter { norm(it.displayLabel).contains(tNorm) }
        if (direct.isNotEmpty()) return direct.distinctBy { norm(it.displayLabel) }.take(4)

        val tokens = target.lowercase().split(Regex("\\s+")).filter { it.length >= 2 }
        if (tokens.isEmpty()) return emptyList()
        return pool.mapNotNull { el ->
            val l = el.displayLabel.lowercase()
            val score = tokens.count { l.contains(it) }
            if (score > 0) el to score else null
        }.sortedByDescending { it.second }.map { it.first }.distinctBy { norm(it.displayLabel) }.take(4)
    }

    fun tap(id: Int): String {
        val entry = nodeMap[id] ?: return "Error: element [$id] not found"
        val ok = entry.second.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        return if (ok) "Tapped [$id] \"${entry.first}\"" else "Failed to tap [$id]"
    }

    fun findAndTapByText(target: String, skipEditable: Boolean = false, excludeText: String = ""): String? {
        val tNorm = norm(target)
        val exNorm = norm(excludeText)
        val pool = (if (skipEditable) nodeMap.entries.filter { !it.value.second.isEditable } else nodeMap.entries)
            .filter { !isNoiseOrLink(it.value.first) }
            .filter { exNorm.isEmpty() || norm(it.value.first) != exNorm }
        val exact = pool.firstOrNull { norm(it.value.first).contains(tNorm) }
        val tokens = target.lowercase().split(Regex("\\s+")).filter { it.length >= 2 }
        val chosen = exact ?: if (tokens.isNotEmpty()) {
            pool
                .map { entry ->
                    val labelLower = entry.value.first.lowercase()
                    val score = tokens.count { labelLower.contains(it) }
                    entry to score
                }
                .filter { it.second > 0 }
                .maxByOrNull { it.second }
                ?.first
        } else null
        val match = chosen ?: return null
        val ok = match.value.second.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        return if (ok) "Tapped [${match.key}] \"${match.value.first.take(40)}\"" else "Failed to tap \"${match.value.first.take(40)}\""
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
