package net.boswell.phone.ui

import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Subject
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.boswell.phone.capture.CaptureState
import net.boswell.phone.models.ModelProgressRepository
import net.boswell.phone.process.ProcessingRepository
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class Tab(val label: String) { RECORD("Record"), TRANSCRIPTS("Transcripts"), PEOPLE("People"), MODELS("Models") }

@Composable
internal fun AppScaffold(ui: UiState, cap: CaptureState, vm: MainViewModel) {
    var tab by rememberSaveable { mutableStateOf(Tab.RECORD) }
    Scaffold(bottomBar = {
        NavigationBar {
            for (t in Tab.entries) NavigationBarItem(
                selected = tab == t,
                onClick = { tab = t; vm.refreshAll() },
                icon = {
                    Icon(when (t) {
                        Tab.RECORD -> Icons.Filled.Mic
                        Tab.TRANSCRIPTS -> Icons.Filled.Subject
                        Tab.PEOPLE -> Icons.Filled.People
                        Tab.MODELS -> Icons.Filled.Download
                    }, null)
                },
                label = { Text(t.label) },
            )
        }
    }) { pad ->
        when (tab) {
            Tab.RECORD -> RecordScreen(ui, cap, vm, pad)
            Tab.TRANSCRIPTS -> TranscriptsScreen(ui, vm, pad)
            Tab.PEOPLE -> PeopleScreen(ui, vm, pad)
            Tab.MODELS -> ModelsScreen(ui, vm, pad)
        }
    }
}

private fun pads(pad: PaddingValues) =
    PaddingValues(start = 16.dp, end = 16.dp, top = pad.calculateTopPadding() + 8.dp, bottom = pad.calculateBottomPadding() + 16.dp)

@Composable
private fun TranscriptsScreen(ui: UiState, vm: MainViewModel, pad: PaddingValues) {
    val proc by ProcessingRepository.state.collectAsStateWithLifecycle()
    val time = remember { SimpleDateFormat("MMM d HH:mm:ss", Locale.getDefault()) }
    LazyColumn(contentPadding = pads(pad), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Text("Transcripts", style = MaterialTheme.typography.headlineMedium) }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val waiting = ui.clips.count { it.transcript == null }
                    Text(when {
                        proc.running -> "Working on ${proc.current ?: "…"} · ${proc.pending} left"
                        !ui.modelsReady -> "Download the three models (Models tab) to transcribe on the phone."
                        waiting > 0 -> "$waiting clip(s) waiting"
                        else -> "All clips transcribed"
                    })
                    proc.lastError?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    if (!proc.running && ui.modelsReady && waiting > 0) Button(onClick = vm::transcribeNow) { Text("Transcribe now") }
                }
            }
        }
        items(ui.clips, key = { it.file.name }) { c ->
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { vm.togglePlay(c.file) }) {
                        Icon(if (ui.playing == c.file) Icons.Filled.Stop else Icons.Filled.PlayArrow, null)
                    }
                    Text(time.format(Date(c.endedMillis)) + "  ·  %.0f s".format(c.seconds), fontWeight = FontWeight.SemiBold)
                }
                val t = c.transcript
                when {
                    t == null -> Text("not transcribed yet", style = MaterialTheme.typography.bodySmall)
                    t.error != null -> Text("could not process: ${t.error}", style = MaterialTheme.typography.bodySmall)
                    t.lines.isEmpty() -> Text("no speech", style = MaterialTheme.typography.bodySmall)
                    else -> for (line in t.lines) {
                        Row(Modifier.padding(start = 8.dp, top = 2.dp)) {
                            Text(line.who + ": ", fontWeight = FontWeight.SemiBold, color = if (line.named) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(line.text)
                        }
                    }
                }
            }
            HorizontalDivider()
        }
    }
}

