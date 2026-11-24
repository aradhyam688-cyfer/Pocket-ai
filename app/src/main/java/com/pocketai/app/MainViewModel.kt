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
    private val maxSteps = 5
    private val openAppRegex = Regex("""\[TOOL:\s*open_app\|[^\]]+\]""")
    private val tapGoalRegex = Regex("""(?i)\btap\s+([a-z0-9 &._+-]+)""")
    private val searchGoalRegex = Regex("""(?i)\bsearch\s+(.+)""")

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
            val resolved = ToolRegistry.resolveFallbackApp(getApplication(), trimmed)
            if (resolved != null) {
                _isNavigating.value = true
                val (label, pkg) = resolved
                _pendingConfirmation.value = PendingAction(
                    description = "Allow Ultron to open $label ($pkg)?",
                    toolTag = "[TOOL: open_app|package=$pkg]"
                )
                return
            }
            if (tapGoalRegex.containsMatchIn(trimmed) || searchGoalRegex.containsMatchIn(trimmed)) {
                _isNavigating.value = true
                viewModelScope.launch(Dispatchers.IO) { runFastAutomationLoop() }
                return
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
            appendMessage("Tool", "Cancelled by user")
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val result = ToolRegistry.executeIfPresent(getApplication(), pending.toolTag) { saveFact(it) }
                ?: "Action skipped"
            appendMessage("Tool", result)

            if (_fastAuto.value && (tapGoalRegex.containsMatchIn(activeGoal) || searchGoalRegex.containsMatchIn(activeGoal))) {
                delay(1000L)
                runFastAutomationLoop()
            } else if (_fastAuto.value) {
                _isNavigating.value = false
                appendMessage("Assistant", "Executed. Try not to bore me with trivialities.")
            } else {
                continueLoopAfterTool(result, waitMs = 1100L)
            }
        }
    }

    private suspend fun runFastAutomationLoop() {
        val svc = PocketAccessibilityService.instance
        if (svc == null) {
            appendMessage("Tool", "Accessibility service not enabled")
            _isNavigating.value = false
            return
        }

        val searchQuery = searchGoalRegex.find(activeGoal)?.groupValues?.get(1)?.trim()
        val tapTarget = tapGoalRegex.find(activeGoal)?.groupValues?.get(1)?.trim()

        for (i in 0 until maxSteps) {
            if (!_isNavigating.value) return
            svc.getCompressedScreen()

            if (searchQuery != null) {
                val inputId = svc.findEditableId()
                if (inputId != null) {
                    val res = svc.typeText(inputId, searchQuery, submit = true)
                    appendMessage("Tool", res)
                    _isNavigating.value = false
                    return
                }
                val tappedSearch = svc.findAndTapByText("search")
                if (tappedSearch != null) {
                    appendMessage("Tool", tappedSearch)
                    delay(800L)
                    continue
                }
            }

            if (tapTarget != null) {
                val tapped = svc.findAndTapByText(tapTarget)
                if (tapped != null) {
                    appendMessage("Tool", tapped)
                    _isNavigating.value = false
                    return
                }
                val scrolled = svc.scrollDown()
                appendMessage("Tool", scrolled)
                if (scrolled == "Reached bottom" || scrolled == "No scrollable view found") break
                delay(700L)
                continue
            }
            break
        }
        _isNavigating.value = false
    }

    fun stopNavigation() {
        _isNavigating.value = false
        _isGenerating.value = false
        _pendingConfirmation.value = null
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
