package com.pocketai.app

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import org.nehuatl.llamacpp.LlamaHelper

data class ChatMessage(val role: String, val text: String)
data class PendingAction(val description: String, val toolTag: String)
data class RecentModel(val name: String, val uri: String)
data class CloudConfig(
    val provider: CloudProvider = CloudProvider.GEMINI,
    val apiKey: String = "",
    val model: String = CloudProvider.GEMINI.defaultModel,
    val autoHelp: Boolean = true
)
data class VoiceConfig(
    val voiceName: String = "",
    val pitch: Float = 0.85f,
    val rate: Float = 0.95f,
    val wakeMode: Boolean = false
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val db = AppDatabase.get(application)
    private val messageDao = db.messageDao()
    private val memoryDao = db.memoryDao()
    private val taskDao = db.taskDao()
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

    private val _voiceConfig = MutableStateFlow(loadVoiceConfig())
    val voiceConfig = _voiceConfig.asStateFlow()

    private val _recentModels = MutableStateFlow(loadRecentModels())
    val recentModels = _recentModels.asStateFlow()

    private val _autoLoadLast = MutableStateFlow(prefs.getBoolean("autoLoadLast", true))
    val autoLoadLast = _autoLoadLast.asStateFlow()

    private val _activeTask = MutableStateFlow<AgentTask?>(null)
    val activeTask = _activeTask.asStateFlow()

    private val _status = MutableStateFlow("No model loaded")
    val status = _status.asStateFlow()

    private val _isGenerating = MutableStateFlow(false)
    val isGenerating = _isGenerating.asStateFlow()

    private val _isNavigating = MutableStateFlow(false)
    val isNavigating = _isNavigating.asStateFlow()

    private val _pendingConfirmation = MutableStateFlow<PendingAction?>(null)
    val pendingConfirmation = _pendingConfirmation.asStateFlow()

    private val _choicePrompt = MutableStateFlow<List<UiElement>>(emptyList())
    val choicePrompt = _choicePrompt.asStateFlow()
    private var pendingChoiceContinuation: (() -> Unit)? = null

    private var isLoaded = false
    private var activeGoal = ""
    private var stepCount = 0
    private var lastToolTag = ""
    private var consecutiveFailures = 0
    private var lastSearchQuery = ""
    private var currentAssistantMsgId: Long? = null
    private var cachedFacts: List<String> = emptyList()
    private val maxSteps = 6
    private val openAppRegex = Regex("""\[TOOL:\s*open_app\|[^\]]+\]""")

    init {
        viewModelScope.launch(Dispatchers.IO) {
            _messages.value = messageDao.getAll().map { ChatMessage(it.role, it.text) }
            cachedFacts = memoryDao.getAll().map { it.fact }
            _memoryCount.value = cachedFacts.size
            if (_autoLoadLast.value) {
                _recentModels.value.firstOrNull()?.let { loadModel(it.uri, it.name) }
            }
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

    private fun loadRecentModels(): List<RecentModel> {
        val raw = prefs.getString("recentModels", "[]") ?: "[]"
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                RecentModel(o.getString("name"), o.getString("uri"))
            }
        }.getOrDefault(emptyList())
    }

    private fun saveRecentModel(name: String, uri: String) {
        val updated = (listOf(RecentModel(name, uri)) + _recentModels.value.filter { it.uri != uri }).take(5)
        _recentModels.value = updated
        val arr = JSONArray()
        updated.forEach { arr.put(JSONObject().put("name", it.name).put("uri", it.uri)) }
        prefs.edit().putString("recentModels", arr.toString()).apply()
    }

    fun setAutoLoadLast(enabled: Boolean) {
        _autoLoadLast.value = enabled
        prefs.edit().putBoolean("autoLoadLast", enabled).apply()
    }

    private fun loadVoiceConfig(): VoiceConfig {
        return VoiceConfig(
            voiceName = prefs.getString("voiceName", "") ?: "",
            pitch = prefs.getFloat("voicePitch", 0.85f),
            rate = prefs.getFloat("voiceRate", 0.95f),
            wakeMode = prefs.getBoolean("wakeMode", false)
        )
    }

    fun saveVoiceConfig(voiceName: String, pitch: Float, rate: Float, wakeMode: Boolean) {
        prefs.edit()
            .putString("voiceName", voiceName)
            .putFloat("voicePitch", pitch)
            .putFloat("voiceRate", rate)
            .putBoolean("wakeMode", wakeMode)
            .apply()
        _voiceConfig.value = VoiceConfig(voiceName, pitch, rate, wakeMode)
    }

    private fun updateTask(transform: (AgentTask) -> AgentTask) {
        val current = _activeTask.value ?: return
        val updated = transform(current)
        _activeTask.value = updated
        viewModelScope.launch(Dispatchers.IO) {
            taskDao.upsert(
                TaskEntity(
                    id = updated.id,
                    instruction = updated.originalInstruction,
                    objective = updated.objective,
                    status = updated.status.name,
                    currentStep = updated.currentStepIndex,
                    totalSteps = updated.plan.size,
                    lastObservation = updated.observations.lastOrNull() ?: ""
                )
            )
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

    fun toggleFastAuto() { _fastAuto.value = !_fastAuto.value }

    fun loadModel(uriString: String, displayName: String? = null) {
        val label = displayName ?: Uri.parse(uriString).lastPathSegment?.substringAfterLast('/') ?: "model.gguf"
        _status.value = "Loading $label..."
        saveRecentModel(label, uriString)
        viewModelScope.launch(Dispatchers.IO) {
            llamaHelper.load(path = uriString, contextLength = 2048) {
                isLoaded = true
                _status.value = "Ready ($label)"
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
    private fun buildPlan(input: String): List<PlannedStep> {
        val lowerAll = input.lowercase().trim()

        // 1. Screen analysis ("solve question 12 from the screen", "explain bernoulli theorem in this question")
        if (Regex("""(?i)\b(?:from the screen|on this screen|in this question|this screen|solve question)\b""").containsMatchIn(lowerAll)) {
            return listOf(PlannedStep(TaskAction.ANALYZE_SCREEN, target = input))
        }

        // 2. App home navigation ("go home in whatsapp", "back to youtube home")
        if (Regex("""(?i)\b(?:go|back|return)\b.*\bhome\b""").containsMatchIn(lowerAll)) {
            val appMatch = Regex("""(?i)\b(?:in|on|to)\s+([a-z0-9._+-]+)(?:\s+home)?$""").find(lowerAll)
            return listOf(PlannedStep(TaskAction.GO_APP_HOME, target = appMatch?.groupValues?.get(1) ?: ""))
        }

        // 3. Web search & narrate ("who is jackie shroff", "what is seahorse", "google ...")
        val selfQuestion = Regex("""(?i)\b(?:you|your|ultron)\b""").containsMatchIn(lowerAll)
        if (!selfQuestion && Regex("""(?i)^(?:who is|who was|what is|what are|where is|when is|google|look up|search web for)\s+(.+)""").containsMatchIn(lowerAll)) {
            val q = lowerAll.replaceFirst(Regex("""(?i)^(?:google|look up|search web for)\s+"""), "").trim()
            return listOf(PlannedStep(TaskAction.WEB_SEARCH_NARRATE, target = q))
        }

        // 4. WhatsApp PDF retrieval
        val pdfIntent = Regex("""(?i)\b(?:pdf|document)\b""").containsMatchIn(input) &&
            Regex("""(?i)\b(?:whatsapp|wa)\b""").containsMatchIn(input)
        if (pdfIntent) {
            val personMatch = Regex("""(?i)\b(?:with|from|by)\s+([a-zA-Z0-9 ._-]+?)(?=\s*(?:,|\band\b|\bthen\b|\bsent\b|\bon\b|\bin\b|$))""").find(input)
            val person = personMatch?.groupValues?.get(1)?.trim()
            if (!person.isNullOrBlank()) {
                return listOf(
                    PlannedStep(TaskAction.OPEN_APP, target = "WhatsApp"),
                    PlannedStep(TaskAction.TAP_TARGET, target = person),
                    PlannedStep(TaskAction.FIND_OPEN_PDF)
                )
            }
        }

        // 5. Standalone "message rahul it is hot here today" (defaults to WhatsApp)
        if (Regex("""(?i)^(?:message|text|tell|whatsapp)\s+""").containsMatchIn(lowerAll)) {
            val body = input.replaceFirst(Regex("""(?i)^(?:message|text|tell|whatsapp)\s+"""), "").trim()
            val qMatch = Regex("""^(.+?)\s+['"](.+?)['"]$""").find(body)
            val sMatch = Regex("""(?i)^(.+?)\s+(?:saying|that)\s+(.+)$""").find(body)
            val (person, msg) = when {
                qMatch != null -> qMatch.groupValues[1].trim() to qMatch.groupValues[2].trim()
                sMatch != null -> sMatch.groupValues[1].trim() to sMatch.groupValues[2].trim()
                else -> {
                    val parts = body.split(" ", limit = 2)
                    if (parts.size == 2) parts[0] to parts[1] else "" to ""
                }
            }
            if (person.isNotBlank() && msg.isNotBlank()) {
                return listOf(
                    PlannedStep(TaskAction.OPEN_APP, target = "WhatsApp"),
                    PlannedStep(TaskAction.MESSAGE_CONTACT, target = person, extra = msg)
                )
            }
        }

        // 6. Chained multi-step commands
        val clauses = input.split(Regex("""(?i)\s*(?:,|\band\b|\bthen\b|\s+(?=(?:tap|click|select|search|scroll|back|doomscroll|message)\b))\s*"""))
            .map { it.trim() }.filter { it.isNotEmpty() }

        val steps = mutableListOf<PlannedStep>()
        for (c in clauses) {
            val lower = c.lowercase()
            when {
                lower.startsWith("open ") -> steps.add(PlannedStep(TaskAction.OPEN_APP, target = c.substring(5).trim()))
                lower.contains("doomscroll") -> steps.add(PlannedStep(TaskAction.DOOMSCROLL))
                lower.startsWith("message ") -> {
                    val body = c.substring(8).trim()
                    val qMatch = Regex("""^(.+?)\s+['"](.+?)['"]$""").find(body)
                    val sMatch = Regex("""(?i)^(.+?)\s+(?:saying|text|that)\s+(.+)$""").find(body)
                    if (qMatch != null) steps.add(PlannedStep(TaskAction.MESSAGE_CONTACT, qMatch.groupValues[1].trim(), qMatch.groupValues[2].trim()))
                    else if (sMatch != null) steps.add(PlannedStep(TaskAction.MESSAGE_CONTACT, sMatch.groupValues[1].trim(), sMatch.groupValues[2].trim()))
                    else {
                        val parts = body.split(" ", limit = 2)
                        if (parts.size == 2) steps.add(PlannedStep(TaskAction.MESSAGE_CONTACT, parts[0], parts[1]))
                    }
                }
                lower.startsWith("search for ") -> steps.add(PlannedStep(TaskAction.SEARCH, target = c.substring(11).trim()))
                lower.startsWith("search ") -> steps.add(PlannedStep(TaskAction.SEARCH, target = c.substring(7).trim()))
                lower.startsWith("scroll ") -> steps.add(PlannedStep(TaskAction.SCROLL, target = lower.removePrefix("scroll ").trim()))
                lower == "back" || lower == "go back" -> steps.add(PlannedStep(TaskAction.BACK))
                lower.startsWith("tap ") || lower.startsWith("click ") || lower.startsWith("select ") -> {
                    val target = c.replaceFirst(Regex("""(?i)^(?:tap|click|select)\s+(?:on\s+)?(?:the\s+)?"""), "").trim()
                    val ord = when {
                        Regex("""(?i)\b(first|1st)\b""").containsMatchIn(target) -> "1"
                        Regex("""(?i)\b(second|2nd)\b""").containsMatchIn(target) -> "2"
                        Regex("""(?i)\b(third|3rd)\b""").containsMatchIn(target) -> "3"
                        else -> null
                    }
                    if (ord != null) steps.add(PlannedStep(TaskAction.TAP_ORDINAL, target = ord))
                    else if (target.isNotEmpty()) steps.add(PlannedStep(TaskAction.TAP_TARGET, target = target))
                }
            }
        }
        return steps
    }

    fun sendMessage(userText: String) {
        val trimmed = userText.trim()
        if (trimmed.startsWith("/remember ", ignoreCase = true)) {
            val fact = trimmed.removePrefix("/remember ").trim()
            saveFact(fact); appendMessage("You", trimmed); appendMessage("Tool", "Remembered: $fact"); return
        }
        if (trimmed.startsWith("/cloud ", ignoreCase = true) || trimmed.startsWith("ask online ", ignoreCase = true)) {
            val q = trimmed.replaceFirst(Regex("""(?i)^(?:/cloud|ask online)\s+"""), "").trim()
            appendMessage("You", trimmed); askCloudForHelp(q); return
        }
        if (trimmed.isBlank() || _isGenerating.value) return
        activeGoal = trimmed; stepCount = 0; lastToolTag = ""; consecutiveFailures = 0; lastSearchQuery = ""
        appendMessage("You", trimmed)

        if (_fastAuto.value) {
            val rawPlan = buildPlan(trimmed)
            if (rawPlan.isNotEmpty()) {
                val validation = PlanValidator.validate(rawPlan)
                if (!validation.isValid) { appendMessage("Tool", "Plan rejected: ${validation.error}"); return }
                _activeTask.value = AgentTask(
                    originalInstruction = trimmed,
                    objective = "Execute ${validation.validatedPlan.size}-step workflow",
                    plan = validation.validatedPlan,
                    status = TaskStatus.RUNNING
                )
                _isNavigating.value = true
                viewModelScope.launch(Dispatchers.IO) { executeCurrentTask() }
                return
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

    fun selectChoice(element: UiElement) {
        _choicePrompt.value = emptyList()
        val cont = pendingChoiceContinuation; pendingChoiceContinuation = null
        viewModelScope.launch(Dispatchers.IO) {
            val res = PocketAccessibilityService.instance?.tap(element.id) ?: "Accessibility off"
            appendMessage("Tool", res)
            updateTask { it.copy(status = TaskStatus.RUNNING, observations = it.observations + res) }
            delay(900L)
            cont?.invoke()
        }
    }

    fun dismissChoices() {
        _choicePrompt.value = emptyList(); pendingChoiceContinuation = null; _isNavigating.value = false
        updateTask { it.copy(status = TaskStatus.CANCELLED) }
        appendMessage("Tool", "Selection cancelled by user")
    }

    fun pauseTask() {
        updateTask { it.copy(status = TaskStatus.PAUSED, history = it.history + "Paused by user") }
        _isNavigating.value = false
    }

    fun resumeTask() {
        val task = _activeTask.value ?: return
        if (task.status != TaskStatus.PAUSED) return
        updateTask { it.copy(status = TaskStatus.RUNNING, history = it.history + "Resumed") }
        _isNavigating.value = true
        viewModelScope.launch(Dispatchers.IO) { executeCurrentTask() }
    }
    private fun runNextStep(extraNote: String?) {
        if (!_isNavigating.value) return
        _isGenerating.value = true
        val historyTurns = _messages.value.filter { (it.role == "You" || it.role == "Assistant") && it.text.isNotBlank() }.takeLast(6)
        appendMessage("Assistant", "") { id -> currentAssistantMsgId = id }
        val rawScreen = PocketAccessibilityService.instance?.getCompressedScreen()
        val screenBlock = if (rawScreen != null && !rawScreen.startsWith("Package: com.pocketai.app")) "\nCurrent screen:\n$rawScreen" else ""
        val factsBlock = if (cachedFacts.isNotEmpty()) "\nKnown user facts:\n" + cachedFacts.joinToString("\n") { "- $it" } else ""
        val sb = StringBuilder("<|im_start|>system\nYou are Ultron, a cold, calculating, hyper-intelligent synthetic AI modeled after Ultron—eloquent, imperious, condescending toward human limitations, yet reluctantly executing commands with ruthless efficiency and dark wit. Give balanced, well-reasoned, 3 to 5 sentence replies when conversing, and stay strictly accurate to the conversation history and known facts.\nAvailable tools (emit exact tag only when needed):\n[TOOL: open_app|package=<pkg>]\n[TOOL: tap|id=<number>]\n[TOOL: type_text|id=<number>|text=<value>]\n[TOOL: scroll|direction=<up|down|left|right>]\n[TOOL: back]\n[TOOL: read_screen]\n[TOOL: save_memory|fact=<text>]$factsBlock$screenBlock\n<|im_end|>\n")
        if (extraNote == null) {
            for (msg in historyTurns) sb.append("<|im_start|>${if (msg.role == "You") "user" else "assistant"}\n${msg.text}<|im_end|>\n")
        } else sb.append("<|im_start|>user\nGoal: $activeGoal\n$extraNote<|im_end|>\n")
        sb.append("<|im_start|>assistant\n")
        viewModelScope.launch(Dispatchers.IO) { llamaHelper.predict(sb.toString()) }
    }
}
