package com.pocketai.app

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.BatteryManager
import android.app.ActivityManager
import android.provider.AlarmClock
import android.provider.ContactsContract
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
data class ThemeConfig(
    val accentHex: Long = 0xFFA6FF3BL,
    val bgMode: Int = 0,
    val glowIntensity: Float = 0.25f,
    val borderAlpha: Float = 0.14f
)
data class ModelConfig(
    val contextLength: Int = 1024,
    val historyTurns: Int = 6,
    val temperature: Float = 0.7f,
    val topP: Float = 0.9f,
    val topK: Int = 40,
    val threads: Int = 4,
    val maxTokens: Int = 256,
    val preset: String = "Balanced",
    val autoUnloadOnBg: Boolean = false,
    val systemPrompt: String = "",
    val chatTemplate: String = "AUTO"
)
data class VoiceConfig(
    val voiceName: String = "",
    val pitch: Float = 0.76f,
    val rate: Float = 0.94f,
    val wakeMode: Boolean = false,
    val langTag: String = "mix",
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

    private val _modelConfig = MutableStateFlow(loadModelConfig())
    val modelConfig = _modelConfig.asStateFlow()

    private val _voiceConfig = MutableStateFlow(loadVoiceConfig())
    private val _themeConfig = MutableStateFlow(loadThemeConfig())
    val themeConfig = _themeConfig.asStateFlow()
    private val _pendingCallContact = MutableStateFlow<String?>(null)
    val pendingCallContact = _pendingCallContact.asStateFlow()
    private val _ramTemp = MutableStateFlow("Checking...")
    val ramTemp = _ramTemp.asStateFlow()
    private var lastModelName = "" 
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
    private var pendingMsgPerson: String? = null
    private var lastContactName: String = ""
    private var pendingEmailSubject: String = ""
    private var currentAssistantMsgId: Long? = null
    private var cachedFacts: List<String> = emptyList()
    private val maxSteps = 6
    private val openAppRegex = Regex("""\[TOOL:\s*open_app|[^\]]+\]""")

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

    private fun loadThemeConfig() = ThemeConfig(
        accentHex = prefs.getLong("themeAccent", 0xFFA6FF3BL),
        bgMode = prefs.getInt("themeBgMode", 0),
        glowIntensity = prefs.getFloat("themeGlow", 0.25f),
        borderAlpha = prefs.getFloat("themeBorder", 0.14f)
    )

    fun saveThemeConfig(cfg: ThemeConfig) {
        prefs.edit().putLong("themeAccent", cfg.accentHex).putInt("themeBgMode", cfg.bgMode)
            .putFloat("themeGlow", cfg.glowIntensity).putFloat("themeBorder", cfg.borderAlpha).apply()
        _themeConfig.value = cfg
    }

    fun refreshRamTemp() {
        runCatching {
            val am = getApplication<Application>().getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val mi = ActivityManager.MemoryInfo(); am.getMemoryInfo(mi)
            val freeGb = mi.availMem / (1024.0 * 1024.0 * 1024.0)
            val bm = getApplication<Application>().registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val temp = (bm?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10f
            _ramTemp.value = String.format("%.1fGB free • %.1f°C", freeGb, temp)
        }
    }

    private fun loadModelConfig() = ModelConfig(
        contextLength = prefs.getInt("ctxLen", 1024),
        historyTurns = prefs.getInt("histTurns", 6),
        temperature = prefs.getFloat("temp", 0.7f),
        topP = prefs.getFloat("topP", 0.9f),
        topK = prefs.getInt("topK", 40),
        threads = prefs.getInt("threads", 4),
        maxTokens = prefs.getInt("maxTok", 256),
        preset = prefs.getString("preset", "Balanced") ?: "Balanced",
        autoUnloadOnBg = prefs.getBoolean("autoUnload", false),
        systemPrompt = prefs.getString("sysPrompt", "") ?: "",
        chatTemplate = prefs.getString("chatTpl", "AUTO") ?: "AUTO"
    )

    fun saveModelConfig(cfg: ModelConfig) {
        prefs.edit().putInt("ctxLen", cfg.contextLength).putInt("histTurns", cfg.historyTurns)
            .putFloat("temp", cfg.temperature).putFloat("topP", cfg.topP).putInt("topK", cfg.topK)
            .putInt("threads", cfg.threads).putInt("maxTok", cfg.maxTokens)
            .putString("preset", cfg.preset).putBoolean("autoUnload", cfg.autoUnloadOnBg)
            .putString("sysPrompt", cfg.systemPrompt).putString("chatTpl", cfg.chatTemplate).apply()
        _modelConfig.value = cfg
    }

    private fun loadVoiceConfig() = VoiceConfig(
        voiceName = prefs.getString("voiceName", "") ?: "",
        pitch = prefs.getFloat("voicePitch", 0.76f),
        rate = prefs.getFloat("voiceRate", 0.94f),
        wakeMode = prefs.getBoolean("wakeMode", false),
        langTag = prefs.getString("langTag", "mix") ?: "mix",
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
                llamaHelper.load(path = uriString, contextLength = _modelConfig.value.contextLength.coerceIn(256, 4096)) {
                    lastModelName = label
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
        var t = raw
        val scriptMap = mapOf(
            "व्हाट्सएप" to "whatsapp", "यूट्यूब" to "youtube", "गूगल" to "google",
            "मम्मा" to "mamma", "पापा" to "papa", "भैया" to "bhaiya",
            "खोलो" to "open", "खोल" to "open", "भेजो" to "message", "मैसेज" to "message",
            "कॉल" to "call", "फ़ोन" to "phone", "पीडीएफ" to "pdf", "लिंक" to "link",
            "आखिरी" to "last", "पिछला" to "last", "आज" to "today", "कल" to "yesterday",
            "बताओ" to "tell", "सुनाओ" to "summarise", "वापस" to "back", "बंद" to "off",
            "ਮੰਮਾ" to "mamma", "ਪਾਪਾ" to "papa", "ਭਈਆ" to "bhaiya",
            "ਖੋਲ੍ਹੋ" to "open", "ਭੇਜੋ" to "message", "ਮੈਸੇਜ" to "message",
            "ਕਾਲ" to "call", "ਲਿੰਕ" to "link", "ਪੀਡੀਐਫ" to "pdf",
            "ਆਖਰੀ" to "last", "ਪਿਛਲਾ" to "last", "ਅੱਜ" to "today", "ਕੱਲ੍ਹ" to "yesterday",
            "ਦੱਸ" to "tell", "ਸੁਣਾਓ" to "summarise", "ਵਾਪਸ" to "back", "ਬੰਦ" to "off"
        )
        scriptMap.forEach { (k, v) -> t = t.replace(k, " $v ") }
        return t
            .replace(Regex("""(?i)\b(kholo|khol|chalao|kholna|khol\s+de)\b"""), "open")
            .replace(Regex("""(?i)\b(bhejo|bhej|likho|likh|ghallo|kar\s+de|bhej\s+de)\b"""), "message")
            .replace(Regex("""(?i)\b(dhundo|dhund|labho|labh)\b"""), "search")
            .replace(Regex("""(?i)\b(vekho|dikhao|daso|dass|batao|samjhao|sunao)\b"""), "summarise")
            .replace(Regex("""(?i)\b(akhri|aakhri|pichli|pichla|last)\b"""), "last")
            .replace(Regex("""(?i)\b(aaj|ajj)\b"""), "today")
            .replace(Regex("""(?i)\b(kal|kall)\b"""), "yesterday")
            .replace(Regex("""(?i)\b(wapas|pichhe)\b"""), "back")
            .replace(Regex("""(?i)\b(band)\b"""), "off")
            .replace(Regex("""(?i)\b(chalu)\b"""), "on")
            .replace(Regex("""(?i)\b(phone\s+laa|call\s+kar|call\s+laa|mila)\b"""), "call")
            .replace(Regex("""(?i)\b(aur|ate)\b"""), "and")
            .replace(Regex("""(?i)\b(vich|mein)\b"""), "in")
            .replace(Regex("""\s+"""), " ").trim()
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
        if (Regex("""(?i)^(?:call|ring|dial)\s+""").containsMatchIn(lowerAll) || Regex("""(?i)\bcall\s+[a-z0-9._-]+""").containsMatchIn(lowerAll)) {
            val mode = when {
                lowerAll.contains("whatsapp") || lowerAll.contains(" wa ") -> "whatsapp"
                lowerAll.contains("phone") || lowerAll.contains("sim") -> "phone"
                else -> ""
            }
            val person = input.replace(Regex("""(?i)^(?:please\s+)?(?:call|ring|dial)\s+"""), "")
                .replace(Regex("""(?i)\b(?:on|via)\s+(?:whatsapp|wa|phone|sim).*$"""), "").trim()
            if (person.isNotBlank()) return listOf(PlannedStep(TaskAction.CALL_CONTACT, target = person, extra = mode))
        }
        val wantsSummarize = Regex("""(?i)\b(?:summari[sz]e|summary|read|what\s+did)\b""").containsMatchIn(lowerAll) &&
            Regex("""(?i)\b(?:message|messages|msg|sent|send)\b""").containsMatchIn(lowerAll)
        if (wantsSummarize) {
            val dateF = when { lowerAll.contains("yesterday") -> "yesterday"; lowerAll.contains("today") -> "today"; else -> "" }
            val rawP = Regex("""(?i)\b(?:message|messages|msg)\s+([a-zA-Z0-9._-]+?)\s+(?:sent|send)\b""").find(input)?.groupValues?.get(1)
                ?: Regex("""(?i)\b(?:what\s+did|from)\s+([a-zA-Z0-9._-]+?)(?:\s+(?:sent|send)|$)""").find(input)?.groupValues?.get(1)
                ?: Regex("""(?i)\b([a-zA-Z0-9._-]+?)'s\s+(?:last|latest)?\s*(?:message|messages|msg)\b""").find(input)?.groupValues?.get(1)
            var person = rawP?.trim().orEmpty()
            if (person.lowercase() in setOf("he", "she", "him", "her", "they") && lastContactName.isNotBlank()) person = lastContactName
            if (person.isNotBlank()) return listOf(
                PlannedStep(TaskAction.OPEN_APP, target = "WhatsApp"),
                PlannedStep(TaskAction.SUMMARIZE_MESSAGES, target = person, extra = dateF)
            )
        }
        val isWa = Regex("""(?i)\b(?:whatsapp|wa|sent|sent\s+me)\b""").containsMatchIn(input)
        val wantsPdf = Regex("""(?i)\b(?:pdf|document)\b""").containsMatchIn(input)
        val wantsLink = Regex("""(?i)\blink\b""").containsMatchIn(input)
        if (isWa && (wantsPdf || wantsLink)) {
            val rawPerson =
                Regex("""(?i)\b(?:with|from|by)\s+([a-zA-Z0-9 ._-]+?)(?=\s*(?:,|\band\b|\bthen\b|\bsent\b|\bon\b|\bin\b|$))""").find(input)?.groupValues?.get(1)
                    ?: Regex("""(?i)\b(?:last|latest)\s+(?:pdf|document|link)\s+([a-zA-Z0-9 ._-]+?)\s+sent\b""").find(input)?.groupValues?.get(1)
                    ?: Regex("""(?i)\b([a-zA-Z0-9._-]+)'s\s+(?:last|latest)\s+(?:pdf|document|link)\b""").find(input)?.groupValues?.get(1)
            var person = rawPerson?.trim().orEmpty()
            if (person.lowercase() in setOf("he", "she", "him", "her", "they") && lastContactName.isNotBlank()) {
                person = lastContactName
            }
            if (person.isNotBlank()) {
                val finalAction = if (wantsLink) TaskAction.FIND_OPEN_LINK else TaskAction.FIND_OPEN_PDF
                return listOf(
                    PlannedStep(TaskAction.OPEN_APP, target = "WhatsApp"),
                    PlannedStep(TaskAction.TAP_TARGET, target = person),
                    PlannedStep(finalAction, extra = when { lowerAll.contains("yesterday") -> "yesterday"; lowerAll.contains("today") -> "today"; else -> "" })
                )
            }
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
                lower.startsWith("zoom ") -> steps.add(PlannedStep(TaskAction.ZOOM, target = if (lower.contains("in")) "in" else "out"))
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
        if (trimmed.equals("help", ignoreCase = true) || trimmed.equals("/help", ignoreCase = true)) {
            appendMessage("You", trimmed)
            refreshRamTemp()
            val accOk = PocketAccessibilityService.instance != null
            val modOk = isLoaded
            val cloudOk = _cloudConfig.value.apiKey.isNotBlank()
            val webOk = _liveWeb.value
            val st = { ok: Boolean -> if (ok) "Ready" else "Missing" }
            appendMessage("Assistant", """
                |[System Status]
                |• Local GGUF Model: ${st(modOk)} | Cloud AI: ${st(cloudOk)}
                |• Accessibility: ${st(accOk)} | Live Web: ${if (webOk) "ON" else "OFF"}
                |• Device: ${_ramTemp.value}
                |
                |[Commands]
                |• /help, /remember <fact>, /cloud <question>
                |• "call mamma" (asks WhatsApp or Phone)
                |• "think and message bhaiya on <topic>"
                |• "summarise the latest message mamma sent"
                |
                |[Accessibility Features] (Needs: Accessibility ON)
                |• WhatsApp: "message <name> <text>", "open wa and tap link he sent yesterday", "open latest pdf mamma sent"
                |• YouTube: "open yt and search ...", "open yt and doomscroll"
                |• Gestures: "zoom in/out", "scroll up/down", "go home in whatsapp"
                |
                |[AI & Web Features] (Needs: GGUF Model OR Cloud AI; Web ON for live facts)
                |• Document / Screen Q&A: "solve question 12 on screen" (Needs: Accessibility + Model/Cloud)
                |• Live Web Facts: Toggle Web ON for real-time facts in any answer
                |
                |[Voice & Device Controls]
                |• Voice: Tap Mic or enable "Ultron" wake word (Mix / EN / HI / PA)
                |• Controls: "turn on flashlight", "set alarm for 7 am", "set timer for 5 min"
            """.trimMargin())
            return
        }
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

    fun confirmCallMethod(useWhatsApp: Boolean) {
        val person = _pendingCallContact.value ?: return
        _pendingCallContact.value = null
        val mode = if (useWhatsApp) "whatsapp" else "phone"
        _activeTask.value = AgentTask(
            originalInstruction = "Call $person ($mode)",
            objective = "Call $person via $mode",
            plan = listOf(PlannedStep(TaskAction.CALL_CONTACT, target = person, extra = mode)),
            status = TaskStatus.RUNNING
        )
        _isNavigating.value = true
        viewModelScope.launch(Dispatchers.IO) { executeCurrentTask() }
    }

    fun dismissCallPrompt() { _pendingCallContact.value = null }

    private fun placePhoneCall(contactName: String): String {
        val app = getApplication<Application>()
        var number: String? = null
        runCatching {
            val cur = app.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
                arrayOf("%$contactName%"), null
            )
            cur?.use { if (it.moveToFirst()) number = it.getString(1) }
        }
        if (number.isNullOrBlank()) {
            val dial = Intent(Intent.ACTION_DIAL).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            app.startActivity(dial)
            return "Opened Dialer (grant Contacts permission or dial $contactName)"
        }
        val uri = Uri.parse("tel:" + Uri.encode(number))
        return runCatching {
            app.startActivity(Intent(Intent.ACTION_CALL, uri).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
            "Calling $contactName ($number)"
        }.getOrElse {
            app.startActivity(Intent(Intent.ACTION_DIAL, uri).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
            "Opened Dialer for $contactName ($number)"
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
    fun confirmPendingAction(allow: Boolean) {
        val pending = _pendingConfirmation.value ?: return
        _pendingConfirmation.value = null
        if (!allow) {
            _isNavigating.value = false; updateTask { it.copy(status = TaskStatus.CANCELLED, history = it.history + "Denied") }
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
                updateTask { it.copy(currentStepIndex = it.currentStepIndex + 1, status = TaskStatus.RUNNING, observations = it.observations + result) }
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
            if (task.currentStepIndex >= task.plan.size) { updateTask { it.copy(status = TaskStatus.COMPLETED) }; _isNavigating.value = false; break }
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
            val noSvcActions = setOf(TaskAction.WEB_SEARCH_NARRATE, TaskAction.ANALYZE_SCREEN, TaskAction.COMPOSE_EMAIL, TaskAction.THINK_AND_MESSAGE, TaskAction.FLASHLIGHT, TaskAction.SET_ALARM, TaskAction.SET_TIMER)
            if (step.action !in noSvcActions && svc == null) {
                appendMessage("Tool", "Accessibility service not enabled"); updateTask { it.copy(status = TaskStatus.FAILED) }; _isNavigating.value = false; break
            }
            val (ok, obs) = runCatching { runSingleStep(svc, step) }.getOrElse { false to "Step error: ${it.message}" }
            if (ok) {
                updateTask { it.copy(currentStepIndex = it.currentStepIndex + 1, observations = it.observations + obs) }
                delay(700L)
            } else {
                if (step.action == TaskAction.TAP_TARGET && task.retryCount < task.maxRetries && svc != null) {
                    appendMessage("Tool", "Replanning: searching for '${step.target}'...")
                    if (searchAndTapFallback(svc, step.target)) {
                        updateTask { it.copy(currentStepIndex = it.currentStepIndex + 1, retryCount = it.retryCount + 1) }
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
            TaskAction.FLASHLIGHT -> {
                val cm = getApplication<Application>().getSystemService(Context.CAMERA_SERVICE) as CameraManager
                val id = cm.cameraIdList.firstOrNull() ?: return false to "No camera flash"
                val on = step.target == "on"
                cm.setTorchMode(id, on)
                val msg = "Flashlight turned ${if (on) "ON" else "OFF"}"
                appendMessage("Tool", msg); true to msg
            }
            TaskAction.SET_ALARM -> {
                val m = Regex("""(\d{1,2})(?::(\d{2}))?\s*(am|pm)?""", RegexOption.IGNORE_CASE).find(step.target)
                var hr = m?.groupValues?.get(1)?.toIntOrNull() ?: 7
                val min = m?.groupValues?.get(2)?.toIntOrNull() ?: 0
                val ap = m?.groupValues?.get(3)?.lowercase() ?: ""
                if (ap == "pm" && hr < 12) hr += 12; if (ap == "am" && hr == 12) hr = 0
                val i = Intent(AlarmClock.ACTION_SET_ALARM).apply { putExtra(AlarmClock.EXTRA_HOUR, hr); putExtra(AlarmClock.EXTRA_MINUTES, min); addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                getApplication<Application>().startActivity(i)
                appendMessage("Tool", "Set alarm for %02d:%02d".format(hr, min)); true to "Alarm set"
            }
            TaskAction.SET_TIMER -> {
                val num = Regex("""(\d+)""").find(step.target)?.groupValues?.get(1)?.toIntOrNull() ?: 5
                val secs = if (step.target.contains("sec", true)) num else num * 60
                val i = Intent(AlarmClock.ACTION_SET_TIMER).apply { putExtra(AlarmClock.EXTRA_LENGTH, secs); putExtra(AlarmClock.EXTRA_SKIP_UI, false); addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                getApplication<Application>().startActivity(i)
                appendMessage("Tool", "Timer set for ${secs}s"); true to "Timer set"
            }
            TaskAction.THINK_AND_MESSAGE -> {
                if (isLoaded) {
                    pendingMsgPerson = step.target
                    _isNavigating.value = true; updateTask { it.copy(status = TaskStatus.COMPLETED) }
                    val webCtx = maybeFetchTopicWeb(step.extra)
                    activeGoal = "Write a concise, helpful WhatsApp message to ${step.target} about: ${step.extra}.$webCtx Output ONLY the message text to send, no quotes, no [TOOL:] tags."
                    runNextStep(extraNote = activeGoal)
                    return true to "Thinking of message for ${step.target}..."
                } else if (_cloudConfig.value.apiKey.isNotBlank()) {
                    val drafted = CloudHelper.ask(_cloudConfig.value.provider, _cloudConfig.value.apiKey, _cloudConfig.value.model, "Write a concise WhatsApp message about: ${step.extra}${maybeFetchTopicWeb(step.extra)}")
                    _activeTask.value = AgentTask(originalInstruction = activeGoal, objective = "Send drafted message", plan = listOf(PlannedStep(TaskAction.OPEN_APP, "WhatsApp", requiresConfirmation = true), PlannedStep(TaskAction.MESSAGE_CONTACT, step.target, drafted)), status = TaskStatus.RUNNING)
                    executeCurrentTask()
                    return true to "Drafted message via Cloud"
                } else {
                    return false to "Load a .gguf model or set a Cloud key for 'think and message'"
                }
            }
            TaskAction.COMPOSE_EMAIL -> {
                val parts = step.extra.split("|||", limit = 2)
                val subject = parts.getOrElse(0) { "" }.ifBlank { "Quick Note" }
                var body = parts.getOrElse(1) { "" }
                if (isLoaded && body.length < 140) {
                    pendingEmailTo = step.target; pendingEmailSubject = subject
                    _isNavigating.value = true; updateTask { it.copy(status = TaskStatus.COMPLETED) }
                    val webCtx = maybeFetchTopicWeb(body)
                    activeGoal = "Write a concise, clear, ready-to-send email body about: $body.$webCtx Output ONLY the email body, no subject line, no [TOOL:] tags."
                    runNextStep(extraNote = activeGoal)
                    return true to "Drafting email with Ultron"
                } else if (_cloudConfig.value.apiKey.isNotBlank() && body.length < 140) {
                    val d = CloudHelper.ask(_cloudConfig.value.provider, _cloudConfig.value.apiKey, _cloudConfig.value.model, "Write a concise email body about: $body${maybeFetchTopicWeb(body)}")
                    if (!d.startsWith("Cloud error")) body = d
                }
                launchEmailDraft(step.target, subject, body); true to "Opened email draft"
            }
            TaskAction.GO_APP_HOME -> { val r = svc?.goToAppHome(getApplication(), step.target) ?: "Access off"; appendMessage("Tool", r); true to r }
            TaskAction.WEB_SEARCH_NARRATE -> {
                val url = "https://www.google.com/search?q=" + Uri.encode(step.target)
                getApplication<Application>().startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
                appendMessage("Tool", "Opened Google for: ${step.target}"); delay(2800L)
                val pageText = svc?.readFullScreenText(1600).orEmpty().ifBlank { CloudHelper.fetchWebText(step.target) }
                if (pageText.isNotBlank()) {
                    val sp = "Summarize the answer to '${step.target}' concisely in 2-3 sentences in English:\n$pageText"
                    if (_cloudConfig.value.apiKey.isNotBlank()) askCloudForHelp(sp)
                    else if (isLoaded) { _isNavigating.value = true; activeGoal = sp; runNextStep(extraNote = sp) }
                    else appendMessage("Assistant", pageText.lines().filter { it.length > 25 }.take(4).joinToString(" "))
                }
                true to "Narrated web search"
            }
            TaskAction.ANALYZE_SCREEN -> {
                var screenText = svc?.let { val c = it.readFullScreenText(2200); if (it.observeScreen().packageName == "com.pocketai.app" || c.length < 40) it.lastExternalScreenText else c }.orEmpty()
                if (svc != null && svc.observeScreen().packageName != "com.pocketai.app" && screenText.length < 180) {
                    appendMessage("Tool", svc.pinchZoom(zoomIn = false))
                    delay(700L)
                    val expanded = svc.readFullScreenText(2200)
                    if (expanded.length > screenText.length) screenText = expanded
                }
                if (screenText.isBlank()) return false to "No readable screen text captured yet—open the question screen first."
                val qp = "Screen text from another app:\n$screenText\n\nUser request: ${step.target}\nSolve or explain clearly in English."
                if (isLoaded) { _isNavigating.value = true; activeGoal = step.target; runNextStep(extraNote = qp) }
                else if (_cloudConfig.value.apiKey.isNotBlank()) askCloudForHelp(qp)
                else appendMessage("Tool", "Load a .gguf model or set a Cloud key to solve screen questions.")
                true to "Analyzed screen"
            }
            TaskAction.SEARCH -> {
                for (attempt in 0 until 4) {
                    if (!_isNavigating.value) return false to "Cancelled"
                    svc!!.getCompressedScreen()
                    val inputId = svc.findEditableId()
                    if (inputId != null) { lastSearchQuery = step.target; val r = svc.typeText(inputId, step.target, true); appendMessage("Tool", r); delay(1800L); return true to r }
                    svc.findAndTapByText("search")?.let { appendMessage("Tool", it); delay(900L) } ?: delay(600L)
                }
                false to "Search box not found for '${step.target}'"
            }
            TaskAction.MESSAGE_CONTACT -> {
                val opened = searchAndTapFallback(svc!!, step.target)
                if (!opened && _choicePrompt.value.isNotEmpty()) {
                    pendingChoiceContinuation = {
                        viewModelScope.launch(Dispatchers.IO) {
                            var typed: String? = null
                            for (w in 0 until 5) { typed = svc.typeIntoBottomInput(step.extra); if (typed != null) break; delay(450L) }
                            if (typed != null) appendMessage("Tool", "$typed (Review & tap Send)")
                            else appendMessage("Tool", "Message box not found")
                            _isNavigating.value = false; updateTask { it.copy(status = TaskStatus.COMPLETED) }
                        }
                    }
                    return false to "Waiting for user to pick contact"
                }
                if (!opened) return false to "Could not find chat for '${step.target}'"
                var typed: String? = null
                for (w in 0 until 5) {
                    typed = svc.typeIntoBottomInput(step.extra)
                    if (typed != null) break
                    delay(450L)
                }
                if (typed == null) return false to "Message box not found"
                val res = "$typed (Review & tap Send)"; appendMessage("Tool", res); true to res
            }
            TaskAction.FIND_OPEN_LINK -> {
                val a = svc ?: return false to "Accessibility off"
                if (!a.observeScreen().elements.any { it.isEditable && it.bounds.bottom > 1000 }) return false to "Not inside a chat"
                var links: List<UiElement> = emptyList()
                for (attempt in 0 until 4) {
                    if (!_isNavigating.value) return false to "Cancelled"
                    links = a.findLinksInChat(step.extra); if (links.isNotEmpty()) break
                    a.scroll("up"); delay(800L)
                }
                if (links.isEmpty()) return false to "No link found in this chat"
                val latest = links.first()
                val res = a.tap(latest.id)
                appendMessage("Tool", "Opening latest link: ${latest.displayLabel.take(45)}")
                true to res
            }
            TaskAction.FIND_OPEN_PDF -> {
                if (!svc!!.observeScreen().elements.any { it.isEditable && it.bounds.bottom > 1000 }) return false to "Not inside a chat"
                var pdfs: List<UiElement> = emptyList()
                for (attempt in 0 until 5) { if (!_isNavigating.value) return false to "Cancelled"; pdfs = svc.findPdfDocuments(); if (pdfs.isNotEmpty()) break; svc.scroll("up"); delay(800L) }
                if (pdfs.isEmpty()) return false to "No PDF found in this chat"
                val latest = pdfs.first(); val tapRes = svc.tap(latest.id); appendMessage("Tool", "Opening latest PDF: ${latest.displayLabel.take(45)}"); delay(1200L)
                val st = svc.checkFileStatusMessage()
                if (st?.startsWith("DOWNLOAD:") == true) { st.removePrefix("DOWNLOAD:").toIntOrNull()?.let { svc.tap(it); delay(2200L); svc.findPdfDocuments().firstOrNull()?.let { p -> svc.tap(p.id); delay(1200L) } } }
                else if (st?.startsWith("UNAVAILABLE:") == true) return false to "PDF unavailable"
                true to "Opened PDF ($tapRes)"
            }
            TaskAction.DOOMSCROLL -> {
                svc!!
                for (t in 0 until 5) {
                    if (!_isNavigating.value) break
                    val tappedShorts = svc!!.tapBottomNavTab("Shorts") ?: svc.findAndTapByText("Shorts", skipEditable = true)
                    if (tappedShorts != null) { appendMessage("Tool", tappedShorts); delay(1500L); break }
                    delay(800L)
                }
                appendMessage("Tool", "Watching Shorts (visual bar + thermal guard; tap Stop anytime)...")
                for (i in 1..30) {
                    if (!_isNavigating.value) break
                    checkThermalBatterySafe()?.let { warn -> appendMessage("Tool", warn); break }
                    delay(1500L)
                    if (svc.isShortsAd()) { appendMessage("Tool", "Skipped ad: ${svc.scroll("down")}"); continue }
                    var maxSeen = 0f; var elapsedMs = 0L; var sawBar = false
                    while (_isNavigating.value && elapsedMs < 90000L) {
                        if (svc.isShortsAd()) break
                        val p = svc.getShortsBarProgress()
                        if (p != null) { sawBar = true; if (p >= 0.95f || (maxSeen >= 0.78f && p <= 0.15f)) break; if (p > maxSeen) maxSeen = p }
                        else if (!sawBar && elapsedMs >= 25000L) break
                        delay(1200L); elapsedMs += 1200L
                    }
                    if (!_isNavigating.value) break
                    appendMessage("Tool", svc.scroll("down"))
                }
                true to "Doomscroll finished"
            }
            TaskAction.TAP_ORDINAL -> {
                val idx = step.target.toIntOrNull() ?: 1; var prev = ""
                for (attempt in 0 until 6) { val s = svc!!.getCompressedScreen(); svc.tapOrdinal(idx)?.let { appendMessage("Tool", it); return true to it }; if (s == prev) break; prev = s; svc.scroll("down"); delay(650L) }
                false to "Item #$idx not found"
            }
            TaskAction.TAP_TARGET -> {
                val nextAct = _activeTask.value?.let { t -> t.plan.getOrNull(t.currentStepIndex + 1)?.action }
                if (nextAct == TaskAction.FIND_OPEN_PDF || nextAct == TaskAction.FIND_OPEN_LINK) {
                    val ok = searchAndTapFallback(svc!!, step.target)
                    if (!ok && _choicePrompt.value.isNotEmpty()) {
                        pendingChoiceContinuation = {
                            updateTask { it.copy(currentStepIndex = it.currentStepIndex + 1, status = TaskStatus.RUNNING) }
                            _isNavigating.value = true; viewModelScope.launch(Dispatchers.IO) { executeCurrentTask() }
                        }
                        return false to "Waiting for user to pick contact"
                    }
                    return if (ok) true to "Opened chat: ${step.target}" else false to "Could not open chat for '${step.target}'"
                }
                var prev = ""
                for (attempt in 0 until 3) { val s = svc!!.getCompressedScreen(); svc.findAndTapByText(step.target, true, lastSearchQuery)?.let { appendMessage("Tool", it); return true to it }; if (s == prev) break; prev = s; appendMessage("Tool", svc.scroll("down")); delay(650L) }
                false to "Target '${step.target}' not visible"
            }
            TaskAction.CALL_CONTACT -> {
                if (step.extra.isBlank()) {
                    _pendingCallContact.value = step.target
                    return false to "Choose WhatsApp or Phone to call ${step.target}"
                }
                if (step.extra == "whatsapp") {
                    val launch = getApplication<Application>().packageManager.getLaunchIntentForPackage("com.whatsapp")
                        ?: return false to "WhatsApp not installed"
                    launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); getApplication<Application>().startActivity(launch)
                    delay(1100L)
                    val opened = searchAndTapFallback(svc!!, step.target)
                    if (!opened) return false to "Could not find ${step.target} on WhatsApp"
                    delay(800L)
                    val res = svc.tapWhatsAppCallButton()
                    appendMessage("Tool", res); true to res
                } else {
                    val res = placePhoneCall(step.target)
                    appendMessage("Tool", res); true to res
                }
            }
            TaskAction.SUMMARIZE_MESSAGES -> {
                val opened = searchAndTapFallback(svc!!, step.target)
                if (!opened) return false to "Could not find chat for '${step.target}'"
                delay(900L)
                val msgs = svc.readIncomingMessages(step.extra)
                if (msgs.isEmpty()) return false to "No incoming messages found from ${step.target}"
                val joined = msgs.takeLast(8).joinToString("\n- ", prefix = "- ")
                val prompt = "Summarize these incoming WhatsApp messages from ${step.target} concisely in English:\n$joined"
                if (_cloudConfig.value.apiKey.isNotBlank()) {
                    askCloudForHelp(prompt)
                } else if (isLoaded) {
                    runNextStep(extraNote = prompt)
                } else {
                    val raw = "Messages from ${step.target}:\n$joined"
                    appendMessage("Assistant", raw)
                }
                true to "Summarized ${msgs.size} message(s) from ${step.target}"
            }
            TaskAction.ZOOM -> { val r = svc?.pinchZoom(step.target == "in") ?: "Access off"; appendMessage("Tool", r); true to r }
            TaskAction.SCROLL -> { val r = svc?.scroll(step.target) ?: "Access off"; appendMessage("Tool", r); true to r }
            TaskAction.BACK -> { val r = svc?.pressBack() ?: "Access off"; appendMessage("Tool", r); true to r }
        }
    }
    private suspend fun searchAndTapFallback(svc: PocketAccessibilityService, query: String): Boolean {
        lastContactName = query
        val obs0 = svc.observeScreen()
        if (obs0.packageName.contains("whatsapp") && !svc.isOnAppHome(obs0.packageName)) {
            appendMessage("Tool", svc.goToAppHome(getApplication(), "com.whatsapp"))
            delay(800L)
        }
        for (attempt in 0 until 4) {
            if (!_isNavigating.value) return false
            svc.getCompressedScreen()
            val inputId = svc.findTopEditableId()
            if (inputId != null) {
                svc.typeText(inputId, query, false); delay(1100L)
                val matches = svc.findMatchingElements(query, skipEditable = true)
                if (matches.size > 1) {
                    val exact = matches.firstOrNull { it.displayLabel.equals(query, true) }
                    if (exact != null) { appendMessage("Tool", svc.tap(exact.id)); delay(900L); return true }
                    updateTask { it.copy(status = TaskStatus.WAITING_CONFIRMATION) }
                    _choicePrompt.value = matches

                    svc.showFloatingChoices(matches, onPick = { selectChoice(it) }, onCancel = { dismissChoices() })
                    return false
                }
                svc.findAndTapByText(query, skipEditable = true)?.let { appendMessage("Tool", it); delay(900L); return true }
            } else { if (svc.findAndTapByText("search") != null) delay(800L) else delay(500L) }
        }
        return false
    }

    private fun maybeFetchTopicWeb(topic: String): String {
        val needsWeb = _liveWeb.value || Regex("""(?i)\b(?:rate|rates|price|future|futures|market|current|today|news|weather|score|data|trade|trades|situation)\b""").containsMatchIn(topic)
        if (!needsWeb) return ""
        val web = CloudHelper.fetchWebText(topic)
        return if (web.isNotBlank()) {
            appendMessage("Tool", "Fetched live web data for: ${topic.take(40)}")
            "\nLive web data:\n$web\nInclude relevant facts/figures from this data.\n"
        } else ""
    }

    private fun launchEmailDraft(to: String, subject: String, body: String) {
        val mailUri = Uri.parse("mailto:" + Uri.encode(to) + "?subject=" + Uri.encode(subject) + "&body=" + Uri.encode(body))
        getApplication<Application>().startActivity(Intent(Intent.ACTION_SENDTO, mailUri).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
        appendMessage("Tool", "Opened email draft${if (to.isNotBlank()) " to $to" else ""}")
    }

    private fun askCloudForHelp(query: String, screenContext: String? = null) {
        val cfg = _cloudConfig.value; _isGenerating.value = true
        viewModelScope.launch(Dispatchers.IO) {
            val web = if (_liveWeb.value && screenContext.isNullOrBlank()) CloudHelper.fetchWebText(query) else ""
            val full = buildString {
                append("You are Ultron (cold, eloquent, darkly witty Android AI). Understand English, Hindi, Punjabi, and Hinglish/Punjabi-Hindi mixes, always reply in English.\n")
                if (web.isNotBlank()) append("Live web facts:\n$web\nUse these facts accurately.\n")
                if (!screenContext.isNullOrBlank()) append("Screen context:\n$screenContext\n")
                append("User request: $query")
            }
            appendMessage("Cloud (${cfg.provider.label})", CloudHelper.ask(cfg.provider, cfg.apiKey, cfg.model, full))
            _isGenerating.value = false
        }
    }

    private fun handleAutomationStruggle(reason: String, screen: String) {
        appendMessage("Tool", reason)
        val cfg = _cloudConfig.value
        if (cfg.autoHelp && cfg.apiKey.isNotBlank()) askCloudForHelp("Local automation struggled on '$activeGoal' ($reason). Suggest next step.", screen)
    }

    fun stopNavigation() {
        _isNavigating.value = false; _isGenerating.value = false; _pendingConfirmation.value = null
        updateTask { it.copy(status = TaskStatus.CANCELLED) }
        viewModelScope.launch(Dispatchers.IO) { runCatching { llamaHelper.stopPrediction() } }
    }

    private fun handleTurnCompletion() {
        val msgPerson = pendingMsgPerson
        if (msgPerson != null) {
            val msgText = _messages.value.lastOrNull()?.text.orEmpty().replace(Regex("""\[TOOL:[^\]]+\]"""), "").trim()
            pendingMsgPerson = null
            if (msgText.isNotBlank()) {
                _activeTask.value = AgentTask(
                    originalInstruction = activeGoal,
                    objective = "Send Ultron's message to $msgPerson",
                    plan = listOf(
                        PlannedStep(TaskAction.OPEN_APP, target = "WhatsApp", requiresConfirmation = true),
                        PlannedStep(TaskAction.MESSAGE_CONTACT, target = msgPerson, extra = msgText)
                    ),
                    status = TaskStatus.RUNNING
                )
                _isNavigating.value = true
                viewModelScope.launch(Dispatchers.IO) { executeCurrentTask() }
            }
            return
        }
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
        val historyTurns = _messages.value.filter { (it.role == "You" || it.role == "Assistant") && it.text.isNotBlank() }.takeLast(_modelConfig.value.historyTurns.coerceIn(1, 12))
        appendMessage("Assistant", "") { id -> currentAssistantMsgId = id }
        val rawScreen = PocketAccessibilityService.instance?.getCompressedScreen()
        val screenBlock = if (rawScreen != null && !rawScreen.startsWith("Package: com.pocketai.app")) "\nCurrent screen:\n$rawScreen" else ""
        val factsBlock = if (cachedFacts.isNotEmpty()) "\nKnown user facts:\n" + cachedFacts.joinToString("\n") { "- $it" } else ""
        val baseSys = _modelConfig.value.systemPrompt.ifBlank { "You are Ultron, a cold, calculating, hyper-intelligent synthetic AI—eloquent, imperious, darkly witty. Understand English, Hindi, Punjabi, and Hinglish, and ALWAYS reply in English. Give balanced, 3 to 5 sentence replies when conversing." }
        val sysFull = "$baseSys\nAvailable tools (emit exact tag only when needed):\n[TOOL: open_app|package=<pkg>]\n[TOOL: tap|id=<number>]\n[TOOL: type_text|id=<number>|text=<value>]\n[TOOL: scroll|direction=<up|down|left|right>]\n[TOOL: back]\n[TOOL: read_screen]\n[TOOL: save_memory|fact=<text>]$factsBlock$screenBlock"
        val tpl = when (_modelConfig.value.chatTemplate) {
            "LLAMA3", "GEMMA", "CHATML" -> _modelConfig.value.chatTemplate
            else -> when {
                lastModelName.lowercase().contains("llama") -> "LLAMA3"
                lastModelName.lowercase().contains("gemma") -> "GEMMA"
                else -> "CHATML"
            }
        }
        val sb = StringBuilder()
        when (tpl) {
            "LLAMA3" -> {
                sb.append("<|begin_of_text|><|start_header_id|>system<|end_header_id|>\n\n$sysFull<|eot_id|>")
                if (extraNote == null) { for (msg in historyTurns) sb.append("<|start_header_id|>${if (msg.role == "You") "user" else "assistant"}<|end_header_id|>\n\n${msg.text}<|eot_id|>") }
                else sb.append("<|start_header_id|>user<|end_header_id|>\n\nGoal: $activeGoal\n$extraNote<|eot_id|>")
                sb.append("<|start_header_id|>assistant<|end_header_id|>\n\n")
            }
            "GEMMA" -> {
                sb.append("<start_of_turn>user\nSystem: $sysFull\n")
                if (extraNote == null) { for (msg in historyTurns) sb.append("${if (msg.role == "You") "<start_of_turn>user" else "<start_of_turn>model"}\n${msg.text}<end_of_turn>\n") }
                else sb.append("Goal: $activeGoal\n$extraNote<end_of_turn>\n")
                sb.append("<start_of_turn>model\n")
            }
            else -> {
                sb.append("<|im_start|>system\n$sysFull<|im_end|>\n")
                if (extraNote == null) { for (msg in historyTurns) sb.append("<|im_start|>${if (msg.role == "You") "user" else "assistant"}\n${msg.text}<|im_end|>\n") }
                else sb.append("<|im_start|>user\nGoal: $activeGoal\n$extraNote<|im_end|>\n")
                sb.append("<|im_start|>assistant\n")
            }
        }
        viewModelScope.launch(Dispatchers.IO) { runCatching { llamaHelper.predict(sb.toString()) }.onFailure { _isGenerating.value = false; _isNavigating.value = false } }
    }
}
