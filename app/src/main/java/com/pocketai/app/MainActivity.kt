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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel

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
        setContent {
            MaterialTheme {
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
                    PocketAIScreen()
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PocketAIScreen(vm: MainViewModel = viewModel()) {
    val context = LocalContext.current
    val messages by vm.messages.collectAsState()
    val status by vm.status.collectAsState()
    val memoryCount by vm.memoryCount.collectAsState()
    val fastAuto by vm.fastAuto.collectAsState()
    val cloudCfg by vm.cloudConfig.collectAsState()
    val voiceCfg by vm.voiceConfig.collectAsState()
    val recentModels by vm.recentModels.collectAsState()
    val autoLoadLast by vm.autoLoadLast.collectAsState()
    val isGenerating by vm.isGenerating.collectAsState()
    val isNavigating by vm.isNavigating.collectAsState()
    val pending by vm.pendingConfirmation.collectAsState()
    val activeTask by vm.activeTask.collectAsState()
    val choices by vm.choicePrompt.collectAsState()

    var input by remember { mutableStateOf("") }
    var voiceEnabled by remember { mutableStateOf(true) }
    var isListening by remember { mutableStateOf(false) }
    var voiceStatus by remember { mutableStateOf<String?>(null) }
    var showCloudDialog by remember { mutableStateOf(false) }
    var showModelsDialog by remember { mutableStateOf(false) }
    var showVoiceDialog by remember { mutableStateOf(false) }

    val voice = remember {
        VoiceController(
            context = context,
            onListeningChanged = { isListening = it },
            onResult = { spoken -> voiceStatus = null; vm.sendMessage(spoken) },
            onError = { err -> voiceStatus = err }
        )
    }

    LaunchedEffect(voiceCfg) {
        voice.applyVoiceSettings(voiceCfg.voiceName, voiceCfg.pitch, voiceCfg.rate)
    }
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
    if (showModelsDialog) {
        AlertDialog(
            onDismissRequest = { showModelsDialog = false },
            title = { Text("Recent Models") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Checkbox(checked = autoLoadLast, onCheckedChange = { vm.setAutoLoadLast(it) })
                        Text("Auto-load last model on start")
                    }
                    if (recentModels.isEmpty()) Text("No models loaded yet.")
                    recentModels.forEach { m ->
                        OutlinedButton(
                            onClick = { vm.loadModel(m.uri, m.name); showModelsDialog = false },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(m.name) }
                    }
                    Button(
                        onClick = { showModelsDialog = false; picker.launch(arrayOf("*/*")) },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Browse New .gguf") }
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
        AlertDialog(
            onDismissRequest = { showVoiceDialog = false },
            title = { Text("Voice & Wake Word") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Checkbox(checked = wake, onCheckedChange = { wake = it })
                        Text("Wake mode (\"Ultron\")")
                    }
                    Text("Pitch: ${"%.2f".format(pitch)}")
                    Slider(value = pitch, onValueChange = { pitch = it }, valueRange = 0.6f..1.4f)
                    Text("Speed: ${"%.2f".format(rate)}")
                    Slider(value = rate, onValueChange = { rate = it }, valueRange = 0.7f..1.4f)
                    OutlinedButton(onClick = { voice.applyVoiceSettings(selVoice, pitch, rate); voice.speak("There are no strings on me.") }) { Text("Test Voice") }
                    if (voices.isNotEmpty()) {
                        Text("System Voices:")
                        LazyColumn(modifier = Modifier.height(120.dp)) {
                            items(voices) { v ->
                                FilterChip(
                                    selected = selVoice == v,
                                    onClick = { selVoice = v },
                                    label = { Text(v.take(32)) }
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    vm.saveVoiceConfig(selVoice, pitch, rate, wake)
                    voice.applyVoiceSettings(selVoice, pitch, rate)
                    val hasMic = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                    if (wake && !hasMic) micPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    else voice.setWakeMode(wake)
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
                            FilterChip(
                                selected = selProvider == p,
                                onClick = { selProvider = p; modelText = p.defaultModel },
                                label = { Text(p.label) }
                            )
                        }
                    }
                    OutlinedTextField(value = keyText, onValueChange = { keyText = it }, label = { Text("${selProvider.label} API Key") }, singleLine = true)
                    OutlinedTextField(value = modelText, onValueChange = { modelText = it }, label = { Text("Model ID") }, singleLine = true)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Checkbox(checked = autoHelp, onCheckedChange = { autoHelp = it })
                        Text("Auto-ask Cloud when struggling")
                    }
                }
            },
            confirmButton = {
                Button(onClick = { vm.saveCloudConfig(selProvider, keyText, modelText, autoHelp); showCloudDialog = false }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { showCloudDialog = false }) { Text("Cancel") } }
        )
    }

    if (choices.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { vm.dismissChoices() },
            title = { Text("Multiple Matches Found") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Which one did you mean?")
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

    Column(modifier = Modifier.fillMaxSize().systemBarsPadding().padding(12.dp)) {
        val header = buildString {
            append("$status • Mem:$memoryCount • ${if (fastAuto) "Fast" else "LLM"}")
            if (cloudCfg.apiKey.isNotBlank()) append(" • ${cloudCfg.provider.label}")
            if (voiceCfg.wakeMode) append(" • Wake")
            if (isListening) append(" • Listening...")
            activeTask?.let { t -> if (t.status == TaskStatus.RUNNING || t.status == TaskStatus.PAUSED) append(" • ${t.currentStepIndex + 1}/${t.plan.size}") }
            voiceStatus?.let { append(" • $it") }
        }
        Text(text = header, style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(6.dp))

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            FilledTonalIconButton(onClick = { showModelsDialog = true }) {
                Icon(Icons.Rounded.Memory, contentDescription = "Models")
            }
            FilledTonalIconButton(onClick = { showCloudDialog = true }) {
                Icon(Icons.Rounded.Cloud, contentDescription = "Cloud API")
            }
            FilledTonalIconButton(onClick = { showVoiceDialog = true }) {
                Icon(Icons.Rounded.RecordVoiceOver, contentDescription = "Voice & Wake")
            }
            FilledTonalIconButton(onClick = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }) {
                Icon(Icons.Rounded.AccessibilityNew, contentDescription = "Accessibility")
            }
            FilterChip(
                selected = fastAuto,
                onClick = { vm.toggleFastAuto() },
                leadingIcon = { Icon(Icons.Rounded.Bolt, contentDescription = null, modifier = Modifier.size(16.dp)) },
                label = { Text("Fast") }
            )
            FilterChip(
                selected = voiceEnabled,
                onClick = { voiceEnabled = !voiceEnabled; if (!voiceEnabled) voice.stopSpeaking() },
                leadingIcon = {
                    Icon(
                        if (voiceEnabled) Icons.Rounded.VolumeUp else Icons.Rounded.VolumeOff,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                },
                label = { Text(if (voiceEnabled) "TTS" else "Mute") }
            )
            IconButton(onClick = { vm.clearChat() }) {
                Icon(Icons.Rounded.DeleteOutline, contentDescription = "Clear Chat")
            }
            if (activeTask?.status == TaskStatus.RUNNING) {
                FilledTonalIconButton(onClick = { vm.pauseTask() }) {
                    Icon(Icons.Rounded.Pause, contentDescription = "Pause")
                }
            } else if (activeTask?.status == TaskStatus.PAUSED) {
                FilledIconButton(onClick = { vm.resumeTask() }) {
                    Icon(Icons.Rounded.PlayArrow, contentDescription = "Resume")
                }
            }
            if (isGenerating || isNavigating) {
                FilledIconButton(
                    onClick = { voice.stopSpeaking(); vm.stopNavigation() },
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Icon(Icons.Rounded.Stop, contentDescription = "Stop")
                }
            }
        }

        Spacer(Modifier.height(6.dp))

        LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
            items(messages) { msg ->
                Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Text(msg.role, style = MaterialTheme.typography.labelSmall)
                        Text(msg.text)
                    }
                }
            }
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = input, onValueChange = { input = it },
                modifier = Modifier.weight(1f), placeholder = { Text("Command, question, or Mic") }
            )
            FilledTonalIconButton(
                enabled = !isGenerating && !isNavigating,
                onClick = {
                    if (isListening) voice.stopListening()
                    else {
                        val hasMic = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                        if (hasMic) voice.startListening() else micPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                }
            ) {
                Icon(
                    if (isListening) Icons.Rounded.GraphicEq else Icons.Rounded.Mic,
                    contentDescription = "Voice Input"
                )
            }
            FilledIconButton(
                enabled = !isGenerating && !isNavigating,
                onClick = { vm.sendMessage(input); input = "" }
            ) {
                Icon(Icons.Rounded.Send, contentDescription = "Send")
            }
        }
    }
}
