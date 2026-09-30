package net.boswell.phone.ui

import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.FilterChip
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import net.boswell.phone.sync.Mode
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import net.boswell.phone.capture.CaptureState
import net.boswell.phone.capture.Link
import net.boswell.phone.capture.Reading
import net.boswell.phone.models.ModelProgressRepository
import net.boswell.phone.process.CleanupWorker

/** "12 s ago", ticking. Every device reading on screen says how old it is. */
@Composable
fun ago(atMillis: Long?): String {
    val now = remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(1_000); now.longValue = System.currentTimeMillis() } }
    if (atMillis == null) return "never"
    val s = ((now.longValue - atMillis) / 1000).coerceAtLeast(0)
    return when {
        s < 60 -> "${s}s ago"
        s < 3600 -> "${s / 60}m ago"
        else -> "${s / 3600}h ${(s % 3600) / 60}m ago"
    }
}

@Composable
private fun Row2(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun <T> ReadingRow(label: String, r: Reading<T>?, show: (T) -> String) =
    Row2(label, if (r == null) "—" else "${show(r.value)} · ${ago(r.atMillis)}")

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun DeviceScreen(ui: UiState, cap: CaptureState, vm: MainViewModel, pad: PaddingValues, onTriggers: () -> Unit, onUsage: () -> Unit, onPair: () -> Unit, onSetup: () -> Unit = {}) {
    val ctx = LocalContext.current
    val progress by ModelProgressRepository.state.collectAsStateWithLifecycle()
    var confirmClean by remember { mutableStateOf(false) }
    var showLog by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { vm.refreshStorage(); vm.refreshModels(); vm.refreshSync(ctx as? android.app.Activity) }

    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = pad.calculateTopPadding() + 8.dp, bottom = pad.calculateBottomPadding() + 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Text("Device", style = MaterialTheme.typography.headlineLarge) }

        item {
            Section("Omi") {
                val last = cap.lastAudioMillis
                val quiet = last == null || System.currentTimeMillis() - last > 4_000
                Text(
                    when (cap.link) {
                        Link.IDLE -> when (ui.mode) {
                            Mode.SYNC -> if (ui.lastSync > 0) "Synced ${Fmt.ago(ui.lastSync / 1000.0)} · ${ui.lastSyncResult ?: ""}" else "Waiting for the first sync"
                            else -> "Not connected"
                        }
                        Link.CONNECTING -> "Connecting…"
                        Link.STREAMING -> if (quiet) "Live · listening (the mic sleeps in silence)" else "Live · recording"
                        Link.AWAY -> "Out of range or off · will keep trying"
                        Link.SYNCING -> cap.sync?.let { s ->
                            if (s.target > 0) "Syncing · ${s.took * 100 / s.target}% · " + "%.0f kB/s".format(s.bytesPerSecond / 1000) else "Syncing · ${s.phase}"
                        } ?: "Syncing…"
                    },
                    style = MaterialTheme.typography.bodyLarge,
                )
                cap.sync?.takeIf { it.target > 0 }?.let { s -> LinearProgressIndicator(progress = { s.took.toFloat() / s.target }, modifier = Modifier.fillMaxWidth()) }

                if (ui.savedAddress == null) {
                    Text("Find your Omi to get started.", style = MaterialTheme.typography.bodyMedium)
                } else {
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        Mode.entries.forEachIndexed { i, m ->
                            SegmentedButton(selected = ui.mode == m, onClick = { vm.setMode(m) }, shape = SegmentedButtonDefaults.itemShape(i, Mode.entries.size)) {
                                Text(when (m) { Mode.OFF -> "Off"; Mode.SYNC -> "Sync"; Mode.LIVE -> "Live" })
                            }
                        }
                    }
                    Text(
                        when (ui.mode) {
                            Mode.OFF -> "The phone leaves the Omi alone. It keeps recording to its own memory."
                            Mode.SYNC -> "The Omi records on its own. Every so often, and whenever it comes into range, the phone downloads what it stored and lets go. Easiest on both batteries; audio downloaded here is removed from the Omi."
                            Mode.LIVE -> "The phone stays connected and conversations appear within seconds. Needed for the assistant to listen."
                        },
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (ui.mode == Mode.SYNC) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Visit every", Modifier.padding(end = 8.dp))
                            for (m in listOf(15, 30, 60, 120, 240)) {
                                FilterChip(selected = ui.syncMinutes == m, onClick = { vm.setSyncMinutes(m) },
                                    label = { Text(if (m < 60) "${m}m" else "${m / 60}h") }, modifier = Modifier.padding(end = 4.dp))
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = vm::syncNow, enabled = cap.link == Link.IDLE) { Text("Sync now") }
                            if (!ui.companionPaired) OutlinedButton(onClick = onPair) { Text("Sync when in range") }
                        }
                        if (ui.companionPaired) Text("Paired for background sync: the phone visits when the Omi comes into range.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    }
                }
                cap.device?.let { d -> Row2("Device", "${d.model ?: "Omi"} · firmware ${d.firmware ?: "?"}") }
                    ?: ui.savedAddress?.let { Row2("Paired", it) }
                ReadingRow("Battery", cap.battery) { "$it%" }
                ReadingRow("Charging", cap.charging) { if (it) "yes" else "no" }
                ReadingRow("Signal", cap.rssi) { "$it dBm" }
                ReadingRow("Clock", cap.deviceClockSkewSeconds) { if (kotlin.math.abs(it) < 3) "in sync" else "off by ${it}s" }
                ReadingRow("Waiting on the Omi", cap.ring) { r -> "≈ %.0f min".format(r.pending * (27 * 3600.0 / 1_115_064) / 60) }
                if (ui.savedAddress == null || ui.mode == Mode.OFF) {
                    OutlinedButton(onClick = vm::scan, enabled = !ui.scanning) { Text(if (ui.scanning) "Looking…" else "Find Omi") }
                }
                for (d in ui.found) {
                    Card(Modifier.fillMaxWidth().clickable { vm.choose(d.address) }) {
                        Row(Modifier.padding(12.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("${d.name ?: "Omi"} · ${d.address}")
                            Text("${d.advertisedRssi} dBm")
                        }
                    }
                }
            }
        }

        if (!ui.batteryExempt) item {
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Keep recording in the background", style = MaterialTheme.typography.titleMedium)
                    Text("Android may stop recording to save battery. Allow Boswell to run unrestricted so it keeps listening all day.",
                        style = MaterialTheme.typography.bodyMedium)
                    Button(onClick = { ctx.startActivity(vm.batteryExemptionIntent()); vm.refreshStorage() }) { Text("Allow") }
                }
            }
        }

        item {
            Section("Assistant") {
                val ctx2 = LocalContext.current
                var keyText by remember { mutableStateOf("") }
                var hasKey by remember { mutableStateOf(net.boswell.phone.assistant.Secrets.has(ctx2, net.boswell.phone.assistant.Secrets.OPENROUTER)) }
                var model by remember { mutableStateOf(net.boswell.phone.assistant.AssistantPrefs.model(ctx2)) }
                var voice by remember { mutableStateOf(net.boswell.phone.assistant.AssistantPrefs.voice(ctx2)) }
                var watcher by remember { mutableStateOf(net.boswell.phone.assistant.AssistantPrefs.watcher(ctx2)) }
                var budget by remember { mutableStateOf(net.boswell.phone.assistant.AssistantPrefs.budget(ctx2).toFloat()) }
                var dbl by remember { mutableStateOf(net.boswell.phone.assistant.AssistantPrefs.doubleTap(ctx2)) }
                val owner = net.boswell.phone.assistant.AssistantPrefs.owner(ctx2)
                val ownerName = ui.people.firstOrNull { it.id == owner }?.name
                Text("Questions and hints go to a model through OpenRouter as text; audio never leaves the phone.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (hasKey) Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("OpenRouter key saved", Modifier.weight(1f), color = MaterialTheme.colorScheme.primary)
                    TextButton(onClick = { net.boswell.phone.assistant.Secrets.put(ctx2, net.boswell.phone.assistant.Secrets.OPENROUTER, null); hasKey = false }) { Text("Remove") }
                } else Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.OutlinedTextField(value = keyText, onValueChange = { keyText = it }, singleLine = true,
                        label = { Text("OpenRouter API key") }, modifier = Modifier.weight(1f),
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation())
                    TextButton(enabled = keyText.isNotBlank(), onClick = {
                        net.boswell.phone.assistant.Secrets.put(ctx2, net.boswell.phone.assistant.Secrets.OPENROUTER, keyText); keyText = ""; hasKey = true
                    }) { Text("Save") }
                }
                androidx.compose.material3.OutlinedTextField(value = model, onValueChange = { model = it; net.boswell.phone.assistant.AssistantPrefs.setModel(ctx2, it) },
                    singleLine = true, label = { Text("Model") }, modifier = Modifier.fillMaxWidth())
                Row2("Me", ownerName ?: "not set · open yourself in People and tap \"This is me\"")
                Row2("Omi button", when (cap.buttonReady) { true -> "ready · tap to ask"; false -> "not available on this connection"; null -> "connect in Live mode" })
                Text("Double tap", style = MaterialTheme.typography.bodyMedium)
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (d in net.boswell.phone.assistant.AssistantPrefs.DoubleTap.entries) FilterChip(selected = dbl == d,
                        onClick = { dbl = d; net.boswell.phone.assistant.AssistantPrefs.setDoubleTap(ctx2, d) },
                        label = { Text(when (d) {
                            net.boswell.phone.assistant.AssistantPrefs.DoubleTap.TODO -> "Add a to-do"
                            net.boswell.phone.assistant.AssistantPrefs.DoubleTap.BOOKMARK -> "Bookmark the moment"
                            net.boswell.phone.assistant.AssistantPrefs.DoubleTap.SUMMARIZE -> "Summarize last 10 min"
                        }) })
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable(onClick = onUsage)) {
                    Column(Modifier.weight(1f)) {
                        Text("AI usage", style = MaterialTheme.typography.bodyLarge)
                        Text("What the assistant has cost, by day and by use", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text("View", color = MaterialTheme.colorScheme.primary)
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable(onClick = onTriggers)) {
                    Column(Modifier.weight(1f)) {
                        Text("Voice triggers", style = MaterialTheme.typography.bodyLarge)
                        Text(if (net.boswell.phone.assistant.Triggers.enabled(ctx2)) "On · say \"remind me…\", \"hey Boswell…\" and more"
                            else "Off · act on phrases like \"remind me\" when you say them",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text("Edit", color = MaterialTheme.colorScheme.primary)
                }
                var calendarOk by remember { mutableStateOf(net.boswell.phone.todo.Calendar.allowed(ctx2)) }
                // Granting access leads straight to choosing where events go, so nobody
                // ends up with the assistant adding events to a calendar they never picked.
                var openPickerNext by remember { mutableStateOf(false) }
                val calLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
                    androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()) {
                    calendarOk = net.boswell.phone.todo.Calendar.allowed(ctx2)
                    openPickerNext = calendarOk && net.boswell.phone.todo.Calendar.chosen(ctx2) == null
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Calendar", style = MaterialTheme.typography.bodyLarge)
                        Text(if (calendarOk) "The assistant can add events to your calendar." else "Let the assistant add appointments to your calendar.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (!calendarOk) TextButton(onClick = { calLauncher.launch(arrayOf(android.Manifest.permission.READ_CALENDAR, android.Manifest.permission.WRITE_CALENDAR)) }) { Text("Allow") }
                }
                if (calendarOk) {
                    var chosen by remember { mutableStateOf(net.boswell.phone.todo.Calendar.chosen(ctx2)) }
                    var picking by remember { mutableStateOf(false) }
                    LaunchedEffect(openPickerNext) { if (openPickerNext) { picking = true; openPickerNext = false } }
                    var showEv by remember { mutableStateOf(net.boswell.phone.todo.Calendar.showEvents(ctx2)) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(if (chosen == null) "No calendar chosen: the assistant won't add events yet" else "New events go to ${chosen!!.name}",
                                style = MaterialTheme.typography.bodyMedium, color = if (chosen == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                            chosen?.let { Text(it.account, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                        TextButton(onClick = { picking = true }) { Text(if (chosen == null) "Choose" else "Change") }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Show calendar events in To-do", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        Switch(checked = showEv, onCheckedChange = { showEv = it; net.boswell.phone.todo.Calendar.setShowEvents(ctx2, it) })
                    }
                    if (picking) CalendarPicker(onDismiss = { picking = false }) { c -> chosen = c; picking = false }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Speak answers", style = MaterialTheme.typography.bodyLarge)
                        Text("Read answers aloud as well as showing them.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = voice, onCheckedChange = { voice = it; net.boswell.phone.assistant.AssistantPrefs.setVoice(ctx2, it) })
                }
                if (voice) {
                    var voices by remember { mutableStateOf<List<net.boswell.phone.assistant.AssistantNotify.VoiceOption>>(emptyList()) }
                    var chosenVoice by remember { mutableStateOf(net.boswell.phone.assistant.AssistantPrefs.ttsVoice(ctx2)) }
                    var pickingVoice by remember { mutableStateOf(false) }
                    LaunchedEffect(Unit) { net.boswell.phone.assistant.AssistantNotify.voices(ctx2) { voices = it } }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Voice: " + (voices.firstOrNull { it.name == chosenVoice }?.label ?: "system default"), Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = { pickingVoice = true }, enabled = voices.isNotEmpty()) { Text("Choose") }
                    }
                    if (pickingVoice) androidx.compose.material3.AlertDialog(
                        onDismissRequest = { pickingVoice = false },
                        title = { Text("Answer voice") },
                        text = {
                            androidx.compose.foundation.lazy.LazyColumn {
                                items(voices.size) { i ->
                                    val v = voices[i]
                                    Row(Modifier.fillMaxWidth().clickable {
                                        chosenVoice = v.name; net.boswell.phone.assistant.AssistantPrefs.setTtsVoice(ctx2, v.name)
                                    }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                        androidx.compose.material3.RadioButton(selected = chosenVoice == v.name, onClick = {
                                            chosenVoice = v.name; net.boswell.phone.assistant.AssistantPrefs.setTtsVoice(ctx2, v.name) })
                                        Column(Modifier.weight(1f)) {
                                            Text(v.label)
                                            Text(if (v.offline) "on this phone" else "needs internet · slower to start", style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                        TextButton(onClick = { net.boswell.phone.assistant.AssistantNotify.speak(ctx2,
                                            "Hi, I'm Boswell. This is how your answers will sound.", v.name) }) { Text("Preview") }
                                    }
                                }
                            }
                        },
                        confirmButton = { TextButton(onClick = { pickingVoice = false }) { Text("Done") } },
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Listen along (live mode)", style = MaterialTheme.typography.bodyLarge)
                        Text("Every couple of minutes, if you've said something, it may send one short hint. Needs \"Me\" set.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = watcher, onCheckedChange = { watcher = it; net.boswell.phone.assistant.AssistantPrefs.setWatcher(ctx2, it) })
                }
                if (watcher) {
                    Text("Daily budget for hints: $%.2f".format(budget), style = MaterialTheme.typography.bodyMedium)
                    androidx.compose.material3.Slider(value = budget, onValueChange = { budget = (it * 20).toInt() / 20f },
                        onValueChangeFinished = { net.boswell.phone.assistant.AssistantPrefs.setBudget(ctx2, budget.toDouble()) },
                        valueRange = 0.05f..3f)
                }
            }
        }

        item {
            Section("On-device models") {
                Text("Everything runs on this phone. Models download from the boswell-phone GitHub release and are checked before use.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                for (m in ui.models) {
                    val p = progress[m.spec.id]
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(m.spec.name, style = MaterialTheme.typography.bodyLarge)
                                Text("${m.spec.purpose} · ${Fmt.bytes(m.spec.totalBytes)}", style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            when {
                                m.installed -> TextButton(onClick = { vm.deleteModel(m.spec.id) }) { Text("Remove") }
                                p?.running == true -> TextButton(onClick = { vm.cancelDownload(m.spec.id) }) { Text("Cancel") }
                                else -> TextButton(onClick = { vm.download(m.spec.id) }) { Text("Get") }
                            }
                        }
                        if (p?.running == true) LinearProgressIndicator(progress = { p.bytes.toFloat() / p.total }, modifier = Modifier.fillMaxWidth())
                        p?.error?.takeIf { !m.installed && p.running.not() }?.let { Text("Last try: $it", style = MaterialTheme.typography.bodySmall) }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = ui.wifiOnly, onCheckedChange = vm::setWifiOnly)
                    Spacer(Modifier.width(8.dp))
                    Text("Download on Wi-Fi only", Modifier.weight(1f))
                    if (ui.models.any { !it.installed }) OutlinedButton(onClick = vm::downloadAll) { Text("Get all") }
                }
            }
        }

        item {
            Section("Storage") {
                val u = ui.usage
                Row2("Recordings", if (u == null) "…" else "${u.clips} · ${Fmt.bytes(u.audioBytes)}")
                if (u != null && u.quietClips > 0) {
                    Text("${u.quietClips} clips older than ${CleanupWorker.DAYS} days held no speech and only background sound (${Fmt.bytes(u.quietBytes)}).",
                        style = MaterialTheme.typography.bodyMedium)
                    OutlinedButton(onClick = { confirmClean = true }) { Text("Free up ${Fmt.bytes(u.quietBytes)}") }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Clean up automatically", style = MaterialTheme.typography.bodyLarge)
                        Text("Daily, delete the audio of week-old clips with no speech and only background. The day's timeline keeps them.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = ui.autoClean, onCheckedChange = vm::setAutoClean)
                }
            }
        }

        item {
            Section("Diagnostics") {
                Row2("Last audio", ago(cap.lastAudioMillis))
                Row2("Frames · unusable", "${cap.frames} · ${cap.dropped}")
                Row2("Clips this session", "${cap.clipsWritten}")
                Row2("Device restarts seen", "${cap.reboots}")
                Row2("Last button tap", cap.lastButton?.let { (code, at) -> (if (code == 2) "double · " else "") + ago(at) }
                    ?: if (cap.buttonReady == true) "none yet (tap quickly)" else "not available")
                Row {
                    TextButton(onClick = { showLog = !showLog }) { Text(if (showLog) "Hide log" else "Show log") }
                    TextButton(onClick = onSetup) { Text("Run setup again") }
                }
            }
        }
        if (showLog) items(cap.log.asReversed().take(80)) { line ->
            Text(line, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
        }
    }

    if (confirmClean) {
        val u = ui.usage
        AlertDialog(
            onDismissRequest = { confirmClean = false },
            title = { Text("Delete background-only audio?") },
            text = { Text("${u?.quietClips ?: 0} clips' audio will be deleted permanently. Their place on the timeline and their sound tags stay.") },
            confirmButton = { TextButton(onClick = { vm.cleanNow(); confirmClean = false }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmClean = false }) { Text("Keep") } },
        )
    }
}
