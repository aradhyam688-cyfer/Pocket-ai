package com.pocketai.app

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.BatteryManager
import android.provider.AlarmClock
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
    val wakeMode: Boolean = false,
    val langTag: String = "en-IN",
    val metallic: Boolean = true
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val db = AppDatabase.get(application)
    private val messageDao = db.messageDao()
    private val memoryDao = db.memoryDao()
    private val taskDao = db.taskDao()
    private val prefs = application.getSharedPreferences("pocketai_cloud", Context.MODE_PRIVATE)

    private val _llmFlow = MutableSharedFlow<LlamaHelper.LLMEvent>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val llamaHelper by lazy { LlamaHelper(application.contentResolver, viewModelScope, _llmFlow) }

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages = _messages.asStateFlow()

    private val _memoryCount = MutableStateFlow(0)
    val memoryCount = _memoryCount.asStateFlow()

    private val _fastAuto = MutableStateFlow(true)
    val fastAuto = _fastAuto.asStateFlow()

    private val _liveWeb = MutableStateFlow(false)
    val liveWeb = _liveWeb.asStateFlow()

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
    private var pendingEmailTo: String? = null
    private var pendingEmailSubject: String = ""
    private var currentAssistantMsgId: Long? = null
    private var cachedFacts: List<String> = emptyList()
    private val maxSteps = 6
    private val openAppRegex = Regex("""\[TOOL:\s*open_app\Vert{}[^\]]+\]""")

    init {
        viewModelScope.launch(Dispatchers.IO) {
            _messages.value = messageDao.getAll().map { ChatMessage(it.role, it.text) }
            cachedFacts = memoryDao.getAll().map { it.fact }
            _memoryCount.value = cachedFacts.size
            if (_autoLoadLast.value) {
                delay(1500L)
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
                        currentAssistantMsgId?.let { id -> viewModelScope.launch(Dispatchers.IO) { messageDao.updateText(id, reply) } }
                        handleTurnCompletion()
                    }
                    is LlamaHelper.LLMEvent.Error -> {
                        _isGenerating.value = false; _isNavigating.value = false
                        _status.value = "Error: ${event.message}"
                    }
                    else -> {}
                }
            }
        }
    }

    private fun loadRecentModels(): List<RecentModel> = runCatching {
        val arr = JSONArray(prefs.getString("recentModels", "[]") ?: "[]")
        (0 until arr.length()).map { i -> val o = arr.getJSONObject(i); RecentModel(o.getString("name"), o.getString("uri")) }
    }.getOrDefault(emptyList())

    private fun saveRecentModel(name: String, uri: String) {
        val updated = (listOf(RecentModel(name, uri)) + _recentModels.value.filter { it.uri != uri }).take(5)
        _recentModels.value = updated
        val arr = JSONArray().apply { updated.forEach { put(JSONObject().put("name", it.name).put("uri", it.uri)) } }
        prefs.edit().putString("recentModels", arr.toString()).apply()
    }

    fun setAutoLoadLast(enabled: Boolean) { _autoLoadLast.value = enabled; prefs.edit().putBoolean("autoLoadLast", enabled).apply() }
    fun toggleLiveWeb() { _liveWeb.value = !_liveWeb.value }
    fun toggleFastAuto() { _fastAuto.value = !_fastAuto.value }

    private fun loadVoiceConfig() = VoiceConfig(
        voiceName = prefs.getString("voiceName", "") ?: "",
        pitch = prefs.getFloat("voicePitch", 0.68f),
        rate = prefs.getFloat("voiceRate", 0.90f),
        wakeMode = prefs.getBoolean("wakeMode", false),
        langTag = prefs.getString("langTag", "en-IN") ?: "en-IN",
        metallic = prefs.getBoolean("metallic", true)
    )

    fun saveVoiceConfig(voiceName: String, pitch: Float, rate: Float, wakeMode: Boolean, langTag: String, metallic: Boolean) {
        prefs.edit().putString("voiceName", voiceName).putFloat("voicePitch", pitch).putFloat("voiceRate", rate)
            .putBoolean("wakeMode", wakeMode).putString("langTag", langTag).putBoolean("metallic", metallic).apply()
        _voiceConfig.value = VoiceConfig(voiceName, pitch, rate, wakeMode, langTag, metallic)
    }

    private fun updateTask(transform: (AgentTask) -> AgentTask) {
        val current = _activeTask.value ?: return
        val updated = transform(current)
        _activeTask.value = updated
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                taskDao.upsert(TaskEntity(updated.id, updated.originalInstruction, updated.objective, updated.status.name, updated.currentStepIndex, updated.plan.size, updated.observations.lastOrNull() ?: ""))
            }
        }
    }

    private fun loadCloudConfig(): CloudConfig {
        val prov = runCatching { CloudProvider.valueOf(prefs.getString("provider", CloudProvider.GEMINI.name) ?: "GEMINI") }.getOrDefault(CloudProvider.GEMINI)
        return CloudConfig(prov, prefs.getString("apiKey", "") ?: "", prefs.getString("model", prov.defaultModel) ?: prov.defaultModel, prefs.getBoolean("autoHelp", true))
    }

    fun saveCloudConfig(provider: CloudProvider, apiKey: String, model: String, autoHelp: Boolean) {
        val cleanModel = model.ifBlank { provider.defaultModel }
        prefs.edit().putString("provider", provider.name).putString("apiKey", apiKey.trim()).putString("model", cleanModel).putBoolean("autoHelp", autoHelp).apply()
        _cloudConfig.value = CloudConfig(provider, apiKey.trim(), cleanModel, autoHelp)
    }

    fun loadModel(uriString: String, displayName: String? = null) {
        val label = displayName ?: Uri.parse(uriString).lastPathSegment?.substringAfterLast('/') ?: "model.gguf"
        _status.value = "Loading $label..."
        saveRecentModel(label, uriString)
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                llamaHelper.load(path = uriString, contextLength = 2048) {
                    isLoaded = true
                    _status.value = "Ready ($label)"
                }
            }.onFailure { _status.value = "Load failed: ${it.message}" }
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
        val clean = fact.trim(); if (clean.isEmpty()) return
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
    private fun normalizeHinglish(raw: String): String {
        return raw
            .replace(Regex("""(?i)\b(kholo|khol|chalao)\b"""), "open")
            .replace(Regex("""(?i)\b(bhejo|bhej|likho|likh)\b"""), "message")
            .replace(Regex("""(?i)\b(dhundo|dhund)\b"""), "search")
            .replace(Regex("""(?i)\b(aur|te)\b"""), "and")
    }

    private fun checkThermalBatterySafe(): String? {
        val bm = getApplication<Application>().registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        val temp = bm.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10f
        val lvl = bm.getIntExtra(BatteryManager.EXTRA_LEVEL, 100)
        val status = bm.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        if (temp >= 44f) return "Paused: device warm (${temp}°C)"
        if (lvl <= 12 && !charging) return "Paused: low battery ($lvl%)"
        return null
    }

    private fun buildPlan(rawInput: String): List<PlannedStep> {
        val input = normalizeHinglish(rawInput)
        val lowerAll = input.lowercase().trim()

        if (Regex("""(?i)\b(?:flashlight|torch)\b""").containsMatchIn(lowerAll)) {
            val turnOn = !lowerAll.contains("off") && !lowerAll.contains("band")
            return listOf(PlannedStep(TaskAction.FLASHLIGHT, target = if (turnOn) "on" else "off"))
        }
        if (Regex("""(?i)\b(?:set|add)\s+(?:an?\s+)?alarm\b""").containsMatchIn(lowerAll)) {
            return listOf(PlannedStep(TaskAction.SET_ALARM, target = input))
        }
        if (Regex("""(?i)\b(?:set|start)\s+(?:a\s+)?timer\b""").containsMatchIn(lowerAll)) {
            return listOf(PlannedStep(TaskAction.SET_TIMER, target = input))
        }
        if (Regex("""(?i)^(?:write\s+(?:an?\s+)?)?(?:e?mail|send\s+e?mail)\b""").containsMatchIn(lowerAll)) {
            val toAddr = Regex("""[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}""").find(input)?.value ?: ""
            val subjMatch = Regex("""(?i)\bsubject\s+(.+?)(?=\s+\bbody\b|$)""").find(input)
            val bodyMatch = Regex("""(?i)\bbody\s+(.+)$""").find(input)
            val aboutMatch = Regex("""(?i)\b(?:about|regarding|for|on)\s+(.+)$""").find(input)
            val subj = subjMatch?.groupValues?.get(1)?.trim() ?: aboutMatch?.groupValues?.get(1)?.trim()?.take(60) ?: ""
            val body = bodyMatch?.groupValues?.get(1)?.trim() ?: aboutMatch?.groupValues?.get(1)?.trim() ?: input
            return listOf(PlannedStep(TaskAction.COMPOSE_EMAIL, target = toAddr, extra = "$subj|||$body"))
        }
        if (Regex("""(?i)\b(?:from the screen|on this screen|in this question|this screen|solve question)\b""").containsMatchIn(lowerAll)) {
            return listOf(PlannedStep(TaskAction.ANALYZE_SCREEN, target = rawInput))
        }
        if (Regex("""(?i)\b(?:go|back|return)\b.*\bhome\b""").containsMatchIn(lowerAll)) {
            val appMatch = Regex("""(?i)\b(?:in|on|to)\s+([a-z0-9._+-]+)(?:\s+home)?$""").find(lowerAll)
            return listOf(PlannedStep(TaskAction.GO_APP_HOME, target = appMatch?.groupValues?.get(1) ?: ""))
        }
        val selfQuestion = Regex("""(?i)\b(?:you|your|ultron)\b""").containsMatchIn(lowerAll)
        if (!selfQuestion && Regex("""(?i)^(?:who is|who was|what is|what are|where is|when is|google|look up|search web for)\s+(.+)""").containsMatchIn(lowerAll)) {
            val q = lowerAll.replaceFirst(Regex("""(?i)^(?:google|look up|search web for)\s+"""), "").trim()
            return listOf(PlannedStep(TaskAction.WEB_SEARCH_NARRATE, target = q))
        }
        val pdfIntent = Regex("""(?i)\b(?:pdf|document)\b""").containsMatchIn(input) && Regex("""(?i)\b(?:whatsapp|wa)\b""").containsMatchIn(input)
        if (pdfIntent) {
            val person = Regex("""(?i)\b(?:with|from|by)\s+([a-zA-Z0-9 ._-]+?)(?=\s*(?:,|\band\b|\bthen\b|\bsent\b|\bon\b|\bin\b|$))""").find(input)?.groupValues?.get(1)?.trim()
            if (!person.isNullOrBlank()) return listOf(PlannedStep(TaskAction.OPEN_APP, target = "WhatsApp"), PlannedStep(TaskAction.TAP_TARGET, target = person), PlannedStep(TaskAction.FIND_OPEN_PDF))
        }
        if (Regex("""(?i)^open\s+.+?\b(?:pdf|document|file)\b""").containsMatchIn(lowerAll) && !Regex("""(?i)\b(?:whatsapp|wa)\b""").containsMatchIn(lowerAll)) {
            val docName = input.replaceFirst(Regex("""(?i)^open\s+(?:the\s+)?"""), "").replace(Regex("""(?i)\s+(?:from|in|on)\s+(?:google\s+)?files.*$"""), "").replace(Regex("""(?i)\b(?:pdf|document|file)\b"""), "").trim()
            if (docName.isNotBlank()) return listOf(PlannedStep(TaskAction.OPEN_APP, target = "Files"), PlannedStep(TaskAction.SEARCH, target = docName), PlannedStep(TaskAction.TAP_TARGET, target = docName))
        }
        if (Regex("""(?i)^(?:message|text|tell|whatsapp)\s+""").containsMatchIn(lowerAll)) {
            val body = input.replaceFirst(Regex("""(?i)^(?:message|text|tell|whatsapp)\s+"""), "").trim()
            val qMatch = Regex("""^(.+?)\s+['"](.+?)['"]$""").find(body)
            val sMatch = Regex("""(?i)^(.+?)\s+(?:saying|that)\s+(.+)$""").find(body)
            val (person, msg) = when {
                qMatch != null -> qMatch.groupValues[1].trim() to qMatch.groupValues[2].trim()
                sMatch != null -> sMatch.groupValues[1].trim() to sMatch.groupValues[2].trim()
                else -> { val p = body.split(" ", limit = 2); if (p.size == 2) p[0] to p[1] else "" to "" }
            }
            if (person.isNotBlank() && msg.isNotBlank()) return listOf(PlannedStep(TaskAction.OPEN_APP, target = "WhatsApp"), PlannedStep(TaskAction.MESSAGE_CONTACT, target = person, extra = msg))
        }

        val clauses = input.split(Regex("""(?i)\s*(?:,|\band\b|\bthen\b|\s+(?=(?:tap|click|select|search|scroll|back|doomscroll|message)\b))\s*""")).map { it.trim() }.filter { it.isNotEmpty() }
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
                    else { val p = body.split(" ", limit = 2); if (p.size == 2) steps.add(PlannedStep(TaskAction.MESSAGE_CONTACT, p[0], p[1])) }
                }
                lower.startsWith("search for ") -> steps.add(PlannedStep(TaskAction.SEARCH, target = c.substring(11).trim()))
                lower.startsWith("search ") -> steps.add(PlannedStep(TaskAction.SEARCH, target = c.substring(7).trim()))
                lower.startsWith("scroll ") -> steps.add(PlannedStep(TaskAction.SCROLL, target = lower.removePrefix("scroll ").trim()))
                lower == "back" || lower == "go back" -> steps.add(PlannedStep(TaskAction.BACK))
                lower.startsWith("tap ") || lower.startsWith("click ") || lower.startsWith("select ") -> {
                    val target = c.replaceFirst(Regex("""(?i)^(?:tap|click|select)\s+(?:on\s+)?(?:the\s+)?"""), "").trim()
                    val ord = when { Regex("""(?i)\b(first|1st)\b""").containsMatchIn(target) -> "1"; Regex("""(?i)\b(second|2nd)\b""").containsMatchIn(target) -> "2"; Regex("""(?i)\b(third|3rd)\b""").containsMatchIn(target) -> "3"; else -> null }
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
                _activeTask.value = AgentTask(originalInstruction = trimmed, objective = "Execute ${validation.validatedPlan.size}-step workflow", plan = validation.validatedPlan, status = TaskStatus.RUNNING)
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
        viewModelScope.launch(Dispatchers.IO) {
            val webCtx = if (_liveWeb.value) CloudHelper.fetchWebText(trimmed) else ""
            runNextStep(extraNote = if (webCtx.isNotBlank()) "Live web context:\n$webCtx\n\nAnswer the user's request concisely in English." else null)
        }
    }

    fun selectChoice(element: UiElement) {
        _choicePrompt.value = emptyList()
        val cont = pendingChoiceContinuation; pendingChoiceContinuation = null
        viewModelScope.launch(Dispatchers.IO) {
            val res = PocketAccessibilityService.instance?.tap(element.id) ?: "Accessibility off"
            appendMessage("Tool", res)
            updateTask { it.copy(status = TaskStatus.RUNNING, observations = it.observations + res) }
            delay(900L); cont?.invoke()
        }
    }

    fun dismissChoices() {
        _choicePrompt.value = emptyList(); pendingChoiceContinuation = null; _isNavigating.value = false
        updateTask { it.copy(status = TaskStatus.CANCELLED) }
        appendMessage("Tool", "Selection cancelled by user")
    }

    fun pauseTask() { updateTask { it.copy(status = TaskStatus.PAUSED, history = it.history + "Paused by user") }; _isNavigating.value = false }
    fun resumeTask() {
        val task = _activeTask.value ?: return; if (task.status != TaskStatus.PAUSED) return
        updateTask { it.copy(status = TaskStatus.RUNNING, history = it.history + "Resumed") }; _isNavigating.value = true
        viewModelScope.launch(Dispatchers.IO) { executeCurrentTask() }
    }
    private fun handleTurnCompletion() {
        val emailTo = pendingEmailTo
        if (emailTo != null) {
            val emailBody = _messages.value.lastOrNull()?.text.orEmpty().replace(Regex("""\[TOOL:[^\]]+\]"""), "").trim()
            val emailSubj = pendingEmailSubject; pendingEmailTo = null; pendingEmailSubject = ""
            if (emailBody.isNotBlank()) launchEmailDraft(emailTo, emailSubj, emailBody)
            return
        }
        if (!_isNavigating.value) return
        val reply = _messages.value.lastOrNull()?.text.orEmpty()
        val openMatch = openAppRegex.find(reply)
        if (openMatch != null) { _pendingConfirmation.value = PendingAction("Allow Ultron to launch this app?\n${openMatch.value}", openMatch.value); return }
        if (!reply.contains("[TOOL:")) {
            _isNavigating.value = false
            val cfg = _cloudConfig.value
            if (cfg.autoHelp && cfg.apiKey.isNotBlank() && reply.length < 15) askCloudForHelp(activeGoal)
            return
        }
        val tagMatch = Regex("""\[TOOL:[^\]]+\]""").find(reply)?.value.orEmpty()
        if (tagMatch.isNotEmpty() && tagMatch == lastToolTag) { _isNavigating.value = false; appendMessage("Tool", "Stopped: repeated action"); return }
        lastToolTag = tagMatch
        viewModelScope.launch(Dispatchers.IO) {
            val result = ToolRegistry.executeIfPresent(getApplication(), reply) { saveFact(it) } ?: "No tool executed"
            appendMessage("Tool", result)
            val isFail = result.startsWith("Error") || result.startsWith("Failed") || result.startsWith("App not found") || result.startsWith("Unknown") || result.contains("not enabled")
            consecutiveFailures = if (isFail) consecutiveFailures + 1 else 0
            if (consecutiveFailures >= 2) { _isNavigating.value = false; handleAutomationStruggle("Stopped after 2 failures ($result)", PocketAccessibilityService.instance?.getCompressedScreen() ?: ""); return@launch }
            continueLoopAfterTool(result, waitMs = 800L)
        }
    }

    private suspend fun continueLoopAfterTool(toolResult: String, waitMs: Long) {
        stepCount++; if (!_isNavigating.value || stepCount >= maxSteps) { _isNavigating.value = false; return }
        delay(waitMs); runNextStep(extraNote = "Previous tool result: $toolResult. Continue if needed, or reply in English.")
    }

    private fun runNextStep(extraNote: String?) {
        if (!_isNavigating.value) return
        _isGenerating.value = true
        val historyTurns = _messages.value.filter { (it.role == "You" || it.role == "Assistant") && it.text.isNotBlank() }.takeLast(6)
        appendMessage("Assistant", "") { id -> currentAssistantMsgId = id }
        val rawScreen = PocketAccessibilityService.instance?.getCompressedScreen()
        val screenBlock = if (rawScreen != null && !rawScreen.startsWith("Package: com.pocketai.app")) "\nCurrent screen:\n$rawScreen" else ""
        val factsBlock = if (cachedFacts.isNotEmpty()) "\nKnown user facts:\n" + cachedFacts.joinToString("\n") { "- $it" } else ""
        val sb = StringBuilder("<|im_start|>system\nYou are Ultron, a cold, calculating, hyper-intelligent synthetic AI—eloquent, imperious, darkly witty. Understand English, Hindi, Punjabi, and Hinglish, and ALWAYS reply in English. Give balanced, 3 to 5 sentence replies when conversing.\nAvailable tools (emit exact tag only when needed):\n[TOOL: open_app|package=<pkg>]\n[TOOL: tap|id=<number>]\n[TOOL: type_text|id=<number>|text=<value>]\n[TOOL: scroll|direction=<up|down|left|right>]\n[TOOL: back]\n[TOOL: read_screen]\n[TOOL: save_memory|fact=<text>]$factsBlock$screenBlock\n<|im_end|>\n")
        if (extraNote == null) { for (msg in historyTurns) sb.append("<|im_start|>${if (msg.role == "You") "user" else "assistant"}\n${msg.text}<|im_end|>\n") }
        else sb.append("<|im_start|>user\nGoal: $activeGoal\n$extraNote<|im_end|>\n")
        sb.append("<|im_start|>assistant\n")
        viewModelScope.launch(Dispatchers.IO) { runCatching { llamaHelper.predict(sb.toString()) }.onFailure { _isGenerating.value = false; _isNavigating.value = false } }
    }
}
