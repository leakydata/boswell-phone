package net.boswell.phone.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.verticalScroll
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
import kotlinx.coroutines.launch
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

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun ConversationScreen(vm: ArchiveViewModel, id: Long, focusLine: Long?, onBack: () -> Unit, onPerson: (Long) -> Unit, playFocus: Boolean = false) {
    // "Redo": the whole conversation again with the cloud engine (Parakeet), speech only.
    var redo by remember { mutableStateOf(false) }
    if (redo) {
        val ctx0 = androidx.compose.ui.platform.LocalContext.current
        val conv = vm.conversationOrNull(id)
        val hasKey = net.boswell.phone.assistant.Secrets.get(ctx0, net.boswell.phone.assistant.Secrets.OPENROUTER) != null
        val clips = remember(id) { vm.redoableClips(id) }
        val cost = (conv?.speechSeconds ?: 0.0) / 3600 * 0.09
        androidx.compose.material3.AlertDialog(onDismissRequest = { redo = false },
            title = { Text("Redo in the cloud?") },
            text = { Text(when {
                !hasKey -> "Cloud transcription needs your OpenRouter key (Device → Assistant)."
                clips.isEmpty() -> "None of this conversation's recordings still have their sound, so they can't be transcribed again."
                else -> "Transcribe ${clips.size} recording${if (clips.size == 1) "" else "s"} again with Parakeet in the cloud. Only the speech is sent. " +
                    "About ${if (cost < 0.01) "less than a cent" else "$%.2f".format(cost)}. Who said what is worked out on the phone again too; " +
                    "lines you corrected and voices you named are kept."
            }) },
            confirmButton = { if (hasKey && clips.isNotEmpty()) TextButton(onClick = { vm.retranscribeCloud(clips); redo = false }) { Text("Redo") } },
            dismissButton = { TextButton(onClick = { redo = false }) { Text(if (hasKey && clips.isNotEmpty()) "Cancel" else "OK") } })
    }
    val s by vm.conv.collectAsStateWithLifecycle()
    var who by remember { mutableStateOf<String?>(null) }
    var orphan by remember { mutableStateOf<LineRow?>(null) }
    var lineMenu by remember { mutableStateOf<List<LineRow>?>(null) }
    var confirmDelete by remember { mutableStateOf<List<String>?>(null) }
    var factCheck by remember { mutableStateOf<net.boswell.phone.assistant.FactCheckRow?>(null) }
    // "Split this voice": the voice, and the parts already checked (from a line's "This part isn't…").
    var split by remember { mutableStateOf<Pair<String, Set<Pair<String, String>>>?>(null) }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(id) { vm.openConversation(id) }
    DisposableEffect(Unit) { onDispose { vm.closeConversation() } }
    val list = rememberLazyListState()
    var playedFocus by remember { mutableStateOf(false) }
    LaunchedEffect(focusLine, s.lines.size) {
        // Scroll to the searched-for line's turn (lines merge into turns, so find by position in time).
        val target = s.lines.firstOrNull { it.id == focusLine } ?: return@LaunchedEffect
        val i = s.lines.filter { it.t0 < target.t0 }.map { it.speaker }.zipWithNext().count { (a, b) -> a != b }
        list.scrollToItem((i + 1).coerceAtMost(s.lines.size))
        // From a "hear it" link: play that moment once the player is ready.
        if (playFocus && !playedFocus) { playedFocus = true; kotlinx.coroutines.delay(400); vm.playLine(target) }
    }

    val c = s.conversation
    // Consecutive lines from one voice with no real pause between them
    // are one thing said: the 30 s clip boundary and the transcriber's
    // own line breaks should not split a sentence into three bubbles.
    val turns = s.lines.fold(mutableListOf<MutableList<LineRow>>()) { acc, l ->
        val last = acc.lastOrNull()?.last()
        if (last != null && last.speaker == l.speaker && l.t0 - last.t1 < 2.5) acc.last() += l else acc += mutableListOf(l)
        acc
    }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    /** Bring [line]'s bubble into view: past the title, the transcribed-by line and the voices row. */
    fun showLine(line: LineRow) {
        val turn = turns.indexOfFirst { t -> t.any { it.id == line.id } }.takeIf { it >= 0 } ?: return
        val before = (if (c?.title != null) 1 else 0) + (if (s.lines.isNotEmpty()) 1 else 0) + 1
        scope.launch { list.animateScrollToItem(before + turn) }
    }
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
                actions = {
                    if (c != null) {
                        TextButton(onClick = { redo = true }) { Text("Redo") }
                        TextButton(onClick = { vm.shareConversations(listOf(c.id)) { ctx.startActivity(it) } }) { Text("Share") }
                        TextButton(onClick = { confirmDelete = s.lines.map { it.clip }.distinct().ifEmpty { listOf() } + listOf("#conversation") }) {
                            Text("Delete", color = MaterialTheme.colorScheme.error) }
                    }
                },
            )
        },
        bottomBar = { PlayerBar(s, vm) },
    ) { pad ->
        LazyColumn(state = list, contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = pad.calculateTopPadding() + 4.dp, bottom = pad.calculateBottomPadding() + 16.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)) {
            // Whose words these are: the phone's, the cloud's (Parakeet), the home server's, or a mix (after a Redo, or home being away).
            s.conversation?.title?.let { title ->
                item {
                    Column(Modifier.padding(bottom = 8.dp)) {
                        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                        s.conversation?.summary?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
            }
            val clipsAll = s.lines.map { it.clip }.distinct()
            if (clipsAll.isNotEmpty()) item {
                Text(transcribedBy(s.lines), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 6.dp))
            }
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
            itemsIndexed(turns, key = { _, t -> t.first().id }) { i, turn ->
                val line = turn.first()
                val prev = turns.getOrNull(i - 1)?.last()
                val newSpeaker = prev?.speaker != line.speaker || line.t0 - (prev?.t1 ?: 0.0) > 30
                val merged = if (turn.size == 1) line else line.copy(text = turn.joinToString(" ") { it.text }, t1 = turn.last().t1)
                // Checked: a ✓ or ✗ (unclear and misheard are only in Ask).
                val check = s.checks.lastOrNull { f -> f.v in BADGED && turn.any { f.on(it.clip, it.t0, it.t1) } }
                Bubble(merged, s.voices[line.speaker], newSpeaker, turn.any { it.id == s.playingLine },
                    onTap = { vm.playLine(line) }, onWho = { if (line.speaker != null) who = line.speaker else orphan = line }, onLong = { lineMenu = turn.toList() }, edited = turn.any { it.original != null },
                    check = check, onCheck = { factCheck = check })
            }
            if (s.lines.isEmpty() && c != null) item { Text("No words were transcribed in this conversation.", Modifier.padding(16.dp)) }
        }
    }

    lineMenu?.let { ls ->
        LineSheet(vm, ls, ls.first().speaker?.let { s.voices[it] }, onDismiss = { lineMenu = null },
            onDelete = { confirmDelete = ls.map { it.clip }.distinct(); lineMenu = null },
            onSplit = { key -> split = key to ls.mapNotNull { l -> l.label?.let { l.clip to it } }.toSet() })
    }
    confirmDelete?.let { clips ->
        val whole = "#conversation" in clips
        val targets = clips.filter { it != "#conversation" }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text(if (whole) "Delete this conversation?" else "Delete this 30-second clip?") },
            text = { Text("The audio and transcript are deleted from this phone for good." + if (!whole) " Other clips in the conversation stay." else "") },
            confirmButton = { TextButton(onClick = {
                if (whole && c != null) { vm.deleteConversations(listOf(c.id)); onBack() } else vm.deleteClips(targets)
                confirmDelete = null
            }) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Keep") } },
        )
    }

    who?.let { key ->
        WhoSheet(vm, s, key, onDismiss = { who = null }, onPerson = { pid -> who = null; onPerson(pid) },
            onSplit = { who = null; split = key to emptySet() }, onShow = { line -> who = null; showLine(line) })
    }
    split?.let { (key, checked) -> SplitSheet(vm, s, key, checked, onDismiss = { split = null }) }
    orphan?.let { line -> OrphanSheet(vm, s, line, onDismiss = { orphan = null }) }
    factCheck?.let { f ->
        androidx.compose.material3.AlertDialog(onDismissRequest = { factCheck = null },
            title = { Text("Fact check: ${f.v?.label?.lowercase() ?: f.verdict.lowercase()}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(f.claim, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(f.explanation)
                }
            },
            confirmButton = { TextButton(onClick = { factCheck = null }) { Text("OK") } },
            dismissButton = { f.source?.let { url ->
                TextButton(onClick = { runCatching { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))) } }) {
                    Text("Open ${net.boswell.phone.assistant.Claims.site(url)}")
                }
            } })
    }
}

