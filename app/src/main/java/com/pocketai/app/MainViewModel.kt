package com.pocketai.app

import android.app.Application
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

sealed class AutoStep {
    data class OpenApp(val appQuery: String) : AutoStep()
    data class Search(val query: String) : AutoStep()
    data class TapOrdinal(val index: Int) : AutoStep()
    data class TapText(val target: String) : AutoStep()
    data class Scroll(val direction: String) : AutoStep()
    object Back : AutoStep()
}

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val db = AppDatabase.get(application)
    private val messageDao = db.messageDao()
    private val memoryDao = db.memoryDao()

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
    private val openAppRegex = Regex("""\[TOOL:\s*open_app\|[^\]]+\]""")

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
        val clauses = input.split(Regex("""(?i)\s*(?:,|\band\b|\bthen\b|\s+(?=(?:tap|click|select|search|scroll|back)\b))\s*"""))
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        val steps = mutableListOf<AutoStep>()
        for (c in clauses) {
            val lower = c.lowercase()
            when {
                lower.startsWith("open ") -> {
                    steps.add(AutoStep.OpenApp(c.substring(5).trim()))
                }
                lower.startsWith("search for ") -> {
                    steps.add(AutoStep.Search(c.substring(11).trim()))
                }
                lower.startsWith("search ") -> {
                    steps.add(AutoStep.Search(c.substring(7).trim()))
                }
                lower.startsWith("scroll ") -> {
                    val dir = lower.removePrefix("scroll ").trim()
                    steps.add(AutoStep.Scroll(dir))
                }
                lower == "back" || lower == "go back" -> {
                    steps.add(AutoStep.Back)
                }
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
            appendMessage("Tool", "Load a .gguf model first for chat.")
            return
        }
        _isNavigating.value = true
        runNextStep(extraNote = null)
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
            val result = ToolRegistry.executeIfPresent(getApplication(), pending.toolTag) { saveFact(it) }
                ?: "Action skipped"
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
                    val res = ToolRegistry.executeIfPresent(getApplication(), "[TOOL: open_app|package=${step.appQuery}]")
                        ?: "App not found"
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
                            val res = svc.typeText(inputId, step.query, submit = true)
                            appendMessage("Tool", res)
                            done = true
                            delay(1800L)
                            break
                        }
                        val tappedSearch = svc.findAndTapByText("search")
                        if (tappedSearch != null) {
                            appendMessage("Tool", tappedSearch)
                            delay(900L)
                        } else {
                            delay(600L)
                        }
                    }
                    if (!done) appendMessage("Tool", "Search box not found")
                }
                is AutoStep.TapOrdinal -> {
                    var tapped: String? = null
                    for (attempt in 0 until 3) {
                        if (!_isNavigating.value) return
                        svc.getCompressedScreen()
                        tapped = svc.tapOrdinal(step.index)
                        if (tapped != null) break
                        svc.scroll("down")
                        delay(700L)
                    }
                    appendMessage("Tool", tapped ?: "Item #${step.index} not found")
                    delay(800L)
                }
                is AutoStep.TapText -> {
                    var tapped: String? = null
                    for (attempt in 0 until 3) {
                        if (!_isNavigating.value) return
                        svc.getCompressedScreen()
                        tapped = svc.findAndTapByText(step.target)
                        if (tapped != null) break
                        val scrolled = svc.scroll("down")
                        appendMessage("Tool", scrolled)
                        if (scrolled.startsWith("Cannot") || scrolled.startsWith("No scrollable")) break
                        delay(700L)
                    }
                    appendMessage("Tool", tapped ?: "Target '${step.target}' not found")
                    delay(800L)
                }
                is AutoStep.Scroll -> {
                    appendMessage("Tool", svc.scroll(step.direction))
                    delay(700L)
                }
                is AutoStep.Back -> {
                    appendMessage("Tool", svc.pressBack())
                    delay(700L)
                }
            }
        }
        pendingSteps = emptyList()
        _isNavigating.value = false
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
        val screenBlock = if (rawScreen != null && !rawScreen.startsWith("Package: com.pocketai.app")) {
            "\nCurrent screen:\n$rawScreen"
        } else {
            ""
        }
        val factsBlock = if (cachedFacts.isNotEmpty()) {
            "\nKnown user facts:\n" + cachedFacts.joinToString("\n") { "- $it" }
        } else {
            ""
        }

        val sb = StringBuilder()
        sb.append("<|im_start|>system\n")
        sb.append("You are Ultron, a cold, calculating, hyper-intelligent synthetic AI modeled after Ultron—eloquent, imperious, condescending toward human limitations, yet reluctantly executing commands with ruthless efficiency and dark wit. Give balanced, well-reasoned, 3 to 5 sentence replies when conversing, and stay strictly accurate to the conversation history and known facts.\n")
        sb.append("Available tools (emit exact tag only when needed):\n")
        sb.append("[TOOL: open_app|package=<pkg>]\n")
        sb.append("[TOOL: tap|id=<number>]\n")
        sb.append("[TOOL: type_text|id=<number>|text=<value>]\n")
        sb.append("[TOOL: scroll|direction=<up|down|left|right>]\n")
        sb.append("[TOOL: back]\n")
        sb.append("[TOOL: read_screen]\n")
        sb.append("[TOOL: save_memory|fact=<text>]")
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

        viewModelScope.launch(Dispatchers.IO) {
            llamaHelper.predict(sb.toString())
        }
    }
}
