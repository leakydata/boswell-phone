package net.boswell.phone.setup

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.boswell.phone.assistant.Secrets
import net.boswell.phone.assistant.Triggers
import net.boswell.phone.capture.Link
import net.boswell.phone.models.ModelProgressRepository
import net.boswell.phone.sync.Mode
import net.boswell.phone.ui.Fmt
import net.boswell.phone.ui.MainViewModel

object Setup {
    private fun p(c: Context) = c.getSharedPreferences("boswell", Context.MODE_PRIVATE)
    fun done(c: Context) = p(c).getBoolean("setup_done", false)
    fun setDone(c: Context, d: Boolean) = p(c).edit().putBoolean("setup_done", d).apply()
}

private enum class Step { WELCOME, PERMISSIONS, OMI, MODE, MODELS, YOU, ASSISTANT, BACKGROUND, DONE }

/** A short, neutral paragraph to read aloud: varied sounds, no names, about twenty seconds. */
private const val PASSAGE = "The morning light came through the kitchen window while the kettle began to hum. " +
    "I checked the weather, found my keys beside the fruit bowl, and made a short list for the day: " +
    "call the plumber, pick up bread and oranges, and finish the report before four o'clock. " +
    "Outside, a neighbour was walking two small dogs, and somewhere a radio played an old song."

/**
 * First run, one decision per screen: permissions, the Omi, how it should
 * connect, the on-phone models, learning your voice, the optional assistant,
 * and keeping it alive in the background. Every step can be skipped and
 * changed later on the Device page.
 */
