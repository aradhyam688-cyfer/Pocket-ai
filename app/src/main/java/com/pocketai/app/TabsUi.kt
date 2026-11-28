package com.pocketai.app

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

@Composable
fun DashboardTab(vm: MainViewModel, accentColor: Color, dynBorder: Color) {
    val events by vm.usageEvents.collectAsState()
    val ramTemp by vm.ramTemp.collectAsState()
    val total = events.size.coerceAtLeast(1)
    val localN = events.count { it.source == "Local" }
    val fastN = events.count { it.source == "Fast" }
    val geminiN = events.count { it.source == "Gemini" }
    val gptN = events.count { it.source == "ChatGPT" }
    val claudeN = events.count { it.source == "Claude" }
    val cloudN = geminiN + gptN + claudeN
    val avgMs = events.filter { it.durationMs > 0 }.map { it.durationMs }.average().let { if (it.isNaN()) 0L else it.toLong() }

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Analytics Dashboard", style = MaterialTheme.typography.titleLarge, color = accentColor)
        Surface(color = Color(0x1AFFFFFF), shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, dynBorder), modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Local vs Cloud Usage (${events.size} runs)", style = MaterialTheme.typography.titleMedium, color = accentColor)
                UsageBarRow("Local GGUF", localN, total, Color(0xFF00E5FF))
                UsageBarRow("Fast Automation", fastN, total, accentColor)
                UsageBarRow("Cloud AI (All)", cloudN, total, Color(0xFFA855F7))
                Text("Avg Latency: ${avgMs}ms • $ramTemp", style = MaterialTheme.typography.bodySmall, color = Color(0xFFB5B5C2))
            }
        }
        Surface(color = Color(0x1AFFFFFF), shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, dynBorder), modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Cloud Provider Breakdown", style = MaterialTheme.typography.titleMedium, color = accentColor)
                val cTot = cloudN.coerceAtLeast(1)
                UsageBarRow("Gemini", geminiN, cTot, Color(0xFF4285F4))
                UsageBarRow("ChatGPT", gptN, cTot, Color(0xFF10B981))
                UsageBarRow("Claude", claudeN, cTot, Color(0xFFFFB300))
                val topCloud = listOf("Gemini" to geminiN, "ChatGPT" to gptN, "Claude" to claudeN).maxByOrNull { it.second }
                if (cloudN > 0 && topCloud != null) Text("Most used Cloud: ${topCloud.first} (${topCloud.second})", style = MaterialTheme.typography.bodySmall, color = Color.White)
            }
        }
                OutlinedButton(onClick = { vm.clearUsageStats() }, modifier = Modifier.fillMaxWidth()) { Text("Reset Analytics") }
        val ctx = LocalContext.current
        val crashPrefs = remember { ctx.getSharedPreferences("pocketai_crash", android.content.Context.MODE_PRIVATE) }
        var crashText by remember { mutableStateOf(crashPrefs.getString("last_crash", "") ?: "") }
        Surface(color = Color(0x1AFFFFFF), shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, dynBorder), modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Crash Log", style = MaterialTheme.typography.titleMedium, color = accentColor)
                    if (crashText.isNotBlank()) TextButton(onClick = { crashPrefs.edit().remove("last_crash").apply(); crashText = "" }) { Text("Clear") }
                }
                Text(if (crashText.isBlank()) "No crashes recorded." else crashText.take(700), style = MaterialTheme.typography.bodySmall, color = Color(0xFFB5B5C2))
            }
        }
    }
}

