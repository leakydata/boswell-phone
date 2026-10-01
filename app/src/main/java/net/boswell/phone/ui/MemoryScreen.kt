package net.boswell.phone.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import net.boswell.phone.assistant.LifeStore

/**
 * Everything the assistant was asked to remember, or noticed: facts about
 * people (and about the user, under "Me"), grouped by person, each removable.
 * Kept on the phone (life.db), never anywhere else.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoryScreen(onBack: () -> Unit, onLogs: () -> Unit) {
    val ctx = LocalContext.current
    var version by remember { mutableStateOf(0) }
    val facts = remember(version) { LifeStore(ctx).use { it.facts() } }
    Scaffold(topBar = {
        TopAppBar(title = { Text("Remembered") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "back") } },
            actions = { TextButton(onClick = onLogs) { Text("Logs") } })
    }) { pad ->
        LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = pad.calculateTopPadding() + 8.dp, bottom = pad.calculateBottomPadding() + 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Text("Say \u201cremember that Sam's daughter is Ava\u201d or \u201cremember my locker is 214\u201d. It also notices facts in what's said, every few hours. Ask \u201cwhat do I know about Sam?\u201d to hear them.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (facts.isEmpty()) item { Text("Nothing remembered yet.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            for ((person, list) in facts.groupBy { if (it.person.equals("me", true)) "Me" else it.person }.toSortedMap(compareBy { if (it == "Me") "" else it.lowercase() })) {
                item { Text(person, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp)) }
                items(list, key = { it.id }) { f ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(f.fact)
                            Text(Fmt.time(f.at), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TextButton(onClick = { LifeStore(ctx).use { it.deleteFact(f.id) }; version++ }) { Text("Remove") }
                    }
                }
            }
        }
    }
}