private val BADGED = setOf(net.boswell.phone.assistant.Verdict.TRUE, net.boswell.phone.assistant.Verdict.FALSE, net.boswell.phone.assistant.Verdict.MISLEADING)

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun Bubble(line: LineRow, v: Voice?, header: Boolean, playing: Boolean, onTap: () -> Unit, onWho: () -> Unit, onLong: () -> Unit = {}, edited: Boolean = false,
                   check: net.boswell.phone.assistant.FactCheckRow? = null, onCheck: () -> Unit = {}) {
    val color = Voices.color(line.speaker)
    Column(Modifier.fillMaxWidth().padding(top = if (header) 10.dp else 0.dp)) {
        if (header) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable(onClick = onWho).padding(bottom = 4.dp)) {
            if (v != null) Avatar(v, 22) else Spacer(Modifier.size(22.dp))
            Spacer(Modifier.width(6.dp))
            Text(v?.name ?: "Unattributed", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold,
                color = if (v?.named == true || v?.boswell == true) color else MaterialTheme.colorScheme.onSurfaceVariant)
            Text("  ${Fmt.time(line.t0)}" + when { line.home -> " · home"; line.cloud -> " · cloud"; else -> "" }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Surface(
            modifier = Modifier.padding(start = 28.dp, end = 24.dp).clip(RoundedCornerShape(16.dp))
                .combinedClickable(onClick = onTap, onLongClick = onLong),
            shape = RoundedCornerShape(topStart = if (header) 4.dp else 16.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 16.dp),
            color = if (v?.media == true) MaterialTheme.colorScheme.surfaceVariant else color.copy(alpha = if (playing) 0.35f else 0.14f),
            border = if (playing) BorderStroke(1.5.dp, color) else null,
        ) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 9.dp)) {
                Text(line.text, style = MaterialTheme.typography.bodyLarge)
                if (edited) Text("corrected", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (check != null) {
                    val ok = check.v == net.boswell.phone.assistant.Verdict.TRUE
                    Text(if (ok) "✓ checks out" else "✗ ${check.v?.label?.lowercase() ?: "false"}", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold,
                        color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClick = onCheck).padding(vertical = 2.dp))
                }
            }
        }
    }
}

