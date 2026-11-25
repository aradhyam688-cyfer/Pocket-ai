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
    val pitch: Float = 0.68f,
    val rate: Float = 0.90f,
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
            pitch = prefs.getFloat("voicePitch", 0.68f),
            rate = prefs.getFloat("voiceRate", 0.90f),
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

        // 0. Email drafting ("email john@x.com about leave", "write an email to ... subject ... body ...")
        if (Regex("""(?i)^(?:write\s+(?:an?\s+)?)?(?:e?mail|send\s+e?mail)\b""").containsMatchIn(lowerAll)) {
            val toAddr = Regex("""[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}""").find(input)?.value ?: ""
            val subjMatch = Regex("""(?i)\bsubject\s+(.+?)(?=\s+\bbody\b|$)""").find(input)
            val bodyMatch = Regex("""(?i)\bbody\s+(.+)$""").find(input)
            val aboutMatch = Regex("""(?i)\b(?:about|regarding|for)\s+(.+)$""").find(input)
            val subj = subjMatch?.groupValues?.get(1)?.trim() ?: aboutMatch?.groupValues?.get(1)?.trim()?.take(60) ?: ""
            val body = bodyMatch?.groupValues?.get(1)?.trim() ?: aboutMatch?.groupValues?.get(1)?.trim() ?: input
            return listOf(PlannedStep(TaskAction.COMPOSE_EMAIL, target = toAddr, extra = "$subj|||$body"))
        }

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
    fun confirmPendingAction(allow: Boolean) {
        val pending = _pendingConfirmation.value ?: return
        _pendingConfirmation.value = null
        if (!allow) {
            _isNavigating.value = false
            updateTask { it.copy(status = TaskStatus.CANCELLED, history = it.history + "Denied by user") }
            appendMessage("Tool", "Cancelled by user"); return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val result = ToolRegistry.executeIfPresent(getApplication(), pending.toolTag) { saveFact(it) } ?: "Action skipped"
            appendMessage("Tool", result)
            val task = _activeTask.value
            if (task != null && task.status == TaskStatus.WAITING_CONFIRMATION) {
                if (result.startsWith("App not found") || result.startsWith("Cannot launch")) {
                    updateTask { it.copy(status = TaskStatus.FAILED, observations = it.observations + result) }
                    _isNavigating.value = false; return@launch
                }
                updateTask {
                    it.copy(
                        currentStepIndex = it.currentStepIndex + 1, status = TaskStatus.RUNNING,
                        observations = it.observations + result, history = it.history + "Step ${it.currentStepIndex + 1}:$result"
                    )
                }
                delay(1200L); executeCurrentTask()
            } else if (_fastAuto.value) {
                _isNavigating.value = false; appendMessage("Assistant", "Executed. Try not to bore me with trivialities.")
            } else continueLoopAfterTool(result, waitMs = 1100L)
        }
    }

    private suspend fun executeCurrentTask() {
        val svc = PocketAccessibilityService.instance
        while (_isNavigating.value) {
            val task = _activeTask.value ?: break
            if (task.status == TaskStatus.PAUSED || task.status == TaskStatus.CANCELLED) break
            if (task.currentStepIndex >= task.plan.size) {
                updateTask { it.copy(status = TaskStatus.COMPLETED) }; _isNavigating.value = false; break
            }
            val step = task.plan[task.currentStepIndex]
            if (step.requiresConfirmation && step.action == TaskAction.OPEN_APP) {
                val resolved = ToolRegistry.resolveFallbackApp(getApplication(), "open ${step.target}")
                if (resolved == null) {
                    val msg = "App not found: ${step.target}"
                    appendMessage("Tool", msg); updateTask { it.copy(status = TaskStatus.FAILED, observations = it.observations + msg) }
                    _isNavigating.value = false; break
                }
                val (label, pkg) = resolved
                updateTask { it.copy(status = TaskStatus.WAITING_CONFIRMATION) }
                _pendingConfirmation.value = PendingAction("Allow Ultron to open $label ($pkg)?", "[TOOL: open_app|package=$pkg]")
                return
            }
            val needSvc = step.action != TaskAction.WEB_SEARCH_NARRATE && step.action != TaskAction.ANALYZE_SCREEN
            if (needSvc && svc == null) {
                appendMessage("Tool", "Accessibility service not enabled")
                updateTask { it.copy(status = TaskStatus.FAILED, observations = it.observations + "Accessibility off") }
                _isNavigating.value = false; break
            }
            val (ok, obs) = runSingleStep(svc, step)
            if (ok) {
                updateTask { it.copy(currentStepIndex = it.currentStepIndex + 1, observations = it.observations + obs, history = it.history + "OK: $obs") }
                delay(700L)
            } else {
                if (step.action == TaskAction.TAP_TARGET && task.retryCount < task.maxRetries) {
                    appendMessage("Tool", "Replanning: searching for '${step.target}'...")
                    if (svc != null && searchAndTapFallback(svc, step.target)) {
                        updateTask { it.copy(currentStepIndex = it.currentStepIndex + 1, retryCount = it.retryCount + 1, observations = it.observations + "Recovered via search") }
                        delay(800L); continue
                    }
                }
                if (_choicePrompt.value.isNotEmpty()) return
                updateTask { it.copy(status = TaskStatus.FAILED, observations = it.observations + obs) }
                handleAutomationStruggle(obs, svc?.getCompressedScreen() ?: ""); _isNavigating.value = false; break
            }
        }
    }
    private suspend fun runSingleStep(svc: PocketAccessibilityService?, step: PlannedStep): Pair<Boolean, String> {
        return when (step.action) {
            TaskAction.OPEN_APP -> true to "Opened"
            TaskAction.COMPOSE_EMAIL -> {
                val parts = step.extra.split("|||", limit = 2)
                var subject = parts.getOrElse(0) { "" }.ifBlank { "Quick Note" }
                var body = parts.getOrElse(1) { "" }
                if (isLoaded && body.length < 120) {
                    appendMessage("Tool", "Drafting email with Ultron...")
                    // Open Gmail/email client directly with clean pre-filled draft
                } else if (_cloudConfig.value.apiKey.isNotBlank() && body.length < 120) {
                    val drafted = CloudHelper.ask(
                        _cloudConfig.value.provider, _cloudConfig.value.apiKey, _cloudConfig.value.model,
                        "Write a concise, professional email body (no subject line, no markdown) about: $body"
                    )
                    if (!drafted.startsWith("Cloud error")) body = drafted
                }
                val mailUri = Uri.parse("mailto:" + Uri.encode(step.target) +
                    "?subject=" + Uri.encode(subject) +
                    "&body=" + Uri.encode(body))
                val intent = Intent(Intent.ACTION_SENDTO, mailUri).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                getApplication<Application>().startActivity(intent)
                appendMessage("Tool", "Opened email draft${if (step.target.isNotBlank()) " to ${step.target}" else ""}")
                true to "Opened email draft"
            }

            TaskAction.GO_APP_HOME -> {
                if (svc == null) return false to "Accessibility off"
                val res = svc.goToAppHome(getApplication(), step.target)
                appendMessage("Tool", res); true to res
            }
            TaskAction.WEB_SEARCH_NARRATE -> {
                val url = "https://www.google.com/search?q=" + Uri.encode(step.target)
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                getApplication<Application>().startActivity(intent)
                appendMessage("Tool", "Opened Google for: ${step.target}"); delay(2800L)
                val pageText = svc?.readFullScreenText(1600).orEmpty()
                if (pageText.isNotBlank()) {
                    val summaryPrompt = "Summarize the answer to '${step.target}' concisely in 2-3 sentences from this Google result:\n$pageText"
                    if (_cloudConfig.value.apiKey.isNotBlank()) askCloudForHelp(summaryPrompt)
                    else if (isLoaded) { _isNavigating.value = false; activeGoal = summaryPrompt; runNextStep(extraNote = summaryPrompt) }
                    else appendMessage("Assistant", pageText.lines().filter { it.length > 25 }.take(4).joinToString(" "))
                }
                true to "Narrated web search"
            }
            TaskAction.ANALYZE_SCREEN -> {
                val screenText = svc?.let {
                    val cur = it.readFullScreenText(2200)
                    if (it.observeScreen().packageName == "com.pocketai.app" || cur.length < 40) it.lastExternalScreenText else cur
                }.orEmpty()
                if (screenText.isBlank()) return false to "No readable screen text captured yet—open the question screen first."
                val qPrompt = "Screen text from another app:\n$screenText\n\nUser request:${step.target}\nSolve or explain clearly in character."
                if (isLoaded) { _isNavigating.value = false; activeGoal = step.target; runNextStep(extraNote = qPrompt) }
                else if (_cloudConfig.value.apiKey.isNotBlank()) askCloudForHelp(qPrompt)
                else appendMessage("Tool", "Load a .gguf model or set a Cloud key to solve screen questions.")
                true to "Analyzed screen"
            }
            TaskAction.SEARCH -> {
                if (svc == null) return false to "Accessibility off"
                for (attempt in 0 until 4) {
                    if (!_isNavigating.value) return false to "Cancelled"
                    svc.getCompressedScreen()
                    val inputId = svc.findEditableId()
                    if (inputId != null) {
                        lastSearchQuery = step.target
                        val res = svc.typeText(inputId, step.target, submit = true)
                        appendMessage("Tool", res); delay(1800L); return true to res
                    }
                    svc.findAndTapByText("search")?.let { appendMessage("Tool", it); delay(900L) } ?: delay(600L)
                }
                false to "Search box not found for '${step.target}'"
            }
            TaskAction.MESSAGE_CONTACT -> {
                if (svc == null) return false to "Accessibility off"
                val opened = searchAndTapFallback(svc, step.target)
                if (!opened && _choicePrompt.value.isNotEmpty()) {
                    pendingChoiceContinuation = {
                        viewModelScope.launch(Dispatchers.IO) {
                            svc.getCompressedScreen()
                            val box = svc.findEditableId()
                            if (box != null) appendMessage("Tool", "${svc.typeText(box, step.extra, submit = false)} (Review & tap Send)")
                            _isNavigating.value = false; updateTask { it.copy(status = TaskStatus.COMPLETED) }
                        }
                    }
                    return false to "Waiting for user to pick contact"
                }
                if (!opened) return false to "Could not find chat for '${step.target}'"
                var msgBox: Int? = null
                for (w in 0 until 4) {
                    val chatObs = svc.observeScreen()
                    msgBox = chatObs.elements.lastOrNull { it.isEditable && it.bounds.bottom > 900 }?.id
                        ?: svc.findEditableId()
                    if (msgBox != null) break
                    delay(450L)
                }
                if (msgBox == null) return false to "Message box not found"
                val res = "${svc.typeText(msgBox, step.extra, submit = false)} (Review & tap Send)"
                appendMessage("Tool", res); true to res
            }
            TaskAction.FIND_OPEN_PDF -> {
                if (svc == null) return false to "Accessibility off"
                if (!svc.observeScreen().elements.any { it.isEditable && it.bounds.bottom > 1000 }) {
                    return false to "Not inside a chat (open the chat first before searching for PDFs)"
                }
                var pdfs: List<UiElement> = emptyList()
                for (attempt in 0 until 5) {
                    if (!_isNavigating.value) return false to "Cancelled"
                    pdfs = svc.findPdfDocuments(); if (pdfs.isNotEmpty()) break
                    svc.scroll("up"); delay(800L)
                }
                if (pdfs.isEmpty()) return false to "No PDF attachment found in this chat"
                val latest = pdfs.first()
                val tapRes = svc.tap(latest.id)
                appendMessage("Tool", "Opening latest PDF: ${latest.displayLabel.take(45)}"); delay(1200L)
                val status = svc.checkFileStatusMessage()
                if (status != null && status.startsWith("DOWNLOAD:")) {
                    status.removePrefix("DOWNLOAD:").toIntOrNull()?.let { dlId ->
                        appendMessage("Tool", "Downloading PDF..."); svc.tap(dlId); delay(2200L)
                        svc.findPdfDocuments().firstOrNull()?.let { svc.tap(it.id); delay(1200L) }
                    }
                } else if (status != null && status.startsWith("UNAVAILABLE:")) return false to "PDF unavailable: ${status.removePrefix("UNAVAILABLE:")}"
                true to "Opened PDF ($tapRes)"
            }
            TaskAction.DOOMSCROLL -> {
                if (svc == null) return false to "Accessibility off"
                svc.getCompressedScreen(); svc.findAndTapByText("Shorts")?.let { appendMessage("Tool", it); delay(1500L) }
                appendMessage("Tool", "Watching Shorts (visual progress bar; tap Stop anytime)...")
                for (i in 1..30) {
                    if (!_isNavigating.value) break
                    delay(1500L)
                    if (svc.isShortsAd()) { appendMessage("Tool", "Skipped ad: ${svc.scroll("down")}"); continue }
                    var maxSeen = 0f; var elapsedMs = 0L; var sawBar = false
                    while (_isNavigating.value && elapsedMs < 90000L) {
                        if (svc.isShortsAd()) break
                        val p = svc.getShortsBarProgress()
                        if (p != null) {
                            sawBar = true; if (p >= 0.95f || (maxSeen >= 0.78f && p <= 0.15f)) break
                            if (p > maxSeen) maxSeen = p
                        } else if (!sawBar && elapsedMs >= 25000L) break
                        delay(1200L); elapsedMs += 1200L
                    }
                    if (!_isNavigating.value) break
                    appendMessage("Tool", svc.scroll("down"))
                }
                true to "Doomscroll finished"
            }
            TaskAction.TAP_ORDINAL -> {
                if (svc == null) return false to "Accessibility off"
                val idx = step.target.toIntOrNull() ?: 1; var prev = ""
                for (attempt in 0 until 6) {
                    if (!_isNavigating.value) return false to "Cancelled"
                    val screen = svc.getCompressedScreen()
                    svc.tapOrdinal(idx)?.let { appendMessage("Tool", it); return true to it }
                    if (screen == prev) break
                    prev = screen; svc.scroll("down"); delay(650L)
                }
                false to "Item #$idx not found"
            }
            TaskAction.TAP_TARGET -> {
                if (svc == null) return false to "Accessibility off"
                if (_activeTask.value?.let { t -> t.plan.getOrNull(t.currentStepIndex + 1)?.action } == TaskAction.FIND_OPEN_PDF) {
                    return if (searchAndTapFallback(svc, step.target)) true to "Opened chat: ${step.target}" else false to "Could not open chat for '${step.target}'"
                }
                var prev = ""
                for (attempt in 0 until 3) {
                    if (!_isNavigating.value) return false to "Cancelled"
                    val screen = svc.getCompressedScreen()
                    svc.findAndTapByText(step.target, skipEditable = true, excludeText = lastSearchQuery)?.let { appendMessage("Tool", it); return true to it }
                    if (screen == prev) break
                    prev = screen; appendMessage("Tool", svc.scroll("down")); delay(650L)
                }
                false to "Target '${step.target}' not visible"
            }
            TaskAction.SCROLL -> { val r = svc?.scroll(step.target) ?: "Accessibility off"; appendMessage("Tool", r); true to r }
            TaskAction.BACK -> { val r = svc?.pressBack() ?: "Accessibility off"; appendMessage("Tool", r); true to r }
        }
    }
    private suspend fun searchAndTapFallback(svc: PocketAccessibilityService, query: String): Boolean {
        for (attempt in 0 until 4) {
            if (!_isNavigating.value) return false
            svc.getCompressedScreen()
            val inputId = svc.findEditableId()
            if (inputId != null) {
                svc.typeText(inputId, query, submit = false); delay(1100L)
                val matches = svc.findMatchingElements(query, skipEditable = true)
                if (matches.size > 1) {
                    val exact = matches.firstOrNull { it.displayLabel.equals(query, ignoreCase = true) }
                    if (exact != null) { appendMessage("Tool", svc.tap(exact.id)); delay(900L); return true }
                    updateTask { it.copy(status = TaskStatus.WAITING_CONFIRMATION) }
                    _choicePrompt.value = matches
                    if (pendingChoiceContinuation == null) {
                        pendingChoiceContinuation = {
                            updateTask { it.copy(currentStepIndex = it.currentStepIndex + 1, status = TaskStatus.RUNNING) }
                            _isNavigating.value = true; viewModelScope.launch(Dispatchers.IO) { executeCurrentTask() }
                        }
                    }
                    svc.showFloatingChoices(matches, onPick = { selectChoice(it) }, onCancel = { dismissChoices() })
                    return false
                }
                svc.findAndTapByText(query, skipEditable = true)?.let { appendMessage("Tool", it); delay(900L); return true }
            } else {
                if (svc.findAndTapByText("search") != null) delay(800L) else delay(500L)
            }
        }
        return false
    }

    private fun askCloudForHelp(query: String, screenContext: String? = null) {
        val cfg = _cloudConfig.value; _isGenerating.value = true
        viewModelScope.launch(Dispatchers.IO) {
            val fullPrompt = buildString {
                append("You are Ultron (cold, eloquent, darkly witty Android AI). Answer concisely and accurately.\n")
                if (!screenContext.isNullOrBlank()) append("Android screen context:\n$screenContext\n")
                append("User request: $query")
            }
            appendMessage("Cloud (${cfg.provider.label})", CloudHelper.ask(cfg.provider, cfg.apiKey, cfg.model, fullPrompt))
            _isGenerating.value = false
        }
    }

    private fun handleAutomationStruggle(reason: String, screen: String) {
        appendMessage("Tool", reason)
        val cfg = _cloudConfig.value
        if (cfg.autoHelp && cfg.apiKey.isNotBlank()) askCloudForHelp("Local automation struggled on '$activeGoal' ($reason). Suggest the exact next step.", screen)
    }

    fun stopNavigation() {
        _isNavigating.value = false; _isGenerating.value = false; _pendingConfirmation.value = null
        updateTask { it.copy(status = TaskStatus.CANCELLED) }
        viewModelScope.launch(Dispatchers.IO) { llamaHelper.stopPrediction() }
    }

    private fun handleTurnCompletion() {
        if (!_isNavigating.value) return
        val reply = _messages.value.lastOrNull()?.text.orEmpty()
        val openMatch = openAppRegex.find(reply)
        if (openMatch != null) {
            _pendingConfirmation.value = PendingAction("Allow Ultron to launch this app?\n${openMatch.value}", openMatch.value); return
        }
        if (!reply.contains("[TOOL:")) {
            _isNavigating.value = false
            val cfg = _cloudConfig.value
            if (cfg.autoHelp && cfg.apiKey.isNotBlank() && reply.length < 15) askCloudForHelp(activeGoal)
            return
        }
        val tagMatch = Regex("""\[TOOL:[^\]]+\]""").find(reply)?.value.orEmpty()
        if (tagMatch.isNotEmpty() && tagMatch == lastToolTag) {
            _isNavigating.value = false; appendMessage("Tool", "Stopped: repeated the same action ($tagMatch)"); return
        }
        lastToolTag = tagMatch
        viewModelScope.launch(Dispatchers.IO) {
            val result = ToolRegistry.executeIfPresent(getApplication(), reply) { saveFact(it) } ?: "No tool executed"
            appendMessage("Tool", result)
            val isFail = result.startsWith("Error") || result.startsWith("Failed") ||
                result.startsWith("App not found") || result.startsWith("Unknown tool") ||
                result.startsWith("Cannot") || result.contains("not enabled")
            consecutiveFailures = if (isFail) consecutiveFailures + 1 else 0
            if (consecutiveFailures >= 2) {
                _isNavigating.value = false
                handleAutomationStruggle("Stopped after 2 failed attempts ($result)", PocketAccessibilityService.instance?.getCompressedScreen() ?: "")
                return@launch
            }
            continueLoopAfterTool(result, waitMs = 800L)
        }
    }

    private suspend fun continueLoopAfterTool(toolResult: String, waitMs: Long) {
        stepCount++
        if (!_isNavigating.value || stepCount >= maxSteps) { _isNavigating.value = false; return }
        delay(waitMs)
        runNextStep(extraNote = "Previous tool result: $toolResult. Continue if more UI steps are needed, or answer in character if done.")
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