@Composable
fun SetupScreen(vm: MainViewModel, onPair: () -> Unit, onFinish: () -> Unit, voiceOnly: Boolean = false) {
    var step by rememberSaveable { mutableStateOf(if (voiceOnly) Step.YOU else Step.WELCOME) }
    val ctx0 = LocalContext.current
    var name by rememberSaveable { mutableStateOf(net.boswell.phone.assistant.AssistantPrefs.owner(ctx0)
        ?.let { net.boswell.phone.speakers.SpeakerStore(ctx0).let { s -> try { s.nameOf(it) } finally { s.close() } } } ?: "") }
    val ui by vm.ui.collectAsStateWithLifecycle()
    val cap by vm.capture.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    fun next() {
        // Leaving "who are you": the name counts even if the voice part was skipped.
        if (step == Step.YOU && name.isNotBlank()) Enrollment.claimName(ctx, name)
        if (voiceOnly && step == Step.YOU) { onFinish(); return }
        step = Step.entries[(step.ordinal + 1).coerceAtMost(Step.entries.lastIndex)]
    }
    fun back() { step = Step.entries[(step.ordinal - 1).coerceAtLeast(0)] }

    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding().padding(horizontal = 24.dp)) {
            LinearProgressIndicator(progress = { step.ordinal / Step.DONE.ordinal.toFloat() }, modifier = Modifier.fillMaxWidth().padding(top = 16.dp))
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(top = 28.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                when (step) {
                    Step.WELCOME -> {
                        Title("Welcome to Boswell")
                        Body("Boswell turns what your Omi hears into a record of your day: conversations, who said what, and the things you said you'd do.")
                        Body("Transcribing, recognising voices and sorting it all happens on this phone. Nothing is sent anywhere unless you switch on the assistant later, and then only text.")
                        Body("Setup takes a couple of minutes. Every step can be skipped and changed later.")
                    }
                    Step.PERMISSIONS -> Permissions()
                    Step.OMI -> {
                        Title("Find your Omi")
                        Body("Turn your Omi on and keep it near the phone. Don't pair it in Android's Bluetooth settings; Boswell connects to it directly.")
                        if (ui.savedAddress != null) Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                            Text("Using ${ui.savedAddress}", Modifier.padding(16.dp), fontWeight = FontWeight.SemiBold)
                        }
                        OutlinedButton(onClick = vm::scan, enabled = !ui.scanning) { Text(if (ui.scanning) "Looking…" else "Look for Omi devices") }
                        for (d in ui.found) Card(Modifier.fillMaxWidth().clickable { vm.chooseOnly(d.address) }) {
                            Row(Modifier.padding(16.dp).fillMaxWidth()) {
                                Text("${d.name ?: "Omi"}  ${d.address}", Modifier.weight(1f))
                                Text("${d.advertisedRssi} dBm")
                            }
                        }
                    }
                    Step.MODE -> {
                        Title("How should it connect?")
                        ModeCard("Live", "The phone stays connected. Conversations appear within seconds, and you can ask questions with the Omi's button.",
                            ui.mode == Mode.LIVE) { vm.setMode(Mode.LIVE) }
                        ModeCard("Sync", "The Omi records on its own and the phone collects it every so often. Easier on both batteries; things show up later.",
                            ui.mode == Mode.SYNC) { vm.setMode(Mode.SYNC) }
                        if (ui.mode == Mode.SYNC) {
                            Body("Pair the Omi as a companion so the phone can collect whenever it comes into range:")
                            OutlinedButton(onClick = onPair, enabled = !ui.companionPaired) { Text(if (ui.companionPaired) "Paired" else "Sync when in range") }
                        }
                        Body("You'll need Live for the next step, where Boswell learns your voice; it switches to your choice afterwards.")
                    }
                    Step.MODELS -> Models(vm, ui)
                    Step.YOU -> You(vm, ui.mode, cap.link == Link.STREAMING, ui.savedAddress != null, name) { name = it }
                    Step.ASSISTANT -> Assistant()
                    Step.BACKGROUND -> {
                        Title("Keep it running")
                        Body("Android stops apps in the background to save battery. Allow Boswell to run unrestricted so it keeps recording and syncing all day.")
                        if (ui.batteryExempt) Text("Allowed ✓", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                        else Button(onClick = { ctx.startActivity(vm.batteryExemptionIntent()) }) { Text("Allow") }
                        LaunchedEffect(Unit) { vm.refreshStorage() }
                    }
                    Step.DONE -> {
                        Title("You're set")
                        Body("Your day appears on Today as conversations happen. People you haven't named show up in People, ready to be named.")
                        if (ui.mode == Mode.LIVE) Body("Tap the Omi's button to ask a question; double tap to add a to-do.")
                        Body("Everything here can be changed on the Device page.")
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                if (step != Step.WELCOME && !voiceOnly) TextButton(onClick = ::back) { Text("Back") }
                Spacer(Modifier.weight(1f))
                if (step in listOf(Step.OMI, Step.MODELS, Step.YOU, Step.ASSISTANT, Step.BACKGROUND)) TextButton(onClick = ::next) { Text("Skip") }
                Spacer(Modifier.width(8.dp))
                Button(onClick = {
                    if (step == Step.DONE) { Setup.setDone(ctx, true); onFinish() } else next()
                }) { Text(when {
                    step == Step.WELCOME -> "Get started"
                    step == Step.DONE -> "Open Boswell"
                    voiceOnly -> "Done"
                    else -> "Next" }) }
            }
        }
    }
}

@Composable private fun Title(t: String) = Text(t, style = MaterialTheme.typography.headlineMedium)
@Composable private fun Body(t: String) = Text(t, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
private fun ModeCard(title: String, text: String, selected: Boolean, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick), colors = CardDefaults.cardColors(
        containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(16.dp)) {
            Text(title + if (selected) "  ✓" else "", style = MaterialTheme.typography.titleMedium)
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun Permissions() {
    val ctx = LocalContext.current
    fun granted(p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED
    var tick by remember { mutableStateOf(0) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { tick++ }
    Title("Permissions")
    val rows = listOf(
        Triple("Nearby devices", "To find and connect to your Omi over Bluetooth.", listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)),
        Triple("Notifications", "To show that it's recording, and to deliver answers and reminders.", listOf(Manifest.permission.POST_NOTIFICATIONS)),
    )
    for ((name, why, perms) in rows) {
        @Suppress("UNUSED_EXPRESSION") tick
        val ok = perms.all(::granted)
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(name, style = MaterialTheme.typography.titleMedium)
                    Text(why, style = MaterialTheme.typography.bodyMedium)
                }
                if (ok) Text("✓", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleLarge)
                else TextButton(onClick = { launcher.launch(perms.toTypedArray()) }) { Text("Allow") }
            }
        }
    }
    Body("Location is never used: Boswell finds the Omi by the service it advertises.")
}

