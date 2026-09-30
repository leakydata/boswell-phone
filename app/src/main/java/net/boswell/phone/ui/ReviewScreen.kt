package net.boswell.phone.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.boswell.phone.ui.theme.PauseBars

/**
 * "Is this you?": voices that are close to someone known but not close
 * enough to call. One answer about an unnamed voice covers every recording
 * of it; each yes teaches Boswell another way the person sounds, and every
 * answer is followed by another look at all past recordings.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewScreen(vm: ArchiveViewModel, onBack: () -> Unit) {
    val items by vm.review.collectAsStateWithLifecycle()
    val playing by vm.playingClip.collectAsStateWithLifecycle()
    val note by vm.recheckNote.collectAsStateWithLifecycle()
    val owner = net.boswell.phone.assistant.AssistantPrefs.owner(LocalContext.current)
    LaunchedEffect(Unit) { vm.loadReview() }

    Scaffold(topBar = {
        TopAppBar(title = { Text("Who's speaking?") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "back") } })
    }) { pad ->
        val list = items
        if (list == null) {
            Column(Modifier.fillMaxSize().padding(pad), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }
        LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = pad.calculateTopPadding() + 8.dp, bottom = pad.calculateBottomPadding() + 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text("These voices are close to someone Boswell knows, but not close enough to be sure. Each answer teaches it, and past recordings are checked again.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            note?.let { n -> item { Text(n, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge) } }
            if (list.isEmpty()) item {
                Text("Nothing to review. New possibilities show up here as Boswell hears more.", Modifier.padding(top = 24.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(list, key = { it.s.key }) { item ->
                val s = item.s
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(if (s.personId == owner) "Is this you?" else "Is this ${s.personName}?", style = MaterialTheme.typography.titleMedium)
                        Text((if (s.clusterId != null) "${s.recordings} recording${if (s.recordings == 1) "" else "s"}" else "One recording") +
                            " · ${Fmt.duration(s.seconds)} · " + (if (s.score >= net.boswell.phone.speakers.VoiceReview.LIKELY) "likely" else "possible"),
                            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (item.said.isNotBlank()) Text("“${item.said}”", maxLines = 3, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodyMedium)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { vm.toggleClip(s.clip) }) {
                                Icon(if (playing == s.clip) Icons.Filled.PauseBars else Icons.Filled.PlayArrow, "play")
                            }
                            Spacer(Modifier.weight(1f))
                            if (s.clusterId != null) androidx.compose.material3.TextButton(onClick = { vm.reviewIsMedia(item) }) { Text("It's TV") }
                            OutlinedButton(onClick = { vm.answerReview(item, yes = false) }) { Text("No") }
                            Spacer(Modifier.width(8.dp))
                            Button(onClick = { vm.answerReview(item, yes = true) }) { Text("Yes") }
                        }
                    }
                }
            }
        }
    }
}
