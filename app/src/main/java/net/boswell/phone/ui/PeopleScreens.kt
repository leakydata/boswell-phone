package net.boswell.phone.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.boswell.phone.speakers.Person

private fun Person.asVoice() = Voice("p$id", name ?: if (kind == "media") "TV / media" else "Unknown voice $id", name != null, id, kind == "media")

@Composable
fun PeopleScreen(vm: ArchiveViewModel, pad: PaddingValues, onPerson: (Long) -> Unit, onOpenConversation: (Long) -> Unit, onLearnVoice: () -> Unit = {}) {
    val s by vm.people.collectAsStateWithLifecycle()
    var naming by remember { mutableStateOf<Person?>(null) }
    LazyColumn(
        contentPadding = PaddingValues(top = pad.calculateTopPadding() + 8.dp, bottom = pad.calculateBottomPadding() + 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Text("People", style = MaterialTheme.typography.headlineLarge, modifier = Modifier.padding(horizontal = 16.dp)) }
        item {
            val ctx = androidx.compose.ui.platform.LocalContext.current
            val owner = net.boswell.phone.assistant.AssistantPrefs.owner(ctx)
            val me = s.named.firstOrNull { it.id == owner }
            Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(if (me != null) "You: ${me.name}" else "Teach Boswell your voice", style = MaterialTheme.typography.titleMedium)
                        Text(if (me != null) "${me.voiceprints} voice sample${if (me.voiceprints == 1) "" else "s"} · read a passage again to improve recognition"
                            else "Read a short passage through your Omi so it knows which voice is you.", style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = onLearnVoice) { Text(if (me != null) "Improve" else "Start") }
                }
            }
        }

        if (s.queue.isNotEmpty()) {
            item {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    Text("Who's this?", style = MaterialTheme.typography.titleMedium)
                    Text("Voices heard more than once that nobody has named. Name one and every recording of it is labeled.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(s.queue, key = { it.id }) { p ->
                        Card(Modifier.width(260.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Avatar(p.asVoice(), 40)
                                    Spacer(Modifier.width(10.dp))
                                    Column {
                                        Text("Unknown voice", style = MaterialTheme.typography.titleMedium)
                                        Text("${p.voiceprints}× · ${Fmt.duration(p.seconds)} · ${Fmt.ago(p.lastHeard)}", style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(onClick = { vm.sampleConversation(p.id)?.let(onOpenConversation) }) {
                                        Icon(Icons.Filled.PlayArrow, null); Text("Hear")
                                    }
                                    Button(onClick = { naming = p }) { Text("Name") }
                                }
                                Row {
                                    TextButton(onClick = { vm.setKind(p.id, "media") }) { Text("It's TV") }
                                    TextButton(onClick = { vm.skip(p.id) }) { Text("Skip") }
                                }
                            }
                        }
                    }
                }
            }
        }

        item { Text("Known", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 16.dp)) }
        if (s.named.isEmpty()) item {
            Text("Nobody named yet. Open a conversation and tap a voice to say who it is.",
                Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        items(s.named, key = { it.id }) { p ->
            ListItem(
                modifier = Modifier.padding(horizontal = 4.dp),
                leadingContent = { Avatar(p.asVoice(), 44) },
                headlineContent = { Text(p.name ?: "") },
                supportingContent = { Text("Last heard ${Fmt.ago(p.lastHeard)} · ${p.voiceprints} voice sample${if (p.voiceprints == 1) "" else "s"}") },
                trailingContent = { TextButton(onClick = { onPerson(p.id) }) { Text("Open") } },
            )
        }
        if (s.media.isNotEmpty()) item {
            Text("${s.media.size} voice${if (s.media.size == 1) "" else "s"} marked as TV or media", Modifier.padding(horizontal = 16.dp),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    naming?.let { p -> NameDialog(p.name, onDismiss = { naming = null }) { vm.namePerson(p.id, it); naming = null } }
}

@Composable
fun NameDialog(current: String?, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(current ?: "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (current == null) "Who is this?" else "Rename") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true, label = { Text("Name") })
                Text("Using a name that already exists joins this voice to that person.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(enabled = text.isNotBlank(), onClick = { onSave(text.trim()) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PersonScreen(vm: ArchiveViewModel, id: Long, onBack: () -> Unit, onOpen: (Long) -> Unit) {
    val s by vm.person.collectAsStateWithLifecycle()
    var renaming by remember { mutableStateOf(false) }
    LaunchedEffect(id) { vm.openPerson(id) }
    val p = s.person
    Scaffold(topBar = {
        TopAppBar(
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "back") } },
            title = { Text(p?.name ?: "Unknown voice") },
            actions = { TextButton(onClick = { renaming = true }) { Text("Rename") } },
        )
    }) { pad ->
        LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = pad.calculateTopPadding() + 8.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (p != null) item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Avatar(p.asVoice(), 64)
                    Spacer(Modifier.width(16.dp))
                    Column {
                        Text("${s.conversations.size} conversation${if (s.conversations.size == 1) "" else "s"}", style = MaterialTheme.typography.titleMedium)
                        Text("Last heard ${Fmt.ago(p.lastHeard)} · ${p.voiceprints} voice samples", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            if (p?.name != null) item {
                val ctx = androidx.compose.ui.platform.LocalContext.current
                var me by remember(p.id) { mutableStateOf(net.boswell.phone.assistant.AssistantPrefs.owner(ctx) == p.id) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("This is me", style = MaterialTheme.typography.bodyLarge)
                        Text("The assistant listens for your voice and answers you.", style = MaterialTheme.typography.bodySmall)
                    }
                    androidx.compose.material3.Switch(checked = me, onCheckedChange = {
                        me = it; net.boswell.phone.assistant.AssistantPrefs.setOwner(ctx, if (it) p.id else null)
                    })
                }
            }
            item { Text("Conversations", style = MaterialTheme.typography.titleMedium) }
            items(s.conversations, key = { it.id }) { c ->
                Card(onClick = { onOpen(c.id) }, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        Text("${Fmt.shortDay(java.time.Instant.ofEpochSecond(c.started.toLong()).atZone(java.time.ZoneId.systemDefault()).toLocalDate())} · ${Fmt.time(c.started)} · ${Fmt.duration(c.ended - c.started)}",
                            style = MaterialTheme.typography.labelLarge)
                        Text(c.snippet, maxLines = 2, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            if (s.groups.size > 1 || s.groups.any { it.voiceprints > 1 }) {
                item {
                    Column {
                        Text("Voice samples", style = MaterialTheme.typography.titleMedium)
                        Text("Each group arrived together. If one isn't ${p?.name ?: "them"}, take it back off; nothing is deleted.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                items(s.groups, key = { it.key }) { g ->
                    ListItem(
                        headlineContent = { Text("${g.voiceprints} sample${if (g.voiceprints == 1) "" else "s"} · ${Fmt.duration(g.seconds)}") },
                        supportingContent = { Text("first heard ${Fmt.ago(g.firstHeard)} · ${g.clips.size} clip${if (g.clips.size == 1) "" else "s"}") },
                        trailingContent = { TextButton(onClick = { vm.unnameGroup(id, g.key) }) { Text("Not them") } },
                    )
                }
            }
        }
    }
    if (renaming) NameDialog(p?.name, onDismiss = { renaming = false }) { vm.namePerson(id, it); renaming = false }
}
