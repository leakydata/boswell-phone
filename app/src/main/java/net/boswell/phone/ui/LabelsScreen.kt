package net.boswell.phone.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import net.boswell.phone.speakers.LabelCheck
import net.boswell.phone.ui.theme.PauseBars

/**
 * "Check the labels": voiceprints filed under the wrong person, or not filed
 * under the right one, pull later matches the wrong way. Each card says why
 * it was picked, can be heard, and is fixed in one tap with the app's usual
 * actions; every fix is followed by another look at past recordings.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LabelsScreen(vm: ArchiveViewModel, onBack: () -> Unit) {
    val items by vm.labels.collectAsStateWithLifecycle()
    val people by vm.people.collectAsStateWithLifecycle()
    val playing by vm.playingClip.collectAsStateWithLifecycle()
    val note by vm.recheckNote.collectAsStateWithLifecycle()
    val owner = net.boswell.phone.assistant.AssistantPrefs.owner(LocalContext.current)
    var naming by remember { mutableStateOf<ArchiveViewModel.LabelItem?>(null) }
    LaunchedEffect(Unit) { vm.loadLabels() }
    val names = people.named.associate { it.id to (it.name ?: "") }
    fun who(id: Long?) = if (id == owner) "you" else names[id] ?: "someone"
    fun whose(id: Long) = if (id == owner) "your" else "${names[id] ?: "their"}'s"
    fun pct(x: Double) = "${(x * 100).toInt()}%"

    Scaffold(topBar = {
        TopAppBar(title = { Text("Check the labels") },
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
                Text("A recording filed under the wrong person makes Boswell expect them to sound like it, and later voices get matched the wrong way. These look off. Nothing here deletes a recording.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            note?.let { n -> item { Text(n, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge) } }
            if (list.isEmpty()) item {
                Text("Nothing looks off.", Modifier.padding(top = 24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(list, key = { it.item.key }) { li ->
                val i = li.item
                val (title, evidence) = when (i) {
                    is LabelCheck.Twice -> "One recording, two names" to
                        "This voice is filed as ${i.people.joinToString(" and ") { names[it] ?: "?" }}. It can only be one of them."
                    is LabelCheck.Overlap -> "${names[i.a]} and ${names[i.b]} keep matching each other" to
                        "${i.crossings} of their recordings sound more like the other one. They may be one person, or some recordings are labeled wrong."
                    is LabelCheck.Outlier -> if (i.like != null)
                        "This voice of ${if (i.sample.personId == owner) "yours" else names[i.sample.personId]} sounds more like ${who(i.like)}" to
                            "${pct(i.likeScore)} like ${who(i.like)}, " + (i.own?.let { "${pct(it)} like ${whose(i.sample.personId)} other recordings." } ?: "and nothing else to compare it with.")
                    else "Doesn't sound like ${whose(i.sample.personId)} other recordings" to
                        "At best ${pct(i.own ?: 0.0)} like any of them, where their recordings are usually much closer."
                    is LabelCheck.Unnamed -> "Sounds like ${who(i.personId)} (${pct(i.score)})" to
                        "Unnamed voice · ${i.recordings} recording${if (i.recordings == 1) "" else "s"} · ${Fmt.duration(i.seconds)}" +
                            (i.runnerUp?.let { " · next closest ${who(it)} ${pct(i.runnerUpScore ?: 0.0)}" } ?: "")
                }
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(title, style = MaterialTheme.typography.titleMedium)
                        Text(evidence, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (li.said.isNotBlank()) Text("“${li.said}”", maxLines = 3, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Hear(i.sample, if (i is LabelCheck.Overlap) names[i.a] else null, playing, vm)
                            if (i is LabelCheck.Overlap) Hear(i.sampleB, names[i.b], playing, vm)
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            when (i) {
                                is LabelCheck.Twice -> for (p in i.people)
                                    Button(onClick = { vm.labelOnly(li, p) }) { Text(if (p == owner) "It's me" else "It's ${names[p]}") }
                                is LabelCheck.Overlap -> {
                                    if (i.a != owner) OutlinedButton(onClick = { vm.labelMerge(li, from = i.a, into = i.b) }) { Text("Both are ${who(i.b)}") }
                                    if (i.b != owner) OutlinedButton(onClick = { vm.labelMerge(li, from = i.b, into = i.a) }) { Text("Both are ${who(i.a)}") }
                                    Button(onClick = { vm.labelIsRight(li) }) { Text("Different") }
                                }
                                is LabelCheck.Outlier -> {
                                    Button(onClick = { vm.labelIsRight(li) }) { Text("Right") }
                                    i.like?.let { to -> OutlinedButton(onClick = { vm.labelTakeOff(li, names[to]) }) { Text(if (to == owner) "It's me" else "It's ${names[to]}") } }
                                    OutlinedButton(onClick = { naming = li }) { Text("Someone else…") }
                                    OutlinedButton(onClick = { vm.labelTakeOff(li, null) }) { Text("Not them") }
                                }
                                is LabelCheck.Unnamed -> {
                                    Button(onClick = { vm.labelUnnamed(li, yes = true) }) { Text(if (i.personId == owner) "It's me" else "It's ${names[i.personId]}") }
                                    OutlinedButton(onClick = { vm.labelUnnamed(li, yes = false) }) { Text("Someone else") }
                                }
                            }
                            TextButton(onClick = { vm.skipLabel(li) }) { Text("Skip") }
                        }
                    }
                }
            }
        }
    }
    // "Someone else…": who it is instead; a name already in use joins them.
    naming?.let { li -> NameDialog(null, onDismiss = { naming = null }) { vm.labelTakeOff(li, it); naming = null } }
}

/**
 * Play one voiceprint's voice, [who] saying whose when there are two; the
 * whole recording when which voice it was is no longer known.
 */
@Composable
private fun Hear(p: LabelCheck.Print, who: String?, playing: String?, vm: ArchiveViewModel) {
    val clip = p.clip ?: return
    val label = p.speaker
    val key = if (label != null) "$clip|$label" else clip
    TextButton(onClick = { if (label != null) vm.toggleVoice(clip, label) else vm.toggleClip(clip) }) {
        Icon(if (playing == key) Icons.Filled.PauseBars else Icons.Filled.PlayArrow, "play this voice")
        Text(if (who != null) "Hear $who" else "Hear")
    }
}
