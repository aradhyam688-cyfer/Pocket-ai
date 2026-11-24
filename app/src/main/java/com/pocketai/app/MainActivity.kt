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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
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
        setContent {
            MaterialTheme {
                PocketAIScreen()
            }
        }
    }
}

@Composable
fun PocketAIScreen(vm: MainViewModel = viewModel()) {
    val context = LocalContext.current
    val messages by vm.messages.collectAsState()
    val status by vm.status.collectAsState()
    val memoryCount by vm.memoryCount.collectAsState()
    val fastAuto by vm.fastAuto.collectAsState()
    val isGenerating by vm.isGenerating.collectAsState()
    val isNavigating by vm.isNavigating.collectAsState()
    val pending by vm.pendingConfirmation.collectAsState()

    var input by remember { mutableStateOf("") }
    var voiceEnabled by remember { mutableStateOf(true) }
    var isListening by remember { mutableStateOf(false) }
    var voiceStatus by remember { mutableStateOf<String?>(null) }

    val voice = remember {
        VoiceController(
            context = context,
            onListeningChanged = { isListening = it },
            onResult = { spoken ->
                voiceStatus = null
                vm.sendMessage(spoken)
            },
            onError = { err -> voiceStatus = err }
        )
    }

    DisposableEffect(Unit) {
        onDispose { voice.release() }
    }

    // Speak the latest Assistant reply when generation finishes
    LaunchedEffect(isGenerating, messages.size) {
        if (!isGenerating && voiceEnabled) {
            val last = messages.lastOrNull()
            if (last != null && last.role == "Assistant" && last.text.isNotBlank()) {
                voice.speak(last.text)
            }
        }
    }

    val micPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) voice.startListening() else voiceStatus = "Microphone permission denied"
    }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            context.contentResolver.takePersistableUriPermission(
                it, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            vm.loadModel(it.toString())
        }
    }

    pending?.let { action ->
        AlertDialog(
            onDismissRequest = { vm.confirmPendingAction(false) },
            title = { Text("Confirm Action") },
            text = { Text(action.description) },
            confirmButton = {
                Button(onClick = { vm.confirmPendingAction(true) }) {
                    Text("Allow")
                }
            },
            dismissButton = {
                TextButton(onClick = { vm.confirmPendingAction(false) }) {
                    Text("Deny")
                }
            }
        )
    }

    Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
        val header = buildString {
            append("$status • Memories: $memoryCount • ${if (fastAuto) "Fast" else "LLM"}")
            if (isListening) append(" • Listening...")
            voiceStatus?.let { append(" • $it") }
        }
        Text(text = header, style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(8.dp))

        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(onClick = { picker.launch(arrayOf("*/*")) }) {
                Text("Load .gguf")
            }
            OutlinedButton(onClick = {
                context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }) {
                Text("Accessibility")
            }
            FilterChip(
                selected = fastAuto,
                onClick = { vm.toggleFastAuto() },
                label = { Text("Fast Auto") }
            )
            FilterChip(
                selected = voiceEnabled,
                onClick = {
                    voiceEnabled = !voiceEnabled
                    if (!voiceEnabled) voice.stopSpeaking()
                },
                label = { Text(if (voiceEnabled) "Voice On" else "Muted") }
            )
            OutlinedButton(onClick = { vm.clearChat() }) {
                Text("Clear")
            }
            if (isGenerating || isNavigating) {
                Button(
                    onClick = {
                        voice.stopSpeaking()
                        vm.stopNavigation()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Stop")
                }
            }
        }

        Spacer(Modifier.height(8.dp))

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
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Command Ultron or tap Mic...") }
            )
            OutlinedButton(
                enabled = !isGenerating && !isNavigating,
                onClick = {
                    if (isListening) {
                        voice.stopListening()
                    } else {
                        val hasMic = ContextCompat.checkSelfPermission(
                            context, Manifest.permission.RECORD_AUDIO
                        ) == PackageManager.PERMISSION_GRANTED
                        if (hasMic) voice.startListening() else micPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                }
            ) {
                Text(if (isListening) "..." else "Mic")
            }
            Button(
                enabled = !isGenerating && !isNavigating,
                onClick = {
                    vm.sendMessage(input)
                    input = ""
                }
            ) {
                Text("Send")
            }
        }
    }
}