@Composable
private fun UsageBarRow(label: String, count: Int, total: Int, barColor: Color) {
    val frac = (count.toFloat() / total.coerceAtLeast(1)).coerceIn(0f, 1f)
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = Color(0xFFF0F0F5), style = MaterialTheme.typography.bodySmall)
            Text("$count (${(frac * 100).toInt()}%)", color = Color(0xFFB5B5C2), style = MaterialTheme.typography.bodySmall)
        }
        Canvas(modifier = Modifier.fillMaxWidth().height(10.dp)) {
            drawRoundRect(Color(0x22FFFFFF), size = size, cornerRadius = CornerRadius(8f, 8f))
            if (frac > 0f) drawRoundRect(barColor, size = Size(size.width * frac, size.height), cornerRadius = CornerRadius(8f, 8f))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ToolsManagerTab(
    vm: MainViewModel,
    accentColor: Color,
    dynBorder: Color,
    onPickKbDoc: () -> Unit,
    onOpenVision: () -> Unit,
    onSwitchToChat: () -> Unit
) {
    val context = LocalContext.current
    val offlineOnly by vm.offlineOnly.collectAsState()
    val devMode by vm.devMode.collectAsState()
    val stepDbg by vm.stepDebugger.collectAsState()
    val activeProf by vm.activeProfile.collectAsState()
    val toggles by vm.toolToggles.collectAsState()
    val plugins by vm.plugins.collectAsState()
    val kbDocs by vm.knowledgeDocs.collectAsState()
    val bench by vm.benchmarkResult.collectAsState()
    val devLogs by vm.devLogs.collectAsState()
    var showAddPlugin by remember { mutableStateOf(false) }

    if (showAddPlugin) {
        var pName by remember { mutableStateOf("") }; var pTrig by remember { mutableStateOf("") }
        var pType by remember { mutableStateOf("OPEN_URL") }; var pPay by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAddPlugin = false },
            title = { Text("Add Custom Plugin") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(value = pName, onValueChange = { pName = it }, label = { Text("Plugin Name") }, singleLine = true)
                    OutlinedTextField(value = pTrig, onValueChange = { pTrig = it }, label = { Text("Trigger prefix (e.g. wiki )") }, singleLine = true)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("OPEN_URL", "PROMPT").forEach { t -> FilterChip(selected = pType == t, onClick = { pType = t }, label = { Text(t) }) }
                    }
                    OutlinedTextField(value = pPay, onValueChange = { pPay = it }, label = { Text("URL or Prompt ({QUERY})") })
                }
            },
            confirmButton = {
                Button(onClick = { if (pName.isNotBlank() && pTrig.isNotBlank()) vm.addPlugin(pName, pTrig, pType, pPay); showAddPlugin = false }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { showAddPlugin = false }) { Text("Cancel") } }
        )
    }

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Tools, Plugins & Dev", style = MaterialTheme.typography.titleLarge, color = accentColor)

        Surface(color = Color(0x1AFFFFFF), shape = RoundedCornerShape(14.dp), border = BorderStroke(1.dp, dynBorder), modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Agent Profiles", style = MaterialTheme.typography.titleSmall, color = accentColor)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    vm.builtInProfiles.forEach { prof -> FilterChip(selected = activeProf == prof.name, onClick = { vm.selectProfile(prof) }, label = { Text(prof.name) }) }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = offlineOnly, onClick = { vm.toggleOfflineOnly() }, label = { Text("Strict Offline") })
                    FilterChip(selected = devMode, onClick = { vm.toggleDevMode() }, label = { Text("Dev Mode") })
                    FilterChip(selected = stepDbg, onClick = { vm.toggleStepDebugger() }, label = { Text("Step Debugger") })
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onOpenVision, modifier = Modifier.weight(1f)) { Text("Vision Model") }
                    OutlinedButton(onClick = { vm.runBenchmark(); onSwitchToChat() }, modifier = Modifier.weight(1f)) { Text("Benchmark") }
                }
                bench?.let { b -> Text("Last Benchmark (${b.modelName}): ${"%.1f".format(b.tokensPerSec)} tok/s • ${b.durationMs}ms • ${b.ramInfo}", style = MaterialTheme.typography.bodySmall, color = accentColor) }
            }
        }

        Surface(color = Color(0x1AFFFFFF), shape = RoundedCornerShape(14.dp), border = BorderStroke(1.dp, dynBorder), modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Built-in Tools & Notifications", style = MaterialTheme.typography.titleSmall, color = accentColor)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    toggles.forEach { (k, on) -> FilterChip(selected = on, onClick = { vm.toggleToolEnabled(k) }, label = { Text(k) }) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { vm.summarizeNotifications(); onSwitchToChat() }, modifier = Modifier.weight(1f)) { Text("Summarize Notifs") }
                    OutlinedButton(onClick = { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }, modifier = Modifier.weight(1f)) { Text("Notif Access") }
                }
            }
        }

        Surface(color = Color(0x1AFFFFFF), shape = RoundedCornerShape(14.dp), border = BorderStroke(1.dp, dynBorder), modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Local Knowledge Base (${kbDocs.size})", style = MaterialTheme.typography.titleSmall, color = accentColor)
                    TextButton(onClick = onPickKbDoc) { Text("+ Add PDF/TXT") }
                }
                kbDocs.forEach { d ->
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(d, color = Color.White, style = MaterialTheme.typography.bodySmall)
                        IconButton(onClick = { vm.deleteKnowledgeDoc(d) }) { Icon(Icons.Rounded.DeleteOutline, null) }
                    }
                }
            }
        }

        Surface(color = Color(0x1AFFFFFF), shape = RoundedCornerShape(14.dp), border = BorderStroke(1.dp, dynBorder), modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Custom Plugins (${plugins.size})", style = MaterialTheme.typography.titleSmall, color = accentColor)
                    TextButton(onClick = { showAddPlugin = true }) { Text("+ Add Plugin") }
                }
                plugins.forEach { pl ->
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("${pl.name} (${pl.trigger})", color = Color.White, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        Checkbox(checked = pl.enabled, onCheckedChange = { vm.togglePlugin(pl) })
                        IconButton(onClick = { vm.deletePlugin(pl) }) { Icon(Icons.Rounded.DeleteOutline, null) }
                    }
                }
            }
        }

        if (devMode && devLogs.isNotEmpty()) {
            Surface(color = Color(0x1AFFFFFF), shape = RoundedCornerShape(14.dp), border = BorderStroke(1.dp, dynBorder), modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Dev Inspector (Latest)", style = MaterialTheme.typography.titleSmall, color = accentColor)
                    val d = devLogs.first()
                    Text("Duration: ${d.durationMs}ms\nPrompt: ${d.prompt}\nOutput: ${d.output}", style = MaterialTheme.typography.bodySmall, color = Color(0xFFB5B5C2))
                }
            }
        }
    }
}

