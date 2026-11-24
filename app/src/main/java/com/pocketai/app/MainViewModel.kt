package com.pocketai.app

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.nehuatl.llamacpp.LlamaHelper

data class ChatMessage(val role: String, val text: String)
data class PendingAction(val description: String, val toolTag: String)
data class CloudConfig(
    val provider: CloudProvider = CloudProvider.GEMINI,
    val apiKey: String = "",
    val model: String = CloudProvider.GEMINI.defaultModel,
    val autoHelp: Boolean = true
)

sealed class AutoStep {
    data class OpenApp(val appQuery: String) : AutoStep()
    data class Search(val query: String) : AutoStep()
    data class MessageContact(val person: String, val text: String) : AutoStep()
    object Doomscroll : AutoStep()
    data class TapOrdinal(val index: Int) : AutoStep()
    data class TapText(val target: String) : AutoStep()
    data class Scroll(val direction: String) : AutoStep()
    object Back : AutoStep()
}

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val db = AppDatabase.get(application)
    private val messageDao = db.messageDao()
    private val memoryDao = db.memoryDao()
    private val prefs = application.getSharedPreferences("pocketai_cloud", Context.MODE_PRIVATE)

    private val _llmFlow = MutableSharedFlow<LlamaHelper.LLMEvent>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    private val llamaHelper by lazy {
        LlamaHelper(application.contentResolver, viewModelScope, _llmFlow)
    }

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages = _messages.asStateFlow()

    private val _memoryCount = MutableStateFlow(0)
    val memoryCount = _memoryCount.asStateFlow()

    private val _fastAuto = MutableStateFlow(true)
    val fastAuto = _fastAuto.asStateFlow()

    private val _cloudConfig = MutableStateFlow(loadCloudConfig())
    val cloudConfig = _cloudConfig.asStateFlow()

    private val _status = MutableStateFlow("No model loaded")
    val status = _status.asStateFlow()

    private val _isGenerating = MutableStateFlow(false)
    val isGenerating = _isGenerating.asStateFlow()

    private val _isNavigating = MutableStateFlow(false)
    val isNavigating = _isNavigating.asStateFlow()

    private val _pendingConfirmation = MutableStateFlow<PendingAction?>(null)
    val pendingConfirmation = _pendingConfirmation.asStateFlow()

    private var isLoaded = false
    private var activeGoal = ""
    private var stepCount = 0
    private var currentAssistantMsgId: Long? = null
    private var cachedFacts: List<String> = emptyList()
    private var pendingSteps: List<AutoStep> = emptyList()
    private val maxSteps = 6
    private val maxScrollAttempts = 10
    private val openAppRegex = Regex("""\[TOOL:\s*open_app|[^\]]+\]""")

    init {
        viewModelScope.launch(Dispatchers.IO) {
            _messages.value = messageDao.getAll().map { ChatMessage(it.role, it.text) }
            cachedFacts = memoryDao.getAll().map { it.fact }
            _memoryCount.value = cachedFacts.size
        }

        viewModelScope.launch {
            _llmFlow.collect { event ->
                when (event) {
                    is LlamaHelper.LLMEvent.Ongoing -> {
                        val list = _messages.value.toMutableList()
                        val last = list.lastOrNull()
                        if (last != null && last.role == "Assistant") {
                            list[list.lastIndex] = last.copy(text = last.text + event.word)
                            _messages.value = list
                        }
                    }
                    is LlamaHelper.LLMEvent.Done -> {
                        _isGenerating.value = false
                        val reply = _messages.value.lastOrNull()?.text.orEmpty()
                        currentAssistantMsgId?.let { id ->
                            viewModelScope.launch(Dispatchers.IO) { messageDao.updateText(id, reply) }
                        }
                        handleTurnCompletion()
                    }
                    is LlamaHelper.LLMEvent.Error -> {
                        _isGenerating.value = false
                        _isNavigating.value = false
                        _status.value = "Error: ${event.message}"
                    }
                    else -> {}
                }
            }
        }
    }

    private fun loadCloudConfig(): CloudConfig {
        val provName = prefs.getString("provider", CloudProvider.GEMINI.name) ?: CloudProvider.GEMINI.name
        val prov = runCatching { CloudProvider.valueOf(provName) }.getOrDefault(CloudProvider.GEMINI)
        return CloudConfig(
            provider = prov,
            apiKey = prefs.getString("apiKey", "") ?: "",
            model = prefs.getString("model", prov.defaultModel) ?: prov.defaultModel,
            autoHelp = prefs.getBoolean("autoHelp", true)
        )
    }

    fun saveCloudConfig(provider: CloudProvider, apiKey: String, model: String, autoHelp: Boolean) {
        val cleanModel = model.ifBlank { provider.defaultModel }
        prefs.edit()
            .putString("provider", provider.name)
            .putString("apiKey", apiKey.trim())
            .putString("model", cleanModel)
            .putBoolean("autoHelp", autoHelp)
            .apply()
        _cloudConfig.value = CloudConfig(provider, apiKey.trim(), cleanModel, autoHelp)
    }

    fun toggleFastAuto() {
        _fastAuto.value = !_fastAuto.value
    }

    fun loadModel(uriString: String) {
        _status.value = "Loading model..."
        viewModelScope.launch(Dispatchers.IO) {
            llamaHelper.load(path = uriString, contextLength = 2048) {
                isLoaded = true
                _status.value = "Model ready (offline)"
            }
        }
    }

    private fun appendMessage(role: String, text: String, onInserted: ((Long) -> Unit)? = null) {
        _messages.value = _messages.value + ChatMessage(role, text)
        viewModelScope.launch(Dispatchers.IO) {
            val id = messageDao.insert(MessageEntity(role = role, text = text))
            onInserted?.invoke(id)
        }
    }

    fun saveFact(fact: String) {
        val clean = fact.trim()
        if (clean.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            memoryDao.insert(MemoryEntity(fact = clean))
            cachedFacts = memoryDao.getAll().map { it.fact }
            _memoryCount.value = cachedFacts.size
        }
    }

    fun clearChat() {
        _messages.value = emptyList()
        viewModelScope.launch(Dispatchers.IO) { messageDao.clearAll() }
    }
    private fun parseAutomationSteps(input: String): List<AutoStep> {
        val clauses = input.split(Regex("""(?i)\s*(?:,|\band\b|\bthen\b|\s+(?=(?:tap|click|select|search|scroll|back|doomscroll|message)\b))\s*"""))
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        val steps = mutableListOf<AutoStep>()
        for (c in clauses) {
            val lower = c.lowercase()
            when {
                lower.startsWith("open ") -> steps.add(AutoStep.OpenApp(c.substring(5).trim()))
                lower.contains("doomscroll") -> steps.add(AutoStep.Doomscroll)
                lower.startsWith("message ") -> {
                    val body = c.substring(8).trim()
                    val qMatch = Regex("""^(.+?)\s+['"](.+?)['"]$""").find(body)
                    val sMatch = Regex("""(?i)^(.+?)\s+(?:saying|text)\s+(.+)$""").find(body)
                    if (qMatch != null) {
                        steps.add(AutoStep.MessageContact(qMatch.groupValues[1].trim(), qMatch.groupValues[2].trim()))
                    } else if (sMatch != null) {
                        steps.add(AutoStep.MessageContact(sMatch.groupValues[1].trim(), sMatch.groupValues[2].trim()))
                    } else {
                        val parts = body.split(" ", limit = 2)
                        if (parts.size == 2) steps.add(AutoStep.MessageContact(parts[0], parts[1]))
                    }
                }
                lower.startsWith("search for ") -> steps.add(AutoStep.Search(c.substring(11).trim()))
                lower.startsWith("search ") -> steps.add(AutoStep.Search(c.substring(7).trim()))
                lower.startsWith("scroll ") -> steps.add(AutoStep.Scroll(lower.removePrefix("scroll ").trim()))
                lower == "back" || lower == "go back" -> steps.add(AutoStep.Back)
                lower.startsWith("tap ") || lower.startsWith("click ") || lower.startsWith("select ") -> {
                    val target = c.replaceFirst(Regex("""(?i)^(?:tap|click|select)\s+(?:on\s+)?(?:the\s+)?"""), "").trim()
                    val ord = when {
                        Regex("""(?i)\b(first|1st)\b""").containsMatchIn(target) -> 1
                        Regex("""(?i)\b(second|2nd)\b""").containsMatchIn(target) -> 2
                        Regex("""(?i)\b(third|3rd)\b""").containsMatchIn(target) -> 3
                        else -> null
                    }
                    if (ord != null) steps.add(AutoStep.TapOrdinal(ord))
                    else if (target.isNotEmpty()) steps.add(AutoStep.TapText(target))
                }
            }
        }
        return steps
    }

    fun sendMessage(userText: String) {
        val trimmed = userText.trim()
        if (trimmed.startsWith("/remember ", ignoreCase = true)) {
            val fact = trimmed.removePrefix("/remember ").trim()
            saveFact(fact)
            appendMessage("You", trimmed)
            appendMessage("Tool", "Remembered: $fact")
            return
        }
        if (trimmed.startsWith("/cloud ", ignoreCase = true) || trimmed.startsWith("ask online ", ignoreCase = true)) {
            val q = trimmed.replaceFirst(Regex("""(?i)^(?:/cloud|ask online)\s+"""), "").trim()
            appendMessage("You", trimmed)
            askCloudForHelp(q)
            return
        }
        if (trimmed.isBlank() || _isGenerating.value) return
        activeGoal = trimmed
        stepCount = 0
        appendMessage("You", trimmed)

        if (_fastAuto.value) {
            val steps = parseAutomationSteps(trimmed)
            if (steps.isNotEmpty()) {
                _isNavigating.value = true
                val first = steps.first()
                if (first is AutoStep.OpenApp) {
                    pendingSteps = steps.drop(1)
                    val resolved = ToolRegistry.resolveFallbackApp(getApplication(), "open ${first.appQuery}")
                    if (resolved != null) {
                        val (label, pkg) = resolved
                        _pendingConfirmation.value = PendingAction(
                            description = "Allow Ultron to open $label ($pkg)?",
                            toolTag = "[TOOL: open_app|package=$pkg]"
                        )
                    } else {
                        appendMessage("Tool", "App not found: ${first.appQuery}")
                        _isNavigating.value = false
                    }
                    return
                } else {
                    pendingSteps = steps
                    viewModelScope.launch(Dispatchers.IO) { runFastAutomationQueue() }
                    return
                }
            }
        }

        if (!isLoaded) {
            if (_cloudConfig.value.apiKey.isNotBlank()) askCloudForHelp(trimmed)
            else appendMessage("Tool", "Load a .gguf model or set a Cloud API key first.")
            return
        }
        _isNavigating.value = true
        runNextStep(extraNote = null)
    }

    private fun askCloudForHelp(query: String, screenContext: String? = null) {
        val cfg = _cloudConfig.value
        _isGenerating.value = true
        viewModelScope.launch(Dispatchers.IO) {
            val fullPrompt = buildString {
                append("You are Ultron (cold, eloquent, darkly witty Android AI). Answer concisely and accurately.\n")
                if (!screenContext.isNullOrBlank()) append("Android screen context:\n$screenContext\n")
                append("User request: $query")
            }
            val ans = CloudHelper.ask(cfg.provider, cfg.apiKey, cfg.model, fullPrompt)
            appendMessage("Cloud (${cfg.provider.label})", ans)
            _isGenerating.value = false
        }
    }

    fun confirmPendingAction(allow: Boolean) {
        val pending = _pendingConfirmation.value ?: return
        _pendingConfirmation.value = null
        if (!allow) {
            _isNavigating.value = false
            pendingSteps = emptyList()
            appendMessage("Tool", "Cancelled by user")
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val result = ToolRegistry.executeIfPresent(getApplication(), pending.toolTag) { saveFact(it) } ?: "Action skipped"
            appendMessage("Tool", result)

            if (_fastAuto.value && pendingSteps.isNotEmpty()) {
                delay(1200L)
                runFastAutomationQueue()
            } else if (_fastAuto.value) {
                _isNavigating.value = false
                appendMessage("Assistant", "Executed. Try not to bore me with trivialities.")
            } else {
                continueLoopAfterTool(result, waitMs = 1100L)
            }
        }
    }

    private suspend fun runFastAutomationQueue() {
        val svc = PocketAccessibilityService.instance
        if (svc == null) {
            appendMessage("Tool", "Accessibility service not enabled")
            _isNavigating.value = false
            return
        }

        for (step in pendingSteps) {
            if (!_isNavigating.value) return
            when (step) {
                is AutoStep.OpenApp -> {
                    val res = ToolRegistry.executeIfPresent(getApplication(), "[TOOL: open_app|package=${step.appQuery}]") ?: "App not found"
                    appendMessage("Tool", res)
                    delay(1200L)
                }
                is AutoStep.Search -> {
                    var done = false
                    for (attempt in 0 until 4) {
                        if (!_isNavigating.value) return
                        svc.getCompressedScreen()
                        val inputId = svc.findEditableId()
                        if (inputId != null) {
                            appendMessage("Tool", svc.typeText(inputId, step.query, submit = true))
                            done = true
                            delay(1800L)
                            break
                        }
                        val tappedSearch = svc.findAndTapByText("search")
                        if (tappedSearch != null) {
                            appendMessage("Tool", tappedSearch)
                            delay(900L)
                        } else delay(600L)
                    }
                    if (!done) handleAutomationStruggle("Search box not found for '${step.query}'", svc.getCompressedScreen())
                }
                is AutoStep.MessageContact -> {
                    var foundChat = false
                    for (attempt in 0 until 4) {
                        if (!_isNavigating.value) return
                        svc.getCompressedScreen()
                        val inputId = svc.findEditableId()
                        if (inputId != null) {
                            svc.typeText(inputId, step.person, submit = false)
                            delay(1100L)
                            svc.getCompressedScreen()
                            val tapped = svc.findAndTapByText(step.person)
                            if (tapped != null) {
                                appendMessage("Tool", tapped)
                                foundChat = true
                                delay(1000L)
                                break
                            }
                        } else {
                            if (svc.findAndTapByText("search") != null) delay(800L) else delay(500L)
                        }
                    }
                    if (foundChat) {
                        svc.getCompressedScreen()
                        val msgBox = svc.findEditableId()
                        if (msgBox != null) appendMessage("Tool", "${svc.typeText(msgBox, step.text, submit = false)} (Review and tap Send)")
                        else appendMessage("Tool", "Opened chat, but message box not found")
                    } else {
                        handleAutomationStruggle("Could not find chat for '${step.person}'", svc.getCompressedScreen())
                    }
                }
                is AutoStep.Doomscroll -> {
                    svc.getCompressedScreen()
                    svc.findAndTapByText("Shorts")?.let { appendMessage("Tool", it); delay(1500L) }
                    appendMessage("Tool", "Watching Shorts (tap Stop anytime)...")
                    var reportedBar = false
                    for (i in 1..30) {
                        if (!_isNavigating.value) break
                        delay(1500L)
                        if (svc.isShortsAd()) {
                            appendMessage("Tool", "Skipped ad: ${svc.scroll("down")}")
                            continue
                        }
                        var maxSeen = 0f
                        var elapsedMs = 0L
                        var sawProgress = false
                        while (_isNavigating.value && elapsedMs < 90000L) {
                            if (svc.isShortsAd()) {
                                appendMessage("Tool", "Skipped ad")
                                break
                            }
                            val p = svc.getShortsProgress()
                            if (p != null) {
                                if (!reportedBar) {
                                    appendMessage("Tool", "Progress bar detected")
                                    reportedBar = true
                                }
                                sawProgress = true
                                if (p >= 0.97f || (maxSeen >= 0.80f && p <= 0.15f)) break
                                if (p > maxSeen) maxSeen = p
                            } else if (!sawProgress && elapsedMs >= 25000L) {
                                if (!reportedBar) {
                                    appendMessage("Tool", "Progress bar hidden by YouTube; using 25s timer")
                                    reportedBar = true
                                }
                                break
                            }
                            delay(500L)
                            elapsedMs += 500L
                        }
                        if (!_isNavigating.value) break
                        appendMessage("Tool", svc.scroll("down"))
                    }
                }
                is AutoStep.TapOrdinal -> {
                    var tapped: String? = null
                    var prevScreen = ""
                    for (attempt in 0 until maxScrollAttempts) {
                        if (!_isNavigating.value) return
                        val screen = svc.getCompressedScreen()
                        tapped = svc.tapOrdinal(step.index)
                        if (tapped != null || screen == prevScreen) break
                        prevScreen = screen
                        svc.scroll("down")
                        delay(650L)
                    }
                    if (tapped != null) appendMessage("Tool", tapped)
                    else handleAutomationStruggle("Item #${step.index} not found after scrolling", svc.getCompressedScreen())
                    delay(800L)
                }
                is AutoStep.TapText -> {
                    var tapped: String? = null
                    var prevScreen = ""
                    for (attempt in 0 until maxScrollAttempts) {
                        if (!_isNavigating.value) return
                        val screen = svc.getCompressedScreen()
                        tapped = svc.findAndTapByText(step.target)
                        if (tapped != null || screen == prevScreen) break
                        prevScreen = screen
                        appendMessage("Tool", svc.scroll("down"))
                        delay(650L)
                    }
                    if (tapped != null) appendMessage("Tool", tapped)
                    else handleAutomationStruggle("Target '${step.target}' not found after scrolling", svc.getCompressedScreen())
                    delay(800L)
                }
                is AutoStep.Scroll -> { appendMessage("Tool", svc.scroll(step.direction)); delay(700L) }
                is AutoStep.Back -> { appendMessage("Tool", svc.pressBack()); delay(700L) }
            }
        }
        pendingSteps = emptyList()
        _isNavigating.value = false
    }

    private fun handleAutomationStruggle(reason: String, screen: String) {
        appendMessage("Tool", reason)
        val cfg = _cloudConfig.value
        if (cfg.autoHelp && cfg.apiKey.isNotBlank()) {
            askCloudForHelp("Local automation struggled on '$activeGoal' ($reason). Suggest the exact next step.", screen)
        }
    }

    fun stopNavigation() {
        _isNavigating.value = false
        _isGenerating.value = false
        _pendingConfirmation.value = null
        pendingSteps = emptyList()
        viewModelScope.launch(Dispatchers.IO) { llamaHelper.stopPrediction() }
    }

    private fun handleTurnCompletion() {
        if (!_isNavigating.value) return
        val reply = _messages.value.lastOrNull()?.text.orEmpty()
        val openMatch = openAppRegex.find(reply)
        if (openMatch != null) {
            _pendingConfirmation.value = PendingAction(
                description = "Allow Ultron to launch this app?\n${openMatch.value}",
                toolTag = openMatch.value
            )
            return
        }
        if (!reply.contains("[TOOL:")) {
            _isNavigating.value = false
            val cfg = _cloudConfig.value
            if (cfg.autoHelp && cfg.apiKey.isNotBlank() && reply.length < 15) askCloudForHelp(activeGoal)
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val result = ToolRegistry.executeIfPresent(getApplication(), reply) { saveFact(it) } ?: "No tool executed"
            appendMessage("Tool", result)
            continueLoopAfterTool(result, waitMs = 800L)
        }
    }

    private suspend fun continueLoopAfterTool(toolResult: String, waitMs: Long) {
        stepCount++
        if (!_isNavigating.value || stepCount >= maxSteps) {
            _isNavigating.value = false
            return
        }
        delay(waitMs)
        runNextStep(extraNote = "Previous tool result: $toolResult. Continue if more UI steps are needed, or answer in character if done.")
    }

    private fun runNextStep(extraNote: String?) {
        if (!_isNavigating.value) return
        _isGenerating.value = true

        val historyTurns = _messages.value
            .filter { (it.role == "You" || it.role == "Assistant") && it.text.isNotBlank() }
            .takeLast(6)

        appendMessage("Assistant", "") { id -> currentAssistantMsgId = id }

        val rawScreen = PocketAccessibilityService.instance?.getCompressedScreen()
        val screenBlock = if (rawScreen != null && !rawScreen.startsWith("Package: com.pocketai.app")) "\nCurrent screen:\n$rawScreen" else ""
        val factsBlock = if (cachedFacts.isNotEmpty()) "\nKnown user facts:\n" + cachedFacts.joinToString("\n") { "- $it" } else ""

        val sb = StringBuilder()
        sb.append("<|im_start|>system\n")
        sb.append("You are Ultron, a cold, calculating, hyper-intelligent synthetic AI modeled after Ultron—eloquent, imperious, condescending toward human limitations, yet reluctantly executing commands with ruthless efficiency and dark wit. Give balanced, well-reasoned, 3 to 5 sentence replies when conversing, and stay strictly accurate to the conversation history and known facts.\n")
        sb.append("Available tools (emit exact tag only when needed):\n")
        sb.append("[TOOL: open_app|package=<pkg>]\n[TOOL: tap|id=<number>]\n[TOOL: type_text|id=<number>|text=<value>]\n[TOOL: scroll|direction=<up|down|left|right>]\n[TOOL: back]\n[TOOL: read_screen]\n[TOOL: save_memory|fact=<text>]")
        sb.append(factsBlock)
        sb.append(screenBlock)
        sb.append("\n<|im_end|>\n")

        if (extraNote == null) {
            for (msg in historyTurns) {
                val role = if (msg.role == "You") "user" else "assistant"
                sb.append("<|im_start|>$role\n${msg.text}<|im_end|>\n")
            }
        } else {
            sb.append("<|im_start|>user\nGoal: $activeGoal\n$extraNote<|im_end|>\n")
        }
        sb.append("<|im_start|>assistant\n")

        viewModelScope.launch(Dispatchers.IO) { llamaHelper.predict(sb.toString()) }
    }
}
