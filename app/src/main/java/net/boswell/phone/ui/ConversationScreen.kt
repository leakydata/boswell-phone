package net.boswell.phone.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import net.boswell.phone.ui.theme.PauseBars
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.boswell.phone.archive.LineRow
import net.boswell.phone.sound.Sounds
import net.boswell.phone.ui.theme.Voices

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationScreen(vm: ArchiveViewModel, id: Long, focusLine: Long?, onBack: () -> Unit, onPerson: (Long) -> Unit) {
    val s by vm.conv.collectAsStateWithLifecycle()
    var who by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(id) { vm.openConversation(id) }
    DisposableEffect(Unit) { onDispose { vm.closeConversation() } }
    val list = rememberLazyListState()
    LaunchedEffect(focusLine, s.lines.size) {
        // Scroll to the searched-for line's turn (lines merge into turns, so find by position in time).
        val target = s.lines.firstOrNull { it.id == focusLine } ?: return@LaunchedEffect
        val i = s.lines.filter { it.t0 < target.t0 }.map { it.speaker }.zipWithNext().count { (a, b) -> a != b }
        list.scrollToItem((i + 1).coerceAtMost(s.lines.size))
    }

    val c = s.conversation
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "back") } },
                title = {
                    Column {
                        Text(if (c == null) "" else "${Fmt.time(c.started)} – ${Fmt.time(c.ended)}", style = MaterialTheme.typography.titleLarge)
                        if (c != null) Text("${Fmt.shortDay(java.time.Instant.ofEpochSecond(c.started.toLong()).atZone(java.time.ZoneId.systemDefault()).toLocalDate())} · ${Fmt.duration(c.ended - c.started)}",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
            )
        },
        bottomBar = { PlayerBar(s, vm) },
    ) { pad ->
        LazyColumn(state = list, contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = pad.calculateTopPadding() + 4.dp, bottom = pad.calculateBottomPadding() + 16.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)) {
            item {
                @OptIn(ExperimentalLayoutApi::class)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                    for ((key, v) in s.voices) {
                        val guess = s.guesses[key]
                        Surface(onClick = { who = key }, shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                            Row(Modifier.padding(start = 4.dp, end = 12.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Avatar(v, 26)
                                Spacer(Modifier.width(6.dp))
                                Text(if (!v.named && guess != null) "${v.name} · ${guess.first.name}?" else v.name, style = MaterialTheme.typography.labelLarge)
                            }
                        }
                    }
                    c?.sounds?.map(Sounds::display)?.distinct()?.forEach { SoundChip(it) }
                }
            }
            // Consecutive lines from one voice with no real pause between them
            // are one thing said: the 30 s clip boundary and the transcriber's
            // own line breaks should not split a sentence into three bubbles.
            val turns = s.lines.fold(mutableListOf<MutableList<LineRow>>()) { acc, l ->
                val last = acc.lastOrNull()?.last()
                if (last != null && last.speaker == l.speaker && l.t0 - last.t1 < 2.5) acc.last() += l else acc += mutableListOf(l)
                acc
            }
            itemsIndexed(turns, key = { _, t -> t.first().id }) { i, turn ->
                val line = turn.first()
                val prev = turns.getOrNull(i - 1)?.last()
                val newSpeaker = prev?.speaker != line.speaker || line.t0 - (prev?.t1 ?: 0.0) > 30
                val merged = if (turn.size == 1) line else line.copy(text = turn.joinToString(" ") { it.text }, t1 = turn.last().t1)
                Bubble(merged, s.voices[line.speaker], newSpeaker, turn.any { it.id == s.playingLine },
                    onTap = { vm.playLine(line) }, onWho = { line.speaker?.let { who = it } })
            }
            if (s.lines.isEmpty() && c != null) item { Text("No words were transcribed in this conversation.", Modifier.padding(16.dp)) }
        }
    }

    who?.let { key ->
        WhoSheet(vm, s, key, onDismiss = { who = null }, onPerson = { pid -> who = null; onPerson(pid) })
    }
}