@Composable
private fun Models(vm: MainViewModel, ui: net.boswell.phone.ui.UiState) {
    val progress by ModelProgressRepository.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.refreshModels() }
    Title("Download the on-phone models")
    Body("Transcription, voices and sounds are recognised on this phone. That takes about ${Fmt.bytes(ui.models.sumOf { it.spec.totalBytes })} of models, downloaded once.")
    for (m in ui.models) {
        val p = progress[m.spec.id]
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(m.spec.purpose.replaceFirstChar { it.uppercase() }, Modifier.weight(1f))
                Text(if (m.installed) "✓" else Fmt.bytes(m.spec.totalBytes), color = if (m.installed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (p?.running == true) LinearProgressIndicator(progress = { p.bytes.toFloat() / p.total }, modifier = Modifier.fillMaxWidth())
            p?.error?.takeIf { !m.installed && !p.running }?.let { Text("Retrying: $it", style = MaterialTheme.typography.bodySmall) }
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Switch(checked = ui.wifiOnly, onCheckedChange = vm::setWifiOnly)
        Spacer(Modifier.width(8.dp))
        Text("Wi-Fi only")
    }
    if (ui.models.any { !it.installed }) Button(onClick = vm::downloadAll) { Text("Download") }
    else Text("All downloaded ✓", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
    Body("It keeps going in the background if you move on.")
}

@Composable
private fun You(vm: MainViewModel, mode: Mode, streaming: Boolean, hasOmi: Boolean, name: String, setName: (String) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val en by Enrollment.state.collectAsStateWithLifecycle()
    var result by remember { mutableStateOf<String?>(null) }
    var enrolled by rememberSaveable { mutableStateOf(false) }
    val chosenMode = remember { mode }
    Title("Who are you?")
    Body("Boswell names the voices it knows. Tell it yours and read a short passage so it recognises you, and so the assistant knows which voice is you.")
    OutlinedTextField(value = name, onValueChange = setName, label = { Text("Your name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    if (!hasOmi) { Body("Connect an Omi first (the earlier step) to learn your voice. You can do it later from People."); return }
    val modelsReady = vm.ui.value.models.filter { it.spec.id == "voiceprint" || it.spec.id == "segmentation" }.all { it.installed }
    if (!modelsReady) { Body("Waiting for the voice model to finish downloading…"); return }
    when {
        enrolled -> Text("Learned your voice ✓ ${result ?: ""}", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
        !en.active && en.heardSeconds == 0.0 -> Button(enabled = name.isNotBlank(), onClick = {
            vm.setMode(Mode.LIVE)          // enrolment listens to the live stream
            Enrollment.start()
        }) { Text("Start reading") }
        else -> {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                Text(PASSAGE, Modifier.padding(16.dp), style = MaterialTheme.typography.bodyLarge)
            }
            if (!streaming) Body("Connecting to your Omi…")
            LinearProgressIndicator(progress = { (en.heardSeconds / en.targetSeconds).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
            Text(if (en.active) "Heard %.0f of %.0f seconds of your voice — keep reading, at a normal pace".format(en.heardSeconds, en.targetSeconds)
                else "Got it, saving…", style = MaterialTheme.typography.bodyMedium)
            var saving by remember { mutableStateOf(false) }
            fun save() {
                if (saving) return
                saving = true
                Enrollment.stop()
                scope.launch {
                    val r = withContext(Dispatchers.IO) { Enrollment.finish(ctx, name) }
                    saving = false
                    r.onSuccess { enrolled = true; result = null; if (chosenMode != Mode.LIVE) vm.setMode(chosenMode) }
                        .onFailure { result = "Couldn't save: ${it.message}. Keep reading and it will try again."
                            net.boswell.phone.capture.CaptureRepository.log("voice enrollment failed: ${it.message}"); Enrollment.start() }
                }
            }
            // Saves by itself once it has heard enough; the button is for stopping early.
            // Finishes by itself: at the target, or when you stop reading with enough heard.
            LaunchedEffect(en.active) {
                if (!en.active && en.heardSeconds >= Enrollment.MIN_SECONDS && !enrolled) save()
            }
            val cap2 by vm.capture.collectAsStateWithLifecycle()
            LaunchedEffect(cap2.lastAudioMillis, en.active) {
                if (!en.active) return@LaunchedEffect
                kotlinx.coroutines.delay(2_500)
                val last = vm.capture.value.lastAudioMillis ?: 0L
                if (System.currentTimeMillis() - last >= 2_400) Enrollment.paused()
            }
            if (en.active) {
                Text("Hearing you", style = MaterialTheme.typography.labelMedium)
                LinearProgressIndicator(progress = { en.level }, modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.tertiary)
            }
            if (en.active && en.heardSeconds >= Enrollment.MIN_SECONDS) OutlinedButton(onClick = ::save) { Text("That's enough, save it") }
            if (saving) Text("Saving your voice…", style = MaterialTheme.typography.bodyMedium)
            result?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable
private fun Assistant() {
    val ctx = LocalContext.current
    var hasKey by remember { mutableStateOf(Secrets.has(ctx, Secrets.OPENROUTER)) }
    var key by remember { mutableStateOf("") }
    var triggers by remember { mutableStateOf(Triggers.enabled(ctx)) }
    var calendarOk by remember { mutableStateOf(net.boswell.phone.todo.Calendar.allowed(ctx)) }
    val calLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        calendarOk = net.boswell.phone.todo.Calendar.allowed(ctx)
    }
    Title("The assistant (optional)")
    Body("Ask questions about your day with the Omi's button, capture to-dos by voice, and let phrases like \"remind me…\" act on their own. " +
        "It uses a model through OpenRouter; only text is sent, and each use costs a fraction of a cent.")
    if (hasKey) Text("OpenRouter key saved ✓", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
    else Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(value = key, onValueChange = { key = it }, singleLine = true, label = { Text("OpenRouter API key") },
            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.weight(1f))
        TextButton(enabled = key.isNotBlank(), onClick = { Secrets.put(ctx, Secrets.OPENROUTER, key); key = ""; hasKey = true }) { Text("Save") }
    }
    if (hasKey) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Voice triggers", style = MaterialTheme.typography.titleMedium)
                Text("Act on \"remind me…\", \"note to self…\", \"hey Boswell…\" when you say them.", style = MaterialTheme.typography.bodyMedium)
            }
            Switch(checked = triggers, onCheckedChange = { triggers = it; Triggers.setEnabled(ctx, it) })
        }
        var chosen by remember { mutableStateOf(net.boswell.phone.todo.Calendar.chosen(ctx)) }
        var picking by remember { mutableStateOf(false) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Calendar", style = MaterialTheme.typography.titleMedium)
                Text(chosen?.let { "Events go to ${it.name} (${it.account})" }
                    ?: if (calendarOk) "Choose which calendar the assistant adds events to" else "Let the assistant add appointments",
                    style = MaterialTheme.typography.bodyMedium)
            }
            if (!calendarOk) TextButton(onClick = { calLauncher.launch(arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)) }) { Text("Allow") }
            else TextButton(onClick = { picking = true }) { Text(if (chosen == null) "Choose" else "Change") }
        }
        LaunchedEffect(calendarOk) { if (calendarOk && chosen == null) picking = true }
        if (picking) net.boswell.phone.ui.CalendarPicker(onDismiss = { picking = false }) { c -> chosen = c; picking = false }
    }
    Spacer(Modifier.height(4.dp))
}
