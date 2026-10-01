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
 * Quick logs kept by voice ("I took my pills", "spent 42 on gas", "parked on
 * level 3"): newest first, grouped by kind, with a total where there are
 * amounts. Ask the assistant to add one, or read them back.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var version by remember { mutableStateOf(0) }
    val entries = remember(version) { LifeStore(ctx).use { it.logs() } }
    Scaffold(topBar = {
        TopAppBar(title = { Text("Logs") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "back") } })
    }) { pad ->
        LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = pad.calculateTopPadding() + 8.dp, bottom = pad.calculateBottomPadding() + 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Text("Say it to the assistant: \u201cI took my pills\u201d, \u201cspent 42 dollars on gas\u201d, \u201cI parked on level 3\u201d. Ask \u201cdid I take my pills today?\u201d to read them back.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (entries.isEmpty()) item { Text("Nothing logged yet.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            for ((kind, list) in entries.groupBy { it.kind }) {
                item {
                    val total = list.mapNotNull { it.amount }.takeIf { it.isNotEmpty() }?.sum()
                    Text(kind.replaceFirstChar { it.uppercase() } + (total?.let { " · total %.2f".format(it) } ?: ""),
                        style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
                }
                items(list, key = { it.id }) { e ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(listOfNotNull(e.note, e.amount?.let { "%.2f".format(it) }).joinToString(" · ").ifEmpty { e.kind })
                            Text(Fmt.time(e.at), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TextButton(onClick = { LifeStore(ctx).use { it.deleteLog(e.id) }; version++ }) { Text("Remove") }
                    }
                }
            }
        }
    }
}