@Composable
private fun Bubble(line: LineRow, v: Voice?, header: Boolean, playing: Boolean, onTap: () -> Unit, onWho: () -> Unit) {
    val color = Voices.color(line.speaker)
    Column(Modifier.fillMaxWidth().padding(top = if (header) 10.dp else 0.dp)) {
        if (header) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable(onClick = onWho).padding(bottom = 4.dp)) {
            if (v != null) Avatar(v, 22) else Spacer(Modifier.size(22.dp))
            Spacer(Modifier.width(6.dp))
            Text(v?.name ?: "Unattributed", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold,
                color = if (v?.named == true) color else MaterialTheme.colorScheme.onSurfaceVariant)
            Text("  ${Fmt.time(line.t0)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Surface(
            onClick = onTap,
            shape = RoundedCornerShape(topStart = if (header) 4.dp else 16.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 16.dp),
            color = if (v?.media == true) MaterialTheme.colorScheme.surfaceVariant else color.copy(alpha = if (playing) 0.35f else 0.14f),
            border = if (playing) BorderStroke(1.5.dp, color) else null,
            modifier = Modifier.padding(start = 28.dp, end = 24.dp),
        ) {
            Text(line.text, Modifier.padding(horizontal = 14.dp, vertical = 9.dp), style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
private fun PlayerBar(s: ConversationState, vm: ArchiveViewModel) {
    if (s.length <= 0) return
    Surface(tonalElevation = 3.dp, shadowElevation = 6.dp) {
        Column(Modifier.navigationBarsPadding().padding(horizontal = 16.dp, vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilledIconButton(onClick = vm::togglePlay) {
                    Icon(if (s.playing) Icons.Filled.PauseBars else Icons.Filled.PlayArrow, if (s.playing) "pause" else "play")
                }
                Spacer(Modifier.width(8.dp))
                var scrub by remember { mutableStateOf<Float?>(null) }
                Slider(
                    value = scrub ?: (s.position / s.length).toFloat().coerceIn(0f, 1f),
                    onValueChange = { scrub = it },
                    onValueChangeFinished = { scrub?.let { vm.seekTo(it * s.length) }; scrub = null },
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Text("${Fmt.clock(s.position)} / ${Fmt.clock(s.length)}", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun WhoSheet(vm: ArchiveViewModel, s: ConversationState, key: String, onDismiss: () -> Unit, onPerson: (Long) -> Unit) {
    val v = s.voices[key] ?: return
    val conv = s.conversation ?: return
    val guess = s.guesses[key]
    var name by remember(key) { mutableStateOf("") }
    val done = { onDismiss() }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(v, 44)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(v.name, style = MaterialTheme.typography.titleLarge)
                    Text(if (v.named) "Named voice" else "Who is this?", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                OutlinedButton(onClick = { s.lines.firstOrNull { it.speaker == key }?.let(vm::playLine) }) {
                    Icon(Icons.Filled.PlayArrow, null); Text("Hear")
                }
            }
            if (v.named && v.personId != null) {
                TextButton(onClick = { onPerson(v.personId) }) { Text("Open ${v.name}") }
                HorizontalDivider()
                Text("Not ${v.name}? Pick who it is instead:", style = MaterialTheme.typography.bodyMedium)
            }
            if (!v.named && guess != null) {
                Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                    Row(Modifier.padding(14.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Sounds like ${guess.first.name}", style = MaterialTheme.typography.titleMedium)
                            Text("${(guess.second * 100).toInt()}% similar", style = MaterialTheme.typography.bodySmall)
                        }
                        Button(onClick = { vm.confirmGuess(conv.id, key, guess.first); done() }) { Text("Yes") }
                    }
                }
            }
            val others = vm.namedPeople().filter { it.id != v.personId }
            if (others.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (p in others.take(12)) SuggestionChip(onClick = { vm.confirmGuess(conv.id, key, p); done() }, label = { Text(p.name ?: "") })
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("New name") }, singleLine = true, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                Button(enabled = name.isNotBlank(), onClick = { vm.nameVoice(conv.id, key, name.trim()); done() }) { Text("Save") }
            }
            if (!v.media) TextButton(onClick = { vm.markMedia(conv.id, key); done() }) { Text("It's a TV, video or radio") }
        }
    }
}