@Composable
private fun PeopleScreen(ui: UiState, vm: MainViewModel, pad: PaddingValues) {
    var naming by remember { mutableStateOf<PersonRow?>(null) }
    LazyColumn(contentPadding = pads(pad), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Text("People", style = MaterialTheme.typography.headlineMedium) }
        item {
            Text("Voices the phone has heard. Unnamed voices that recur are grouped; name one and every " +
                "recording of that voice is labelled, now and from then on.", style = MaterialTheme.typography.bodySmall)
        }
        if (ui.people.isEmpty()) item { Text("Nobody yet: voices appear here once clips are transcribed.") }
        items(ui.people, key = { it.id }) { p ->
            Card(Modifier.fillMaxWidth().clickable { naming = p }) {
                Row(Modifier.padding(16.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { vm.playSample(p.id) }) { Icon(Icons.Filled.GraphicEq, "play a sample") }
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(p.name ?: "Unknown voice #${p.id}", fontWeight = FontWeight.SemiBold)
                        Text("${p.voiceprints} sighting(s) · %.0f s of speech".format(p.seconds), style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = { naming = p }) { Text(if (p.name == null) "Name" else "Rename") }
                }
            }
        }
    }
    naming?.let { p ->
        var text by remember(p.id) { mutableStateOf(p.name ?: "") }
        AlertDialog(
            onDismissRequest = { naming = null },
            title = { Text(if (p.name == null) "Who is this?" else "Rename") },
            text = {
                Column {
                    Text("Using a name that already exists merges this voice into that person.", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true)
                }
            },
            confirmButton = { TextButton(enabled = text.isNotBlank(), onClick = { vm.namePerson(p.id, text.trim()); naming = null }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { naming = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ModelsScreen(ui: UiState, vm: MainViewModel, pad: PaddingValues) {
    val progress by ModelProgressRepository.state.collectAsStateWithLifecycle()
    LazyColumn(contentPadding = pads(pad), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Text("Models", style = MaterialTheme.typography.headlineMedium) }
        item {
            Text("Nothing ships inside the app. These download from the boswell-phone GitHub release and are " +
                "checked against their SHA-256 before use. Everything then runs on the phone.", style = MaterialTheme.typography.bodySmall)
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = ui.wifiOnly, onCheckedChange = vm::setWifiOnly)
                Text("Wi-Fi only")
                Spacer(Modifier.width(16.dp))
                OutlinedButton(onClick = vm::downloadAll, enabled = ui.models.any { !it.installed }) { Text("Download all") }
            }
        }
        items(ui.models, key = { it.spec.id }) { m ->
            val p = progress[m.spec.id]
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(m.spec.name, fontWeight = FontWeight.SemiBold)
                    Text("${m.spec.purpose} · %.0f MB · ${m.spec.license}".format(m.spec.totalBytes / 1e6), style = MaterialTheme.typography.bodySmall)
                    when {
                        m.installed -> Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Installed", color = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(12.dp))
                            TextButton(onClick = { vm.deleteModel(m.spec.id) }) { Text("Delete") }
                        }
                        p?.running == true -> {
                            LinearProgressIndicator(progress = { p.bytes.toFloat() / p.total }, modifier = Modifier.fillMaxWidth())
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("%.0f / %.0f MB".format(p.bytes / 1e6, p.total / 1e6), style = MaterialTheme.typography.bodySmall)
                                Spacer(Modifier.width(12.dp))
                                TextButton(onClick = { vm.cancelDownload(m.spec.id) }) { Text("Cancel") }
                            }
                        }
                        else -> {
                            p?.error?.let { Text("Last attempt: $it", style = MaterialTheme.typography.bodySmall) }
                            if (m.partialBytes > 0) Text("%.0f MB already downloaded, will resume".format(m.partialBytes / 1e6), style = MaterialTheme.typography.bodySmall)
                            Button(onClick = { vm.download(m.spec.id) }) { Text("Download") }
                        }
                    }
                }
            }
        }
    }
}