/**
 * Long-press on what was said: fix it first. Each line of the bubble is an
 * edit box, already focused, with a play button to hear exactly that bit.
 * The transcriber's version is kept and can be restored.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LineSheet(vm: ArchiveViewModel, lines: List<LineRow>, voice: Voice?, onDismiss: () -> Unit, onDelete: () -> Unit, onSplit: (String) -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    fun toast(t: String) = android.widget.Toast.makeText(ctx, t, android.widget.Toast.LENGTH_SHORT).show()
    val texts = remember(lines) { androidx.compose.runtime.mutableStateListOf(*lines.map { it.text }.toTypedArray()) }
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val changed = lines.indices.filter { texts[it].trim() != lines[it].text }
    val all = lines.joinToString(" ") { it.text }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp).imePadding(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Fix what was said", style = MaterialTheme.typography.titleMedium)
            lines.forEachIndexed { i, l ->
                Row(verticalAlignment = Alignment.Top) {
                    IconButton(onClick = { vm.playLine(l) }) { Icon(Icons.Filled.PlayArrow, "hear this part") }
                    Column(Modifier.weight(1f)) {
                        OutlinedTextField(value = texts[i], onValueChange = { texts[i] = it },
                            modifier = Modifier.fillMaxWidth().let { if (i == 0) it.focusRequester(focus) else it })
                        l.original?.let { o ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Heard: \"$o\"", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                                TextButton(onClick = { texts[i] = o }) { Text("Restore") }
                            }
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                androidx.compose.material3.Button(enabled = changed.isNotEmpty(), onClick = {
                    vm.editLines(changed.map { lines[it] to texts[it] }); toast("Saved"); onDismiss()
                }) { Text("Save") }
            }
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            @Composable fun action(label: String, danger: Boolean = false, go: () -> Unit) =
                TextButton(onClick = { go(); onDismiss() }, modifier = Modifier.fillMaxWidth()) {
                    Text(label, Modifier.fillMaxWidth(), color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                }
            action("Copy text") { clipboard.setText(androidx.compose.ui.text.AnnotatedString(all)); toast("Copied") }
            action("Make it a to-do") { vm.lineToTodo(lines.first().copy(text = all)); toast("Added to your to-do list") }
            action("Ask the assistant about it") { vm.askAbout(lines.first().copy(text = all)); toast("The answer will appear in Ask") }
            // Only this part of a voice is someone else (a TV under your name, say): split it off.
            if (voice != null && !voice.boswell && lines.any { it.label != null }) action("This part isn't ${voice.name}…") { onSplit(voice.key) }
            action("Transcribe again (keeps your fixes)") { vm.retranscribe(lines.map { it.clip }.distinct()); toast("Re-transcribing") }
            action("Delete this clip", danger = true) { onDelete() }
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
            androidx.compose.material3.FilterChip(selected = s.skipPauses, onClick = { vm.setSkipPauses(!s.skipPauses) },
                label = { Text("Skip pauses") })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun WhoSheet(vm: ArchiveViewModel, s: ConversationState, key: String, onDismiss: () -> Unit, onPerson: (Long) -> Unit, onSplit: () -> Unit,
                     onShow: (LineRow) -> Unit = {}) {
    val v = s.voices[key] ?: return
    val conv = s.conversation ?: return
    if (v.boswell) { BoswellSheet(vm, s, key, conv.id, onDismiss); return }
    val guess = s.guesses[key]
    var name by remember(key) { mutableStateOf("") }
    val done = { onDismiss() }
    // Who it is, heard at once: the voice's longest line plays as the sheet opens, and the arrows
    // step through its other lines, so a voice can be labeled without scrolling to find it.
    val said = remember(key, s.lines) { s.lines.filter { it.speaker == key } }
    var at by remember(key) { mutableStateOf(said.indices.maxByOrNull { said[it].t1 - said[it].t0 } ?: 0) }
    fun hear(i: Int) { said.getOrNull(i)?.let { at = i; vm.playLine(it) } }
    LaunchedEffect(key) { hear(at) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(v, 44)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(v.name, style = MaterialTheme.typography.titleLarge)
                    Text(if (v.named) "Named voice" else "Who is this?", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                OutlinedButton(onClick = { hear(at) }) {
                    Icon(Icons.Filled.PlayArrow, null); Text("Hear")
                }
            }
            said.getOrNull(at)?.let { line ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { hear(at - 1) }, enabled = at > 0) { Text("◀") }
                    Column(Modifier.weight(1f)) {
                        Text("“${line.text.take(120)}”", style = MaterialTheme.typography.bodyMedium, maxLines = 2)
                        Text("${Fmt.time(line.t0)} · ${at + 1} of ${said.size}", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { hear(at + 1) }, enabled = at < said.size - 1) { Text("▶") }
                }
                TextButton(onClick = { onShow(line) }) { Text("Show in conversation") }
            }
            // First, where a thumb finds it: while labeling, TV is the most common answer.
            if (!v.media) OutlinedButton(onClick = { vm.markMedia(conv.id, key); done() }, modifier = Modifier.fillMaxWidth()) {
                Text("It's a TV, video or radio")
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
            // Two sources heard as one voice (you and a TV): only worth offering with more than one part.
            if (s.lines.filter { it.speaker == key && it.label != null }.distinctBy { it.clip to it.label }.size >= 2)
                TextButton(onClick = onSplit) { Text("Split this voice…") }
        }
    }
}

/**
 * "Split this voice": one conversation voice is often two sources heard as one
 * (you and a TV). Each part -- one recording's voice -- is listed with when it
 * speaks, how long and what it said, to hear and check; the checked ones move
 * to someone else, a TV or a new unnamed voice, and the rest stay.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun SplitSheet(vm: ArchiveViewModel, s: ConversationState, key: String, checked: Set<Pair<String, String>>, onDismiss: () -> Unit) {
    val v = s.voices[key] ?: return
    val conv = s.conversation ?: return
    val slots by androidx.compose.runtime.produceState<List<ArchiveViewModel.Slot>?>(null, conv.id, key) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { vm.slotsIn(conv.id, key) }
    }
    var picked by remember(key) { mutableStateOf(checked) }
    var choosing by remember(key) { mutableStateOf(false) }
    var name by remember(key) { mutableStateOf("") }
    val playing by vm.playingClip.collectAsStateWithLifecycle()
    DisposableEffect(Unit) { onDispose { vm.stopVoice() } }
    fun move(to: ArchiveViewModel.MoveTo) { vm.splitVoice(conv.id, key, picked, to); onDismiss() }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp).imePadding()
            .verticalScroll(androidx.compose.foundation.rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Split ${v.name}", style = MaterialTheme.typography.titleLarge)
            Text("Check the parts that are someone or something else. The rest stay ${v.name}.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val all = slots
            if (all == null) Text("Loading…", color = MaterialTheme.colorScheme.onSurfaceVariant)
            else for (slot in all) {
                val id = slot.clip to slot.label
                val on = id in picked
                Surface(onClick = { picked = if (on) picked - id else picked + id }, shape = RoundedCornerShape(14.dp),
                    color = if (on) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.Checkbox(checked = on, onCheckedChange = { picked = if (it) picked + id else picked - id })
                        Column(Modifier.weight(1f)) {
                            Text("${Fmt.seconds(slot.at)} · ${"%.0f".format(slot.seconds)} s of speech", style = MaterialTheme.typography.labelLarge)
                            Text(if (slot.said.isBlank()) "(no words)" else if (slot.said.length > 80) slot.said.take(80).trimEnd() + "…" else slot.said,
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                        }
                        val here = playing == "${slot.clip}|${slot.label}"
                        IconButton(onClick = { vm.toggleSlot(slot.clip, slot.label) }) {
                            Icon(if (here) Icons.Filled.PauseBars else Icons.Filled.PlayArrow, if (here) "stop" else "hear this part")
                        }
                    }
                }
            }
            if (picked.isNotEmpty() && !choosing) Button(onClick = { choosing = true }, modifier = Modifier.fillMaxWidth()) {
                Text("Move ${picked.size} part${if (picked.size == 1) "" else "s"} to…")
            }
            if (picked.isNotEmpty() && choosing) {
                HorizontalDivider()
                Text("Move ${picked.size} part${if (picked.size == 1) "" else "s"} to:", style = MaterialTheme.typography.titleMedium)
                val others = vm.namedPeople().filter { it.id != v.personId }
                if (others.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (p in others.take(12)) SuggestionChip(onClick = { move(ArchiveViewModel.MoveTo.Someone(p.id)) }, label = { Text(p.name ?: "") })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("New name") }, singleLine = true, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    Button(enabled = name.isNotBlank() && name.trim() != v.name, onClick = { move(ArchiveViewModel.MoveTo.Named(name.trim())) }) { Text("Move") }
                }
                TextButton(onClick = { move(ArchiveViewModel.MoveTo.Media) }) { Text("TV, video or radio") }
                TextButton(onClick = { move(ArchiveViewModel.MoveTo.Unnamed) }) { Text("A new unnamed voice") }
            }
        }
    }
}

/**
 * Boswell's own spoken answers, which the Omi heard: nothing to name. If it
 * wasn't Boswell, "Not Boswell" makes those lines ordinary voices again.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BoswellSheet(vm: ArchiveViewModel, s: ConversationState, key: String, conversation: Long, onDismiss: () -> Unit) {
    val v = s.voices[key] ?: return
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(v, 44)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(v.name, style = MaterialTheme.typography.titleLarge)
                    Text("Spoken answers, heard by the Omi", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                OutlinedButton(onClick = { s.lines.firstOrNull { it.speaker == key }?.let(vm::playLine) }) {
                    Icon(Icons.Filled.PlayArrow, null); Text("Hear")
                }
            }
            Text("These lines are what Boswell said aloud. They stay in the transcript but don't count as anyone talking.",
                style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = { vm.notBoswell(conversation); onDismiss() }) { Text("Not Boswell") }
        }
    }
}

/**
 * "Who said this?" for a line no speaker was found for (a word or two at a
 * recording's edge): the voices heard in the same recording to choose from.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OrphanSheet(vm: ArchiveViewModel, s: ConversationState, line: LineRow, onDismiss: () -> Unit) {
    // One entry per voice: Boswell's can be heard as more than one diarized voice.
    val options = remember(line.clip) { vm.speakersInClip(line.clip).distinctBy { it.second ?: it.first } }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Who said this?", style = MaterialTheme.typography.titleLarge)
            Text("\u201c${line.text}\u201d", style = MaterialTheme.typography.bodyLarge)
            OutlinedButton(onClick = { vm.playLine(line) }) { Icon(Icons.Filled.PlayArrow, null); Text("Hear it") }
            if (options.isEmpty()) Text("No voice in this recording was clear enough to tell who's speaking, so there's no one to file it under.",
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            else Text("Voices heard in the same recording:", color = MaterialTheme.colorScheme.onSurfaceVariant)
            for ((label, key) in options) {
                val v = key?.let { s.voices[it] }
                Surface(onClick = { vm.assignLine(line, label); onDismiss() }, shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (v != null) { Avatar(v, 36); Spacer(Modifier.width(12.dp)) }
                        Text(v?.name ?: "Another voice", style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }
    }
}

/** Where a conversation's recordings were transcribed: one place, or how many in each (most first). */
internal fun transcribedBy(lines: List<net.boswell.phone.archive.LineRow>): String {
    val where = lines.distinctBy { it.clip }.groupingBy { if (it.home) "at home" else if (it.cloud) "in the cloud" else "on the phone" }.eachCount()
    return if (where.size == 1) when (where.keys.single()) {
        "at home" -> "Transcribed at home (your computer)"
        "in the cloud" -> "Transcribed in the cloud (Parakeet)"
        else -> "Transcribed on the phone"
    } else "Transcribed " + where.entries.sortedByDescending { it.value }.joinToString(", ") { "${it.value} ${it.key}" }
}
