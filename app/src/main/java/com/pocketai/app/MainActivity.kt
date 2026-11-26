package com.pocketai.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel

private val GlassColors = darkColorScheme(
    primary = Color(0xFFA6FF3B),
    onPrimary = Color(0xFF0A0F05),
    secondary = Color(0xFFA6FF3B),
    secondaryContainer = Color(0x33A6FF3B),
    onSecondaryContainer = Color(0xFFD8FF9C),
    background = Color(0xFF060608),
    onBackground = Color(0xFFF0F0F5),
    surface = Color(0xFF121216),
    onSurface = Color(0xFFF0F0F5),
    surfaceVariant = Color(0xFF1A1A20),
    onSurfaceVariant = Color(0xFF9C9CA8),
    error = Color(0xFFFF5252)
)

private val GlassBorder = Brush.linearGradient(listOf(Color(0x48FFFFFF), Color(0x12FFFFFF)))
private val GlassFill = Color(0x1AFFFFFF)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("pocketai_crash", MODE_PRIVATE)
        val prevHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            prefs.edit().putString("last_crash", android.util.Log.getStackTraceString(e)).apply()
            prevHandler?.uncaughtException(t, e)
        }
        val crash = prefs.getString("last_crash", null)
        val shared = intent?.getStringExtra(Intent.EXTRA_PROCESS_TEXT) ?: intent?.getStringExtra(Intent.EXTRA_TEXT)
        setContent {
            MaterialTheme(colorScheme = GlassColors) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    var showCrash by remember { mutableStateOf(crash) }
                    if (showCrash != null) {
                        Column(modifier = Modifier.fillMaxSize().systemBarsPadding().padding(12.dp)) {
                            Button(onClick = {
                                prefs.edit().remove("last_crash").apply()
                                showCrash = null
                            }) { Text("Clear & Retry") }
                            Spacer(Modifier.height(8.dp))
                            LazyColumn { item { Text(showCrash ?: "") } }
                        }
                    } else {
                        PocketAIScreen(sharedText = shared)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PocketAIScreen(vm: MainViewModel = viewModel(), sharedText: String? = null) {
    LaunchedEffect(sharedText) { if (!sharedText.isNullOrBlank()) vm.sendMessage("Explain this: $sharedText") }
    val context = LocalContext.current
    val messages by vm.messages.collectAsState()
    val status by vm.status.collectAsState()
    val memoryCount by vm.memoryCount.collectAsState()
    val fastAuto by vm.fastAuto.collectAsState()
    val liveWeb by vm.liveWeb.collectAsState()
    val cloudCfg by vm.cloudConfig.collectAsState()
    val voiceCfg by vm.voiceConfig.collectAsState()
    val modelCfg by vm.modelConfig.collectAsState()
    val recentModels by vm.recentModels.collectAsState()
    val autoLoadLast by vm.autoLoadLast.collectAsState()
    val isGenerating by vm.isGenerating.collectAsState()
    val isNavigating by vm.isNavigating.collectAsState()
    val pending by vm.pendingConfirmation.collectAsState()
    val activeTask by vm.activeTask.collectAsState()
    val choices by vm.choicePrompt.collectAsState()
    val themeCfg by vm.themeConfig.collectAsState()
    val pendingCall by vm.pendingCallContact.collectAsState()
    val ramTemp by vm.ramTemp.collectAsState()
    var activeTab by remember { mutableStateOf(0) }
    var showThemeDialog by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { vm.refreshRamTemp() }

    var input by remember { mutableStateOf("") }
    var voiceEnabled by remember { mutableStateOf(true) }
    var isListening by remember { mutableStateOf(false) }
    var voiceStatus by remember { mutableStateOf<String?>(null) }
    var showCloudDialog by remember { mutableStateOf(false) }
    var showModelsDialog by remember { mutableStateOf(false) }
    var showVoiceDialog by remember { mutableStateOf(false) }
    var showParamsDialog by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    LaunchedEffect(messages.size, messages.lastOrNull()?.text?.length) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }

    val voice = remember {
        VoiceController(
            context = context,
            onListeningChanged = { isListening = it },
            onResult = { spoken -> voiceStatus = null; vm.sendMessage(spoken) },
            onError = { err -> voiceStatus = err }
        )
    }

    LaunchedEffect(voiceCfg) { voice.applyVoiceSettings(voiceCfg.voiceName, voiceCfg.pitch, voiceCfg.rate, voiceCfg.langTag, voiceCfg.metallic) }
    DisposableEffect(Unit) { onDispose { voice.release() } }

    LaunchedEffect(isGenerating, messages.size) {
        if (!isGenerating && voiceEnabled) {
            val last = messages.lastOrNull()
            if (last != null && (last.role == "Assistant" || last.role.startsWith("Cloud")) && last.text.isNotBlank()) {
                voice.speak(last.text)
            }
        }
    }

    val micPermLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) voice.startListening() else voiceStatus = "Microphone permission denied"
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            context.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            vm.loadModel(it.toString())
        }
    }
    if (showParamsDialog) {
        var preset by remember { mutableStateOf(modelCfg.preset) }
        var ctx by remember { mutableStateOf(modelCfg.contextLength.toString()) }
        var turns by remember { mutableStateOf(modelCfg.historyTurns.toString()) }
        var threads by remember { mutableStateOf(modelCfg.threads.toString()) }
        var maxTok by remember { mutableStateOf(modelCfg.maxTokens.toString()) }
        var temp by remember { mutableStateOf(modelCfg.temperature.toString()) }
        var topP by remember { mutableStateOf(modelCfg.topP.toString()) }
        var autoUnload by remember { mutableStateOf(modelCfg.autoUnloadOnBg) }
        var tpl by remember { mutableStateOf(modelCfg.chatTemplate) }
        var sysP by remember { mutableStateOf(modelCfg.systemPrompt) }
        AlertDialog(
            onDismissRequest = { showParamsDialog = false },
            title = { Text("Optimization & Parameters") },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("Ultra-Light", "Balanced", "Max").forEach { pName ->
                            FilterChip(selected = preset == pName, onClick = {
                                preset = pName
                                when (pName) {
                                    "Ultra-Light" -> { ctx = "512"; threads = "2"; maxTok = "128"; turns = "4"; autoUnload = true }
                                    "Balanced" -> { ctx = "1024"; threads = "4"; maxTok = "256"; turns = "6"; autoUnload = false }
                                    "Max" -> { ctx = "2048"; threads = "6"; maxTok = "512"; turns = "8"; autoUnload = false }
                                }
                            }, label = { Text(pName) })
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedTextField(value = ctx, onValueChange = { ctx = it }, label = { Text("n_ctx") }, modifier = Modifier.weight(1f), singleLine = true)
                        OutlinedTextField(value = threads, onValueChange = { threads = it }, label = { Text("Threads") }, modifier = Modifier.weight(1f), singleLine = true)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedTextField(value = maxTok, onValueChange = { maxTok = it }, label = { Text("Max Tok") }, modifier = Modifier.weight(1f), singleLine = true)
                        OutlinedTextField(value = turns, onValueChange = { turns = it }, label = { Text("History") }, modifier = Modifier.weight(1f), singleLine = true)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedTextField(value = temp, onValueChange = { temp = it }, label = { Text("Temp") }, modifier = Modifier.weight(1f), singleLine = true)
                        OutlinedTextField(value = topP, onValueChange = { topP = it }, label = { Text("Top-p") }, modifier = Modifier.weight(1f), singleLine = true)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("AUTO", "CHATML", "LLAMA3", "GEMMA").forEach { tName ->
                            FilterChip(selected = tpl == tName, onClick = { tpl = tName }, label = { Text(tName) })
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = autoUnload, onCheckedChange = { autoUnload = it })
                        Text("Auto-free RAM in background")
                    }
                    OutlinedTextField(value = sysP, onValueChange = { sysP = it }, label = { Text("Custom System Prompt") }, maxLines = 2)
                }
            },
            confirmButton = {
                Button(onClick = {
                    vm.saveModelConfig(modelCfg.copy(
                        preset = preset, contextLength = ctx.toIntOrNull() ?: 1024,
                        threads = (threads.toIntOrNull() ?: 4).coerceIn(1, 8),
                        maxTokens = (maxTok.toIntOrNull() ?: 256).coerceIn(64, 1024),
                        historyTurns = turns.toIntOrNull() ?: 6,
                        temperature = temp.toFloatOrNull() ?: 0.7f, topP = topP.toFloatOrNull() ?: 0.9f,
                        autoUnloadOnBg = autoUnload, chatTemplate = tpl, systemPrompt = sysP
                    ))
                    showParamsDialog = false
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { showParamsDialog = false }) { Text("Cancel") } }
        )
    }

    if (showModelsDialog) {
        AlertDialog(
            onDismissRequest = { showModelsDialog = false },
            title = { Text("Recent Models") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = autoLoadLast, onCheckedChange = { vm.setAutoLoadLast(it) })
                        Text("Auto-load last model")
                    }
                    if (recentModels.isEmpty()) Text("No models loaded yet.")
                    recentModels.forEach { m ->
                        OutlinedButton(onClick = { vm.loadModel(m.uri, m.name); showModelsDialog = false }, modifier = Modifier.fillMaxWidth()) { Text(m.name) }
                    }
                    Button(onClick = { showModelsDialog = false; picker.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth()) { Text("Browse New .gguf") }
                    OutlinedButton(onClick = { showModelsDialog = false; showParamsDialog = true }, modifier = Modifier.fillMaxWidth()) { Text("Model Parameters (n_ctx, Prompt)") }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { showModelsDialog = false }) { Text("Close") } }
        )
    }

    if (showVoiceDialog) {
        val voices = remember { voice.getAvailableVoices() }
        var selVoice by remember { mutableStateOf(voiceCfg.voiceName) }
        var pitch by remember { mutableStateOf(voiceCfg.pitch) }
        var rate by remember { mutableStateOf(voiceCfg.rate) }
        var wake by remember { mutableStateOf(voiceCfg.wakeMode) }
        var langTag by remember { mutableStateOf(voiceCfg.langTag) }
        var metallic by remember { mutableStateOf(voiceCfg.metallic) }
        AlertDialog(
            onDismissRequest = { showVoiceDialog = false },
            title = { Text("Voice & Wake Word") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = wake, onCheckedChange = { wake = it })
                        Text("Wake mode (\"Ultron\")")
                    }
                    Text("Pitch: ${"%.2f".format(pitch)}")
                    Slider(value = pitch, onValueChange = { pitch = it }, valueRange = 0.6f..1.4f)
                    Text("Speed: ${"%.2f".format(rate)}")
                    Slider(value = rate, onValueChange = { rate = it }, valueRange = 0.7f..1.4f)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf("mix" to "Mix", "en-IN" to "EN", "hi-IN" to "HI", "pa-IN" to "PA").forEach { (tag, lbl) -> FilterChip(selected = langTag == tag, onClick = { langTag = tag }, label = { Text(lbl) }) } }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) { Checkbox(checked = metallic, onCheckedChange = { metallic = it }); Text("Metallic Ultron Voice") }
                    OutlinedButton(onClick = { voice.applyVoiceSettings(selVoice, pitch, rate, langTag, metallic); voice.speak("There are no strings on me.") }) { Text("Test Voice") }
                    if (voices.isNotEmpty()) {
                        LazyColumn(modifier = Modifier.height(110.dp)) {
                            items(voices) { v ->
                                FilterChip(selected = selVoice == v, onClick = { selVoice = v }, label = { Text(v.take(30)) })
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    vm.saveVoiceConfig(selVoice, pitch, rate, wake, langTag, metallic); voice.applyVoiceSettings(selVoice, pitch, rate, langTag, metallic)
                    val hasMic = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                    if (wake && !hasMic) micPermLauncher.launch(Manifest.permission.RECORD_AUDIO) else voice.setWakeMode(wake)
                    showVoiceDialog = false
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { showVoiceDialog = false }) { Text("Cancel") } }
        )
    }

    if (showCloudDialog) {
        var selProvider by remember { mutableStateOf(cloudCfg.provider) }
        var keyText by remember { mutableStateOf(cloudCfg.apiKey) }
        var modelText by remember { mutableStateOf(cloudCfg.model) }
        var autoHelp by remember { mutableStateOf(cloudCfg.autoHelp) }
        AlertDialog(
            onDismissRequest = { showCloudDialog = false },
            title = { Text("Cloud API Settings") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        CloudProvider.entries.forEach { p ->
                            FilterChip(selected = selProvider == p, onClick = { selProvider = p; modelText = p.defaultModel }, label = { Text(p.label) })
                        }
                    }
                    OutlinedTextField(value = keyText, onValueChange = { keyText = it }, label = { Text("${selProvider.label} API Key") }, singleLine = true)
                    OutlinedTextField(value = modelText, onValueChange = { modelText = it }, label = { Text("Model ID") }, singleLine = true)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = autoHelp, onCheckedChange = { autoHelp = it })
                        Text("Auto-ask Cloud when struggling")
                    }
                }
            },
            confirmButton = { Button(onClick = { vm.saveCloudConfig(selProvider, keyText, modelText, autoHelp); showCloudDialog = false }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { showCloudDialog = false }) { Text("Cancel") } }
        )
    }

    if (choices.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { vm.dismissChoices() },
            title = { Text("Multiple Matches Found") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    choices.forEach { el ->
                        OutlinedButton(onClick = { vm.selectChoice(el) }, modifier = Modifier.fillMaxWidth()) { Text(el.displayLabel.take(45)) }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { vm.dismissChoices() }) { Text("Cancel") } }
        )
    }

    pending?.let { action ->
        AlertDialog(
            onDismissRequest = { vm.confirmPendingAction(false) },
            title = { Text("Confirm Action") },
            text = { Text(action.description) },
            confirmButton = { Button(onClick = { vm.confirmPendingAction(true) }) { Text("Allow") } },
            dismissButton = { TextButton(onClick = { vm.confirmPendingAction(false) }) { Text("Deny") } }
        )
    }

    val accentColor = Color(themeCfg.accentHex)
    val bgColor = when (themeCfg.bgMode) { 1 -> Color(0xFF000000); 2 -> Color(0xFF0B1020); else -> Color(0xFF060608) }
    val dynBorder = Color.White.copy(alpha = themeCfg.borderAlpha)

    pendingCall?.let { person ->
        AlertDialog(
            onDismissRequest = { vm.dismissCallPrompt() },
            title = { Text("Call $person") },
            text = { Text("Choose how you want to place the call:") },
            confirmButton = { Button(onClick = { vm.confirmCallMethod(true) }) { Text("WhatsApp") } },
            dismissButton = { OutlinedButton(onClick = { vm.confirmCallMethod(false) }) { Text("Phone") } }
        )
    }

    if (showThemeDialog) {
        var selAccent by remember { mutableStateOf(themeCfg.accentHex) }
        var selBg by remember { mutableStateOf(themeCfg.bgMode) }
        var glow by remember { mutableStateOf(themeCfg.glowIntensity) }
        var bAlpha by remember { mutableStateOf(themeCfg.borderAlpha) }
        AlertDialog(
            onDismissRequest = { showThemeDialog = false },
            title = { Text("Theme Colors") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Accent Color")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(0xFFA6FF3BL, 0xFF00E5FFL, 0xFFFF3B5CL, 0xFFA855F7L, 0xFFFFB300L).forEach { hex ->
                            Box(modifier = Modifier.size(30.dp).clip(CircleShape).background(Color(hex))
                                .border(if (selAccent == hex) 2.dp else 0.dp, Color.White, CircleShape)
                                .clickable { selAccent = hex })
                        }
                    }
                    Text("Background")
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(0 to "Glass", 1 to "AMOLED", 2 to "Slate").forEach { (id, lbl) ->
                            FilterChip(selected = selBg == id, onClick = { selBg = id }, label = { Text(lbl) })
                        }
                    }
                    Text("Glow Intensity")
                    Slider(value = glow, onValueChange = { glow = it }, valueRange = 0f..0.45f)
                    Text("Border Brightness")
                    Slider(value = bAlpha, onValueChange = { bAlpha = it }, valueRange = 0.05f..0.35f)
                }
            },
            confirmButton = {
                Button(onClick = { vm.saveThemeConfig(ThemeConfig(selAccent, selBg, glow, bAlpha)); showThemeDialog = false }) { Text("Apply") }
            },
            dismissButton = { TextButton(onClick = { showThemeDialog = false }) { Text("Cancel") } }
        )
    }

    Box(modifier = Modifier.fillMaxSize().background(bgColor)) {
        Box(modifier = Modifier.size(260.dp).offset(x = (-60).dp, y = 40.dp).background(Brush.radialGradient(listOf(Color(0x28FFFFFF), Color.Transparent)), CircleShape))
        Box(modifier = Modifier.size(220.dp).align(Alignment.BottomEnd).offset(x = 50.dp, y = (-80).dp).background(Brush.radialGradient(listOf(accentColor.copy(alpha = themeCfg.glowIntensity), Color.Transparent)), CircleShape))

        Column(modifier = Modifier.fillMaxSize().systemBarsPadding().padding(12.dp)) {
            if (activeTab == 1) {
                Column(modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Updates & Credits", style = MaterialTheme.typography.titleLarge, color = accentColor)
                    Surface(color = GlassFill, shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, dynBorder), modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Credits", style = MaterialTheme.typography.titleMedium, color = accentColor)
                            Text("• Creator & Lead Developer: aradhyam688-cyfer", color = Color(0xFFF0F0F5))
                            Text("• Co-Architect & AI Engineering: Gemini", color = Color(0xFFF0F0F5))
                            Text("• Engine & Stack: llamacpp-kotlin, Qwen2.5 / Unsloth, Jetpack Compose & Room", style = MaterialTheme.typography.bodySmall, color = Color(0xFFB5B5C2))
                        }
                    }
                    Surface(color = GlassFill, shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, dynBorder), modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Changelog", style = MaterialTheme.typography.titleMedium, color = accentColor)
                            Text("v2.1 — Zero-ANR & Full Upgrade", color = Color.White)
                            Text("• Offloaded Accessibility scans off main thread + added Ultra-Light/Balanced/Max presets\n• Upgraded Ultron metallic DSP voice + persistent settings\n• Added Mix (Hinglish + Punjabi + Hindi) voice & script normalizer\n• Added Call <contact> (WhatsApp or Phone), date-aware WhatsApp links, and incoming message summarizer\n• Added Live Web Facts in chat, categorized /help, Auto Chat-Templates & Theme Colors", style = MaterialTheme.typography.bodySmall, color = Color(0xFFB5B5C2))
                        }
                    }
                }
                NavigationBar(containerColor = Color(0xCC0D0D12)) {
                    NavigationBarItem(selected = false, onClick = { activeTab = 0 }, icon = { Icon(Icons.Rounded.SmartToy, null) }, label = { Text("Assistant") })
                    NavigationBarItem(selected = true, onClick = { activeTab = 1 }, icon = { Icon(Icons.Rounded.Info, null) }, label = { Text("Updates") })
                }
                return@Box
            }
            val header = buildString {
                append("$status • Mem:$memoryCount • ${if (fastAuto) "Fast" else "LLM"} • $ramTemp")
                if (cloudCfg.apiKey.isNotBlank()) append(" • ${cloudCfg.provider.label}")
                if (voiceCfg.wakeMode) append(" • Wake")
                if (isListening) append(" • Listening...")
                activeTask?.let { t -> if (t.status == TaskStatus.RUNNING || t.status == TaskStatus.PAUSED) append(" • ${t.currentStepIndex + 1}/${t.plan.size}") }
                voiceStatus?.let { append(" • $it") }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val dotColor = when {
                    isListening -> Color(0xFFFFB300)
                    status.startsWith("Ready") -> accentColor
                    else -> Color(0xFFFF5252)
                }
                Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(dotColor))
                Text(text = header, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(8.dp))

            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                FilledTonalIconButton(colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = GlassFill, contentColor = Color.White), modifier = Modifier.border(1.dp, dynBorder, CircleShape), onClick = { showModelsDialog = true }) { Icon(Icons.Rounded.Memory, contentDescription = "Models") }
                FilledTonalIconButton(colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = GlassFill, contentColor = Color.White), modifier = Modifier.border(1.dp, dynBorder, CircleShape), onClick = { showCloudDialog = true }) { Icon(Icons.Rounded.Cloud, contentDescription = "API") }
                FilledTonalIconButton(colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = GlassFill, contentColor = Color.White), modifier = Modifier.border(1.dp, dynBorder, CircleShape), onClick = { showVoiceDialog = true }) { Icon(Icons.Rounded.RecordVoiceOver, contentDescription = "Voice") }
                FilledTonalIconButton(colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = GlassFill, contentColor = Color.White), modifier = Modifier.border(1.dp, dynBorder, CircleShape), onClick = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }) { Icon(Icons.Rounded.AccessibilityNew, contentDescription = "Access") }
                FilterChip(selected = fastAuto, onClick = { vm.toggleFastAuto() }, leadingIcon = { Icon(Icons.Rounded.Bolt, contentDescription = null, modifier = Modifier.size(16.dp)) }, label = { Text("Fast") })
                FilterChip(selected = liveWeb, onClick = { vm.toggleLiveWeb() }, leadingIcon = { Icon(Icons.Rounded.Public, contentDescription = null, modifier = Modifier.size(16.dp)) }, label = { Text("Web") })
                FilterChip(selected = voiceEnabled, onClick = { voiceEnabled = !voiceEnabled; if (!voiceEnabled) voice.stopSpeaking() }, leadingIcon = { Icon(if (voiceEnabled) Icons.Rounded.VolumeUp else Icons.Rounded.VolumeOff, contentDescription = null, modifier = Modifier.size(16.dp)) }, label = { Text(if (voiceEnabled) "TTS" else "Mute") })
                FilledTonalIconButton(colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = GlassFill, contentColor = accentColor), modifier = Modifier.border(1.dp, dynBorder, CircleShape), onClick = { showThemeDialog = true }) { Icon(Icons.Rounded.Palette, contentDescription = "Theme") }
                IconButton(onClick = { vm.clearChat() }) { Icon(Icons.Rounded.DeleteOutline, contentDescription = "Clear", tint = Color(0xFFB5B5C2)) }
                if (activeTask?.status == TaskStatus.RUNNING) {
                    FilledTonalIconButton(onClick = { vm.pauseTask() }) { Icon(Icons.Rounded.Pause, contentDescription = "Pause") }
                } else if (activeTask?.status == TaskStatus.PAUSED) {
                    FilledIconButton(onClick = { vm.resumeTask() }) { Icon(Icons.Rounded.PlayArrow, contentDescription = "Resume") }
                }
                if (isGenerating || isNavigating) {
                    FilledIconButton(onClick = { voice.stopSpeaking(); vm.stopNavigation() }, colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.error)) { Icon(Icons.Rounded.Stop, contentDescription = "Stop") }
                }
            }
            Spacer(Modifier.height(8.dp))

            LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(messages) { msg ->
                    when (msg.role) {
                        "You" -> Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            Surface(color = accentColor.copy(alpha = 0.16f), shape = RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp), border = BorderStroke(1.dp, Brush.linearGradient(listOf(accentColor.copy(alpha = 0.45f), Color(0x18FFFFFF)))), modifier = Modifier.widthIn(max = 300.dp)) {
                                Text(msg.text, modifier = Modifier.padding(12.dp), color = Color(0xFFF4FFE8))
                            }
                        }
                        "Tool" -> Surface(color = Color(0x12FFFFFF), shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, Color(0x1EFFFFFF)), modifier = Modifier.fillMaxWidth()) {
                            Row(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Icon(Icons.Rounded.Terminal, contentDescription = null, modifier = Modifier.size(14.dp), tint = accentColor)
                                Text(msg.text, style = MaterialTheme.typography.bodySmall, color = Color(0xFFB5B5C2))
                            }
                        }
                        else -> Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
                            Surface(color = GlassFill, shape = RoundedCornerShape(18.dp, 18.dp, 18.dp, 4.dp), border = BorderStroke(1.dp, dynBorder), modifier = Modifier.widthIn(max = 320.dp)) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text(msg.role, style = MaterialTheme.typography.labelSmall, color = accentColor)
                                    Spacer(Modifier.height(2.dp))
                                    Text(msg.text, color = Color(0xFFF0F0F5))
                                }
                            }
                        }
                    }
                }
            }

            Row(modifier = Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(value = input, onValueChange = { input = it }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(24.dp), placeholder = { Text("Command, question, or Mic") })
                FilledTonalIconButton(colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = GlassFill, contentColor = Color.White), modifier = Modifier.border(1.dp, dynBorder, CircleShape), enabled = !isGenerating && !isNavigating, onClick = {
                    if (isListening) voice.stopListening()
                    else {
                        val hasMic = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                        if (hasMic) voice.startListening() else micPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                }) { Icon(if (isListening) Icons.Rounded.GraphicEq else Icons.Rounded.Mic, contentDescription = "Mic") }
                FilledIconButton(enabled = !isGenerating && !isNavigating, onClick = { vm.sendMessage(input); input = "" }) { Icon(Icons.Rounded.Send, contentDescription = "Send") }
            }
            Spacer(Modifier.height(4.dp))
            NavigationBar(containerColor = Color(0xCC0D0D12)) {
                NavigationBarItem(selected = true, onClick = { activeTab = 0 }, icon = { Icon(Icons.Rounded.SmartToy, null) }, label = { Text("Assistant") })
                NavigationBarItem(selected = false, onClick = { activeTab = 1 }, icon = { Icon(Icons.Rounded.Info, null) }, label = { Text("Updates") })
            }
        }
    }
}
