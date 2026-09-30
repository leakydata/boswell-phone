package net.boswell.phone.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.boswell.phone.ui.theme.PauseBars
import java.time.LocalDate

/**
 * Every recording of a day, including the ones with no speech that never
 * become conversations. Long-press to select; then delete, share or
 * transcribe again.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun RecordingsScreen(vm: ArchiveViewModel, day: LocalDate, onBack: () -> Unit, onOpen: (Long) -> Unit) {
    val ctx = LocalContext.current
    val recs by vm.recordings.collectAsStateWithLifecycle()
    val playing by vm.playingClip.collectAsStateWithLifecycle()
    var selected by remember { mutableStateOf(setOf<String>()) }
    var confirm by remember { mutableStateOf(false) }
    LaunchedEffect(day) { vm.loadRecordings(day) }
    val selecting = selected.isNotEmpty()
    // "No speech" only means something once a clip has been transcribed; one
    // still waiting has no transcript either and must never be mistaken for silence.
    val transcribed = remember(recs) {
        val tdir = net.boswell.phone.process.ProcessingWorker.transcriptsDir(ctx)
        recs.map { it.clip.name }.filter { java.io.File(tdir, it.removeSuffix(".wav") + ".json").exists() }.toSet()
    }

    Scaffold(topBar = {
        TopAppBar(
            navigationIcon = {
                if (selecting) IconButton(onClick = { selected = emptySet() }) { Icon(Icons.Filled.Close, "cancel selection") }
                else IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "back") }
            },
            title = { Text(if (selecting) "${selected.size} selected" else "Recordings · ${Fmt.shortDay(day)}") },
            actions = {
                if (selecting) {
                    TextButton(onClick = { vm.shareClips(selected.toList()) { ctx.startActivity(it) } }) { Text("Share") }
                    TextButton(onClick = { vm.retranscribe(selected.toList()); selected = emptySet() }) { Text("Redo") }
                    TextButton(onClick = { confirm = true }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                } else if (recs.isNotEmpty()) {
                    TextButton(onClick = { selected = recs.filter { !it.clip.speech && it.clip.name in transcribed }.map { it.clip.name }.toSet() }) { Text("Select no-speech") }
                }
            },
        )
    }) { pad ->
        LazyColumn(contentPadding = PaddingValues(top = pad.calculateTopPadding(), bottom = 24.dp)) {
            if (recs.isEmpty()) item { Text("No recordings this day.", Modifier.padding(16.dp)) }
            items(recs, key = { it.clip.name }) { r ->
                val c = r.clip
                val isSel = c.name in selected
                Row(
                    Modifier.fillMaxWidth().combinedClickable(
                        onClick = {
                            when {
                                selecting -> selected = if (isSel) selected - c.name else selected + c.name
                                c.conversation != null -> onOpen(c.conversation)
                                else -> vm.toggleClip(c.name)
                            }
                        },
                        onLongClick = { selected = selected + c.name },
                    ).padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (selecting) Checkbox(checked = isSel, onCheckedChange = { selected = if (isSel) selected - c.name else selected + c.name })
                    else IconButton(onClick = { vm.toggleClip(c.name) }, enabled = c.audio) {
                        Icon(if (playing == c.name) Icons.Filled.PauseBars else Icons.Filled.PlayArrow, "play")
                    }
                    Spacer(Modifier.width(4.dp))
                    Column(Modifier.weight(1f)) {
                        Text("${Fmt.time(c.started)} · ${Fmt.duration(c.ended - c.started)}" +
                            (if (!c.audio) " · audio removed" else "") + (if (r.sounds.isNotEmpty()) " · ${r.sounds.joinToString()}" else ""),
                            style = MaterialTheme.typography.labelLarge)
                        Text(r.text.ifEmpty { if (c.name !in transcribed) "waiting to be transcribed" else if (c.verdict == "empty") "background only" else "no speech" }, maxLines = 2, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (r.text.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                    }
                }
                HorizontalDivider()
            }
        }
    }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text("Delete ${selected.size} recording${if (selected.size == 1) "" else "s"}?") },
        text = { Text("Audio and transcripts are deleted from this phone for good. People you've named stay recognized.") },
        confirmButton = { TextButton(onClick = { vm.deleteClips(selected.toList()); selected = emptySet(); confirm = false }) {
            Text("Delete", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Keep") } },
    )
}