@Composable
fun CommandPaletteDialog(vm: MainViewModel, onDismiss: () -> Unit, onRun: (String) -> Unit) {
    var q by remember { mutableStateOf("") }
    val suggestions by vm.autoSuggestions.collectAsState()
    val builtIns = listOf("/help", "call mamma", "summarise notifications", "summarise this pdf", "open yt and doomscroll", "turn on flashlight") + suggestions
    val filtered = builtIns.distinct().filter { q.isBlank() || it.contains(q, true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("AI Command Palette") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(value = q, onValueChange = { q = it }, label = { Text("Search commands or type...") }, singleLine = true)
                filtered.take(6).forEach { cmd -> OutlinedButton(onClick = { onRun(cmd); onDismiss() }, modifier = Modifier.fillMaxWidth()) { Text(cmd) } }
            }
        },
        confirmButton = { if (q.isNotBlank()) Button(onClick = { onRun(q); onDismiss() }) { Text("Run") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

@Composable
fun VisionDialog(vm: MainViewModel, onPickVisionModel: () -> Unit, onPickMmproj: () -> Unit, onDismiss: () -> Unit) {
    val vc by vm.visionConfig.collectAsState()
    var ctx by remember { mutableStateOf(vc.contextLength.toString()) }
    var imgSz by remember { mutableStateOf(vc.maxImageSize.toString()) }
    var temp by remember { mutableStateOf(vc.temperature.toString()) }
    var threads by remember { mutableStateOf(vc.threads.toString()) }
    var swap by remember { mutableStateOf(vc.autoSwapRam) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Vision Model & Params") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Model: ${vc.modelName.ifBlank { "None (uses Offline OCR fallback)" }}", style = MaterialTheme.typography.bodySmall)
                Text("mmproj: ${if (vc.mmprojUri.isNotBlank()) "Loaded" else "Not set"}", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(onClick = onPickVisionModel, modifier = Modifier.weight(1f)) { Text("Pick VLM") }
                    OutlinedButton(onClick = onPickMmproj, modifier = Modifier.weight(1f)) { Text("Pick mmproj") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(value = ctx, onValueChange = { ctx = it }, label = { Text("n_ctx") }, modifier = Modifier.weight(1f), singleLine = true)
                    OutlinedTextField(value = imgSz, onValueChange = { imgSz = it }, label = { Text("Max px") }, modifier = Modifier.weight(1f), singleLine = true)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(value = temp, onValueChange = { temp = it }, label = { Text("Temp") }, modifier = Modifier.weight(1f), singleLine = true)
                    OutlinedTextField(value = threads, onValueChange = { threads = it }, label = { Text("Threads") }, modifier = Modifier.weight(1f), singleLine = true)
                }
                Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(checked = swap, onCheckedChange = { swap = it }); Text("Auto-swap Text RAM before Vision") }
            }
        },
        confirmButton = {
            Button(onClick = {
                vm.saveVisionConfig(vc.copy(contextLength = ctx.toIntOrNull() ?: 2048, maxImageSize = (imgSz.toIntOrNull() ?: 512).coerceIn(256, 1024), temperature = temp.toFloatOrNull() ?: 0.5f, threads = (threads.toIntOrNull() ?: 4).coerceIn(1, 8), autoSwapRam = swap))
                onDismiss()
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}
