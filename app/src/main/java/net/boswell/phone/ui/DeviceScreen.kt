package net.boswell.phone.ui

import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
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
fun DeviceScreen(ui: UiState, cap: CaptureState, vm: MainViewModel, pad: PaddingValues, onTriggers: () -> Unit, onUsage: () -> Unit, onPair: () -> Unit, onSetup: () -> Unit = {},
                 onCompare: () -> Unit = {}, onTexting: () -> Unit = {}) {
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
                    if (ui.mode == Mode.LIVE) {
                        var onCharger by remember { mutableStateOf(net.boswell.phone.sync.Modes.syncOnCharger(ctx)) }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Sync on the charger", style = MaterialTheme.typography.bodyLarge)
                                Text("While live, the Omi keeps what it hears out of range. When it goes on its charger, pause and download that, then go back to live.",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(checked = onCharger, onCheckedChange = { onCharger = it; net.boswell.phone.sync.Modes.setSyncOnCharger(ctx, it) })
                        }
                    }
                }
                cap.device?.let { d -> Row2("Device", "${d.model ?: "Omi"} · firmware ${d.firmware ?: "?"}") }
                    ?: ui.savedAddress?.let { Row2("Paired", it) }
                ReadingRow("Battery", cap.battery) { "$it%" }
                ReadingRow("Charging", cap.charging) { if (it) "yes" else "no" }
                if (ui.savedAddress != null) {
                    // The Omi's LED: 0 is off. Sent now when connected, otherwise at the next connection.
                    val lctx = LocalContext.current
                    val lprefs = remember { lctx.getSharedPreferences("boswell", android.content.Context.MODE_PRIVATE) }
                    var led by remember { mutableStateOf(lprefs.getInt("led_brightness", -1).takeIf { it >= 0 } ?: cap.ledBrightness ?: 50) }
                    LaunchedEffect(cap.ledBrightness) {
                        if (lprefs.getInt("led_brightness", -1) < 0) cap.ledBrightness?.let { led = it }
                    }
                    // Stops: off, 1% (barely visible), then every 10%.
                    val stops = listOf(0, 1) + (10..100 step 10)
                    val at = stops.indices.minBy { kotlin.math.abs(stops[it] - led) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Light", Modifier.padding(end = 12.dp))
                        androidx.compose.material3.Slider(value = at.toFloat(), onValueChange = { led = stops[Math.round(it)] },
                            valueRange = 0f..(stops.size - 1).toFloat(), steps = stops.size - 2,
                            onValueChangeFinished = {
                                lprefs.edit().putInt("led_brightness", led).apply()
                                net.boswell.phone.capture.CaptureService.applyLedNow(lctx)
                            }, modifier = Modifier.weight(1f))
                        Text(if (led == 0) "Off" else "$led%", Modifier.padding(start = 12.dp).width(44.dp), style = MaterialTheme.typography.labelLarge)
                    }
                    val connected = cap.link == net.boswell.phone.capture.Link.STREAMING || cap.link == net.boswell.phone.capture.Link.SYNCING
                    if (!connected) Text("Sent to the Omi the next time it connects.", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
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
                Text("Questions and hints go to a model as text (through OpenRouter, or on your home server); audio never leaves the phone.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                AiWhereSetting()
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
                var cloudQ by remember { mutableStateOf(net.boswell.phone.assistant.AssistantPrefs.cloudQuestions(ctx2)) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Understand questions in the cloud", style = MaterialTheme.typography.bodyLarge)
                        Text("Button questions are transcribed by Parakeet, which hears short questions more accurately than the phone. " +
                            "Their audio goes to OpenRouter (the text already does). If it can't be reached, the phone transcribes instead.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = cloudQ, onCheckedChange = { cloudQ = it; net.boswell.phone.assistant.AssistantPrefs.setCloudQuestions(ctx2, it) })
                }
                AssistantRoutines()
                EmailSettings()
                Text("Texting, contacts and what's remembered about people are in People.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
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
            Section("Home server") { HomeServerSection() }
        }

        item {
            Section("Transcription") {
                val tctx = LocalContext.current
                val T = net.boswell.phone.asr.Transcription
                var mode by remember { mutableStateOf(T.mode(tctx)) }
                val cloudOn = mode != net.boswell.phone.asr.Transcription.Mode.PHONE
                var cap by remember { mutableStateOf(T.dailyCap(tctx)) }
                var spent by remember { mutableStateOf(0.0) }
                val hasKey = remember { net.boswell.phone.assistant.Secrets.has(tctx, net.boswell.phone.assistant.Secrets.OPENROUTER) }
                LaunchedEffect(mode) {
                    spent = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        val st = net.boswell.phone.assistant.AssistantStore(tctx); try { st.spentToday(T.PURPOSE) } finally { st.close() }
                    }
                }
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    for ((i, m) in listOf(net.boswell.phone.asr.Transcription.Mode.PHONE to "Phone", net.boswell.phone.asr.Transcription.Mode.OTHERS to "With others", net.boswell.phone.asr.Transcription.Mode.ALL to "Everything").withIndex()) {
                        SegmentedButton(selected = mode == m.first, enabled = m.first == net.boswell.phone.asr.Transcription.Mode.PHONE || hasKey,
                            onClick = { mode = m.first; T.setMode(tctx, m.first) },
                            shape = SegmentedButtonDefaults.itemShape(i, 3)) { Text(m.second) }
                    }
                }
                Text(if (cloudOn) (if (mode == net.boswell.phone.asr.Transcription.Mode.OTHERS) "Recordings where someone besides you is talking go to ${T.ENGINE.label} in the cloud, " +
                        "where the phone is weakest (people across the room, talking over each other). What you say on your own stays on the phone. "
                    else "Words come from ${T.ENGINE.label} in the cloud. ") + "More accurate in testing, about $0.09 per hour of speech. " +
                        "The audio of clips with speech goes to OpenRouter; who's speaking is still worked out on this phone. " +
                        "If the cloud can't be reached, or today's limit is reached, the phone transcribes instead."
                    else "Private: audio never leaves the phone. To fix a clip the phone got wrong, select it in Recordings and choose More → Redo in the cloud." +
                        if (!hasKey) " Cloud transcription needs an OpenRouter key (Assistant, above)." else "",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (hasKey) Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Daily limit", Modifier.padding(end = 8.dp))
                    for (v in listOf(0.25, 0.5, 1.0, 2.0)) {
                        FilterChip(selected = cap == v, onClick = { cap = v; T.setDailyCap(tctx, v) },
                            label = { Text("$" + if (v < 1) "%.2f".format(v) else "%.0f".format(v)) }, modifier = Modifier.padding(end = 4.dp))
                    }
                }
                if (hasKey) Text("Spent on cloud transcription today: $%.3f".format(spent), style = MaterialTheme.typography.bodySmall)
                var vocabOpen by remember { mutableStateOf(false) }
                var vocabCount by remember { mutableStateOf(net.boswell.phone.asr.Vocabulary.custom(tctx).size) }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { vocabOpen = true }) {
                    Column(Modifier.weight(1f)) {
                        Text("Words Boswell should know", style = MaterialTheme.typography.bodyLarge)
                        Text("Names and terms it should spell right: everyone in People, Omi and Boswell" +
                            if (vocabCount > 0) ", and $vocabCount of your own" else "",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text("Edit", color = MaterialTheme.colorScheme.primary)
                }
                if (vocabOpen) VocabularyDialog(onDismiss = { vocabOpen = false; vocabCount = net.boswell.phone.asr.Vocabulary.custom(tctx).size })
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable(onClick = onCompare)) {
                    Column(Modifier.weight(1f)) {
                        Text("Compare with the cloud", style = MaterialTheme.typography.bodyLarge)
                        Text("See how the phone's transcripts stack up against a cloud engine on your own clips",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text("Open", color = MaterialTheme.colorScheme.primary)
                }
            }

            Section("On-device models") {
                Text("Everything runs on this phone. Models download from the boswell-phone GitHub release and are checked before use.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val vctx = LocalContext.current
                var better by remember { mutableStateOf(net.boswell.phone.diarize.VoiceModels.wanted(vctx) == net.boswell.phone.diarize.VoiceModel.SPEAKER_ID) }
                val converting = net.boswell.phone.diarize.VoiceModels.wanted(vctx) != net.boswell.phone.diarize.VoiceModels.active(vctx)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Better voice recognition", style = MaterialTheme.typography.bodyLarge)
                        Text("A larger voice model (ReDimNet2): picked the right person for 92% of voices in testing, against 82%. " +
                            "It takes about twice the processing, and battery, per recording. Your voices are converted once, on the charger." +
                            if (converting) " Converting your voices: it finishes on the charger." else "",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = better, onCheckedChange = { better = it; vm.setBetterVoices(it) })
                }
                for (m in ui.models) {
                    val p = progress[m.spec.id]
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(m.spec.name, style = MaterialTheme.typography.bodyLarge)
                                Text("${m.spec.purpose} · ${Fmt.bytes(if (m.installed) m.spec.totalBytes else m.spec.downloadBytes)}${if (m.installed) "" else " download"}", style = MaterialTheme.typography.bodySmall,
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
                    Text("${u.quietClips} clips held no speech and only background sound (${Fmt.bytes(u.quietBytes)}).",
                        style = MaterialTheme.typography.bodyMedium)
                    OutlinedButton(onClick = { confirmClean = true }) { Text("Free up ${Fmt.bytes(u.quietBytes)}") }
                }
                var backlogCharging by remember { mutableStateOf(net.boswell.phone.sync.Modes.backlogOnCharger(ctx)) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Big downloads wait for the charger", style = MaterialTheme.typography.bodyLarge)
                        Text("More than half an hour of audio from the Omi's memory is transcribed while the phone charges, to spare its battery. What's heard live is always transcribed right away.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = backlogCharging, onCheckedChange = {
                        backlogCharging = it
                        net.boswell.phone.sync.Modes.setBacklogOnCharger(ctx, it)
                        net.boswell.phone.process.ProcessingWorker.enqueue(ctx)
                    })
                }
                BackupRow()
                Text("Delete the sound of quiet clips", style = MaterialTheme.typography.bodyLarge)
                Text("Clips with no speech where only background was heard. The day's timeline keeps them; only the sound goes. " +
                    "Clips with speech are kept compressed, about a tenth of their original size.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    for ((d, label) in listOf(0 to "Right away", 1 to "After a day", 7 to "After a week", -1 to "Never")) {
                        FilterChip(selected = ui.quietDays == d, onClick = { vm.setQuietDays(d) }, label = { Text(label) },
                            modifier = Modifier.padding(end = 4.dp))
                    }
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

/**
 * The words list: what's automatic (built in, People) shown for reference,
 * and the person's own words, added and removed here.
 */
@Composable
private fun VocabularyDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var mine by remember { mutableStateOf(net.boswell.phone.asr.Vocabulary.custom(ctx)) }
    var adding by remember { mutableStateOf("") }
    val auto = remember { net.boswell.phone.asr.Vocabulary.all(ctx).filter { a -> mine.none { it.equals(a, true) } } }
    fun save(list: List<String>) { mine = list; net.boswell.phone.asr.Vocabulary.setCustom(ctx, list) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        title = { Text("Words Boswell should know") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("When the transcript nearly gets one of these (\"omi\", \"bos well\"), it's fixed. Common words that only sound alike are never changed, and a corrected line can be restored.",
                    style = MaterialTheme.typography.bodySmall)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.OutlinedTextField(value = adding, onValueChange = { adding = it }, singleLine = true,
                        placeholder = { Text("A name or word") }, modifier = Modifier.weight(1f))
                    TextButton(enabled = adding.trim().length >= 3, onClick = { save(mine + adding.trim()); adding = "" }) { Text("Add") }
                }
                for (w in mine) Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(w, Modifier.weight(1f))
                    TextButton(onClick = { save(mine - w) }) { Text("Remove") }
                }
                if (auto.isNotEmpty()) {
                    Text("Always included", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
                    Text(auto.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text("Applies to new transcripts.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
    )
}

/**
 * Where the AI runs: OpenRouter, or the model on the paired home server (Ollama on
 * your computer: free, private, slower) -- with a fallback like recordings have.
 * Offered only while a home server is paired.
 */
@Composable
private fun AiWhereSetting() {
    val ctx = LocalContext.current
    if (!net.boswell.phone.home.HomeServer.paired(ctx)) return
    val scope = rememberCoroutineScope()
    val P = net.boswell.phone.assistant.AssistantPrefs
    var where by remember { mutableStateOf(P.where(ctx)) }
    var fallback by remember { mutableStateOf(P.homeFallback(ctx)) }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    Text("Where the AI runs", style = MaterialTheme.typography.bodyLarge)
    Row {
        for ((w, label) in listOf(net.boswell.phone.assistant.AssistantPrefs.Where.OPENROUTER to "OpenRouter", net.boswell.phone.assistant.AssistantPrefs.Where.HOME to "Home server")) {
            FilterChip(selected = where == w, onClick = { where = w; P.setWhere(ctx, w); status = null }, label = { Text(label) },
                modifier = Modifier.padding(end = 6.dp))
        }
    }
    if (where == net.boswell.phone.assistant.AssistantPrefs.Where.OPENROUTER) {
        Text("A large model in the cloud: fast and capable, a fraction of a cent per question.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    Text("Questions, voice triggers, hints, titles, briefs and recaps go to a model on your computer instead: free, and the text stays at home. " +
        "It's smaller than the cloud models, so answers can be plainer, and slower while your computer is busy. " +
        "Web searches still go to OpenRouter (only it can search the web); without an OpenRouter key the assistant answers from what it knows.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text("When your computer can't answer", style = MaterialTheme.typography.bodyMedium)
    Row {
        for ((f, label) in listOf(net.boswell.phone.assistant.AssistantPrefs.HomeFallback.OPENROUTER to "Use OpenRouter", net.boswell.phone.assistant.AssistantPrefs.HomeFallback.SKIP to "Skip")) {
            FilterChip(selected = fallback == f, onClick = { fallback = f; P.setHomeFallback(ctx, f) }, label = { Text(label) },
                modifier = Modifier.padding(end = 6.dp))
        }
    }
    Text(if (fallback == net.boswell.phone.assistant.AssistantPrefs.HomeFallback.SKIP) "Nothing goes to the cloud: questions say home can't be reached, and titles and hints wait for next time."
        else if (!net.boswell.phone.assistant.Secrets.has(ctx, net.boswell.phone.assistant.Secrets.OPENROUTER)) "OpenRouter answers instead -- once you add its key below."
        else "OpenRouter answers instead until home is back.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(enabled = !busy, onClick = {
            busy = true
            scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                status = runCatching { "Home AI: " + net.boswell.phone.home.HomeServer.llm(ctx) }
                    .getOrElse { "Home AI can't answer: ${it.message}" }
                busy = false
            }
        }) { Text("Test home AI") }
        if (busy) LinearProgressIndicator(Modifier.weight(1f).padding(start = 8.dp))
    }
    status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
}

/** The assistant's own routines and reach: brief, recap, promises, meeting briefs, the web, contacts. */
@Composable
private fun AssistantRoutines() {
    val ctx = LocalContext.current
    val P = net.boswell.phone.assistant.AssistantPrefs
    var brief by remember { mutableStateOf(P.briefHour(ctx)) }
    var recap by remember { mutableStateOf(P.recapHour(ctx)) }
    var promises by remember { mutableStateOf(P.promises(ctx)) }
    var meetings by remember { mutableStateOf(P.meetingBriefs(ctx)) }
    var web by remember { mutableStateOf(P.webSearch(ctx)) }
    var contacts by remember { mutableStateOf(ctx.checkSelfPermission(android.Manifest.permission.READ_CONTACTS) == android.content.pm.PackageManager.PERMISSION_GRANTED) }
    val ask = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { contacts = it }
    fun hourLabel(h: Int) = when { h < 0 -> "Off"; h == 0 -> "12 AM"; h < 12 -> "$h AM"; h == 12 -> "12 PM"; else -> "${h - 12} PM" }

    Text("Morning brief", style = MaterialTheme.typography.bodyLarge)
    Text("Today's calendar, what's due, and loose ends from yesterday, as a notification.", style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    Row(Modifier.horizontalScroll(rememberScrollState())) {
        for (h in listOf(-1, 6, 7, 8, 9)) FilterChip(selected = brief == h, onClick = { brief = h; P.setBriefHour(ctx, h) },
            label = { Text(hourLabel(h)) }, modifier = Modifier.padding(end = 4.dp))
    }
    Text("Evening recap", style = MaterialTheme.typography.bodyLarge)
    Text("Who you talked with, what was decided, and what was promised.", style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    Row(Modifier.horizontalScroll(rememberScrollState())) {
        for (h in listOf(-1, 19, 20, 21, 22)) FilterChip(selected = recap == h, onClick = { recap = h; P.setRecapHour(ctx, h) },
            label = { Text(hourLabel(h)) }, modifier = Modifier.padding(end = 4.dp))
    }
    @Composable fun Toggle(title: String, detail: String, on: Boolean, set: (Boolean) -> Unit) = Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = on, onCheckedChange = set)
    }
    var titles by remember { mutableStateOf(P.titles(ctx)) }
    Toggle("Title each conversation", "A short title and a one-line summary on Today for conversations of a minute or more, a few minutes after they end. About a tenth of a cent each; briefs and recaps read them too.",
        titles) { titles = it; P.setTitles(ctx, it) }
    Toggle("Notice promises", "Every few hours, promises in what was said (yours and others') become to-dos under Promises, and facts about people are remembered.",
        promises) { promises = it; P.setPromises(ctx, it) }
    Toggle("Brief before meetings", "Shortly before a calendar event, what was last said about its people or topic.", meetings) { meetings = it; P.setMeetingBriefs(ctx, it) }
    Toggle("Look things up on the web", "Weather, news, facts and opening hours, when you ask. A few cents a search at most." +
        if (P.where(ctx) == net.boswell.phone.assistant.AssistantPrefs.Where.HOME) " Searches go through OpenRouter even when the AI runs at home." else "", web) { web = it; P.setWebSearch(ctx, it) }

}

/**
 * Back up everything to one file the person chooses where to keep. Restoring
 * is offered in setup, on a new phone or a fresh install.
 */
@Composable
private fun BackupRow() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var withKeys by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<Float?>(null) }
    var result by remember { mutableStateOf<String?>(null) }
    val create = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        progress = 0f; result = null
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            result = runCatching { net.boswell.phone.backup.Backup.export(ctx, uri, withKeys) { progress = it } }
                .fold({ "Backed up ${it.recordings} recordings (${Fmt.bytes(it.bytes)})." + if (it.keys) " Your API key is in it: keep the file private." else "" },
                    { "The backup didn't finish: ${it.message}" })
            progress = null
        }
    }
    Text("Back up", style = MaterialTheme.typography.bodyLarge)
    Text("Recordings, transcripts, people and voices, to-dos, what the assistant remembers, and settings, in one file. " +
        "Restore it from setup on a new phone. The models aren't included; they download again.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = withKeys, onCheckedChange = { withKeys = it })
        Text("Include my API key (readable by anyone with the file)", style = MaterialTheme.typography.bodyMedium)
    }
    val p = progress
    if (p != null) LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
    else OutlinedButton(onClick = { create.launch(net.boswell.phone.backup.Backup.suggestedName()) }) { Text("Back up…") }
    result?.let { Text(it, style = MaterialTheme.typography.bodySmall) }

    // Weekly, into a folder: the copy that's there when the phone isn't.
    val A = net.boswell.phone.backup.AutoBackup
    var folder by remember { mutableStateOf(A.folderName(ctx)) }
    var autoMsg by remember { mutableStateOf(A.lastResult(ctx)?.let { r -> if (A.last(ctx) > 0) "$r, ${Fmt.ago(A.last(ctx) / 1000.0)}" else r }) }
    var autoBusy by remember { mutableStateOf(false) }
    val pick = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree()) { tree ->
        if (tree != null) runCatching { A.setFolder(ctx, tree); folder = A.folderName(ctx) }.onFailure { autoMsg = "Couldn't use that folder: ${it.message}" }
    }
    Text("Automatic backups", style = MaterialTheme.typography.bodyLarge)
    Text(if (folder == null) "Once a week, while the phone charges, into a folder you choose; the newest ${A.KEEP} are kept. " +
            "For a copy that survives losing the phone, choose a folder your cloud storage app keeps in sync. API keys are never included."
        else "Weekly into \"$folder\" while charging, keeping the newest ${A.KEEP}. API keys are never included.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(onClick = { pick.launch(null) }) { Text(if (folder == null) "Choose a folder" else "Change folder") }
        if (folder != null) {
            TextButton(enabled = !autoBusy, onClick = {
                autoBusy = true
                scope.launch(kotlinx.coroutines.Dispatchers.IO) { autoMsg = A.runNow(ctx); autoBusy = false }
            }) { Text(if (autoBusy) "Backing up…" else "Back up now") }
            TextButton(onClick = { A.setFolder(ctx, null); folder = null; autoMsg = null }) { Text("Turn off") }
        }
    }
    autoMsg?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
}

/**
 * Email for the assistant: an address and an app password, servers filled in
 * for the common providers (editable under "Servers"). Checked by signing in
 * before it's kept.
 */
@Composable
private fun EmailSettings() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var saved by remember { mutableStateOf(net.boswell.phone.assistant.Email.account(ctx)?.takeIf { net.boswell.phone.assistant.Email.configured(ctx) }) }
    var address by remember { mutableStateOf(saved?.address ?: "") }
    var password by remember { mutableStateOf("") }
    var servers by remember { mutableStateOf(false) }
    var imap by remember { mutableStateOf(saved?.let { "${it.imapHost}:${it.imapPort}" } ?: "") }
    var smtp by remember { mutableStateOf(saved?.let { "${it.smtpHost}:${it.smtpPort}" } ?: "") }
    var checking by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    Text("Email", style = MaterialTheme.typography.bodyLarge)
    val s = saved
    if (s != null) {
        Text("${s.address} · the assistant can read your inbox, and send email after you confirm each one.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = { net.boswell.phone.assistant.Email.forget(ctx); saved = null; password = ""; message = null }) { Text("Turn off email") }
        return
    }
    Text("Let the assistant read your inbox and send email (only after you confirm each one). Uses an app password, never your main one.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    OutlinedTextField(value = address, onValueChange = {
        address = it.trim()
        if (address.contains('@')) net.boswell.phone.assistant.Email.guess(address).let { g -> imap = "${g.imapHost}:${g.imapPort}"; smtp = "${g.smtpHost}:${g.smtpPort}" }
    }, label = { Text("Email address") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Email))
    OutlinedTextField(value = password, onValueChange = { password = it }, label = { Text("App password") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Password))
    if (address.contains('@')) Text(net.boswell.phone.assistant.Email.appPasswordHelp(address), style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    TextButton(onClick = { servers = !servers }) { Text(if (servers) "Hide servers" else "Servers") }
    if (servers) {
        OutlinedTextField(value = imap, onValueChange = { imap = it.trim() }, label = { Text("Incoming (IMAP) host:port") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = smtp, onValueChange = { smtp = it.trim() }, label = { Text("Outgoing (SMTP) host:port") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    }
    if (checking) LinearProgressIndicator(Modifier.fillMaxWidth())
    else Button(enabled = address.contains('@') && password.isNotBlank(), onClick = {
        checking = true; message = null
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val g = net.boswell.phone.assistant.Email.guess(address)
            fun hp(v: String, h: String, p: Int) = v.substringBefore(':').ifBlank { h } to (v.substringAfter(':', "").toIntOrNull() ?: p)
            val (ih, ip) = hp(imap, g.imapHost, g.imapPort); val (sh, sp) = hp(smtp, g.smtpHost, g.smtpPort)
            val a = net.boswell.phone.assistant.Email.Account(address, ih, ip, sh, sp)
            net.boswell.phone.assistant.Email.save(ctx, a, password)
            val problem = net.boswell.phone.assistant.Email.test(ctx)
            if (problem == null) { saved = a; password = "" } else { net.boswell.phone.assistant.Email.forget(ctx); message = problem }
            checking = false
        }
    }) { Text("Sign in") }
    message?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
}

/**
 * Recordings the phone transcribed itself (home away, or before it was paired),
 * done again at home: by themselves for the last week (CatchUp), or all of
 * them with one button, confirmed first, with progress while it runs.
 */
@Composable
private fun CatchUpSection() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val C = net.boswell.phone.process.CatchUp
    val run by C.state.collectAsStateWithLifecycle()
    var auto by remember { mutableStateOf(C.auto(ctx)) }
    var names by remember { mutableStateOf<List<String>>(emptyList()) }
    var confirm by remember { mutableStateOf(false) }
    var asked by remember { mutableStateOf(false) }
    LaunchedEffect(run.running, run.done) {
        names = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val speakers = net.boswell.phone.speakers.SpeakerStore(ctx)
                val archive = net.boswell.phone.archive.Archive(ctx)
                try { archive.sync(speakers); C.phoneMade(archive) } finally { archive.close(); speakers.close() }
            }.getOrDefault(emptyList())
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Catch up automatically", style = MaterialTheme.typography.bodyLarge)
            Text("Recordings from the last ${C.AUTO_DAYS} days that the phone transcribed while your computer couldn't be reached go home again, " +
                "one at a time, whenever nothing new is waiting. Lines you corrected and voices you named are kept.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = auto, onCheckedChange = { auto = it; C.setAuto(ctx, it) })
    }
    val n = names.size
    // Asked for and not finished: between turns it's waiting, not done -- no second offer.
    var pending by remember { mutableStateOf(C.asked(ctx)) }
    LaunchedEffect(run.running, run.done) { pending = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { C.asked(ctx) } }
    if (n > 0 && !run.running && pending == 0 && !asked) Row(verticalAlignment = Alignment.CenterVertically) {
        Text("$n recording${if (n == 1) "" else "s"} transcribed on the phone ·", style = MaterialTheme.typography.bodyMedium)
        TextButton(enabled = !asked, onClick = { confirm = true }) { Text("Redo them at home") }
    }
    if (run.running) {
        val total = run.done + run.left
        Text("Redoing at home: ${run.done} of $total", style = MaterialTheme.typography.bodyMedium)
        LinearProgressIndicator(progress = { if (total == 0) 0f else run.done.toFloat() / total }, modifier = Modifier.fillMaxWidth())
    } else if (asked || pending > 0) Text(
        (if (pending > 0) "$pending left to redo at home. " else "") + "Continues in a few minutes, while your computer can be reached.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (confirm) AlertDialog(onDismissRequest = { confirm = false },
        title = { Text("Redo $n recording${if (n == 1) "" else "s"} at home?") },
        text = { Text("Each goes to your computer and is transcribed again there, one at a time, after any new recordings. " +
            "Lines you corrected, voices you named and your other answers about voices are kept. " +
            "Titles and summaries are made again where the words changed noticeably.") },
        confirmButton = { TextButton(onClick = {
            confirm = false; asked = true
            val all = names
            scope.launch(kotlinx.coroutines.Dispatchers.IO) { C.requestAll(ctx, all) }
        }) { Text("Redo") } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } })
    LaunchedEffect(run.running) { if (run.running) asked = false }
}

/**
 * Boswell Server on the person's own computer: pair (scan its QR code, or type the
 * address and code), use it for every recording, and choose what happens when
 * home can't be reached.
 */
@Composable
private fun HomeServerSection() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val H = net.boswell.phone.home.HomeServer
    var paired by remember { mutableStateOf(H.paired(ctx)) }
    var enabled by remember { mutableStateOf(H.enabled(ctx)) }
    var fallback by remember { mutableStateOf(H.fallback(ctx)) }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var manual by remember { mutableStateOf(false) }
    var server by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }

    fun pairWith(url: String, c: String) {
        busy = true; status = null
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val problem = H.pair(ctx, url, c)
            status = problem ?: runCatching { H.health(ctx) }.getOrElse { "Paired, but the server didn't answer: ${it.message}" }
            paired = H.paired(ctx); enabled = H.enabled(ctx); busy = false
            if (problem == null) net.boswell.phone.process.ProcessingWorker.enqueue(ctx)
        }
    }

    Text("Let your own computer do the heavy work: a Boswell Server with an NVIDIA graphics card transcribes, separates speakers " +
        "and makes voiceprints in under a second per recording, with larger models, and the phone saves the battery. " +
        "Recordings go only to your computer, over Tailscale.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (!paired) {
        Text("On the computer, run boswell-server and press p to show a pairing code. Install Tailscale on this phone and sign in " +
            "with the same account as the computer.", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = !busy, onClick = {
                com.google.mlkit.vision.codescanner.GmsBarcodeScanning.getClient(ctx).startScan()
                    .addOnSuccessListener { b ->
                        val qr = b.rawValue?.let(H::parseQr)
                        if (qr == null) status = "That isn't a Boswell Server code." else pairWith(qr.first, qr.second)
                    }
                    .addOnFailureListener { status = "Couldn't scan: ${it.message}" }
            }) { Text("Scan the code") }
            OutlinedButton(enabled = !busy, onClick = { manual = !manual }) { Text("Type it") }
        }
        if (manual) {
            OutlinedTextField(value = server, onValueChange = { server = it.trim() }, label = { Text("Address, e.g. http://my-pc.tail1234.ts.net:8765") },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = code, onValueChange = { code = it.filter(Char::isDigit).take(6) }, label = { Text("Code") }, singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number))
            Button(enabled = !busy && server.isNotBlank() && code.length == 6, onClick = { pairWith(server, code) }) { Text("Pair") }
        }
    } else {
        Text("Paired with ${H.url(ctx)}", style = MaterialTheme.typography.bodyMedium)
        // Recordings went to the phone because home couldn't be reached: say so, and since when.
        var trouble by remember { mutableStateOf(H.trouble(ctx)) }
        LaunchedEffect(Unit) { while (true) { trouble = H.trouble(ctx); kotlinx.coroutines.delay(5_000) } }
        trouble?.let { (since, why) ->
            val at = java.time.Instant.ofEpochMilli(since).atZone(java.time.ZoneId.systemDefault())
            val day = if (at.toLocalDate() == java.time.LocalDate.now()) "" else at.format(java.time.format.DateTimeFormatter.ofPattern("MMM d, "))
            androidx.compose.material3.Surface(shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.errorContainer) {
                Column(Modifier.padding(12.dp).fillMaxWidth()) {
                    Text("Can't reach your computer since $day${at.format(java.time.format.DateTimeFormatter.ofPattern("h:mm a"))}",
                        style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onErrorContainer)
                    Text(why + if (fallback == net.boswell.phone.home.HomeServer.Fallback.PHONE)
                            " Meanwhile the phone transcribes recordings itself; they're redone at home once it's back."
                        else " Recordings wait for it.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Process recordings at home", style = MaterialTheme.typography.bodyLarge)
                Text("Every recording goes to your computer; the phone puts the result together and recognizes people as usual.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = enabled, onCheckedChange = {
                enabled = it; H.setEnabled(ctx, it)
                net.boswell.phone.process.ProcessingWorker.enqueue(ctx)
            })
        }
        Text("When your computer can't be reached", style = MaterialTheme.typography.bodyMedium)
        Row {
            for ((f, label) in listOf(net.boswell.phone.home.HomeServer.Fallback.PHONE to "Use the phone", net.boswell.phone.home.HomeServer.Fallback.WAIT to "Wait for home")) {
                FilterChip(selected = fallback == f, onClick = { fallback = f; H.setFallback(ctx, f) }, label = { Text(label) },
                    modifier = Modifier.padding(end = 6.dp))
            }
        }
        Text(if (fallback == net.boswell.phone.home.HomeServer.Fallback.WAIT) "Recordings wait on the phone and go home when it's reachable again: no battery spent on them meanwhile."
            else "The phone transcribes them itself until home is back.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        CatchUpSection()
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = !busy, onClick = {
                busy = true
                scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    status = runCatching { H.health(ctx) }.getOrElse { "Can't reach it: ${it.message}. Is the computer on, and Tailscale on here?" }
                    busy = false
                }
            }) { Text("Test") }
            TextButton(onClick = { H.forget(ctx); paired = false; enabled = false; status = null }) { Text("Unpair") }
        }
    }
    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
}

