package net.boswell.phone.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import net.boswell.phone.assistant.AssistantPrefs
import net.boswell.phone.assistant.Trigger
import net.boswell.phone.assistant.Triggers

private fun Trigger.Action.label() = when (this) {
    Trigger.Action.TODO -> "Add a to-do"
    Trigger.Action.CALENDAR -> "Add to calendar"
    Trigger.Action.ANSWER -> "Answer me"
    Trigger.Action.CUSTOM -> "Custom"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TriggersScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var list by remember { mutableStateOf(Triggers.all(ctx)) }
    var on by remember { mutableStateOf(Triggers.enabled(ctx)) }
    var editing by remember { mutableStateOf<Trigger?>(null) }
    fun save(l: List<Trigger>) { list = l; Triggers.save(ctx, l) }
    val ownerSet = AssistantPrefs.owner(ctx) != null

    Scaffold(topBar = {
        TopAppBar(
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "back") } },
            title = { Text("Voice triggers") },
            actions = {
                IconButton(onClick = { editing = Trigger((list.maxOfOrNull { it.id } ?: 0) + 1, emptyList(), Trigger.Action.TODO) }) {
                    Icon(Icons.Filled.Add, "new trigger")
                }
            },
        )
    }) { pad ->
        LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = pad.calculateTopPadding() + 8.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Listen for trigger phrases", style = MaterialTheme.typography.titleMedium)
                        Text("When you say one of these, what you said goes to the assistant with the action below. " +
                            "It acts a few seconds after you pause (live) or at the next sync, and does nothing if it wasn't really a request. " +
                            "Only speech recorded after you switch this on counts.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = on, onCheckedChange = { on = it; Triggers.setEnabled(ctx, it) })
                }
                if (!ownerSet) Text("Set \"This is me\" on your own page in People, or triggers limited to your voice won't fire.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 6.dp))
            }
            items(list, key = { it.id }) { t ->
                Card(Modifier.fillMaxWidth().clickable { editing = t }) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(t.phrases.joinToString(" · ") { "\"$it\"" }, style = MaterialTheme.typography.bodyLarge)
                            Text(t.action.label() + (t.category?.let { " → $it" } ?: "") + (if (t.ownerOnly) " · my voice" else " · anyone"),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = t.enabled, onCheckedChange = { en -> save(list.map { if (it.id == t.id) it.copy(enabled = en) else it }) })
                    }
                }
            }
            item {
                TextButton(onClick = { save(Triggers.DEFAULTS) }) { Text("Restore the starter triggers") }
            }
        }
    }

    editing?.let { t -> TriggerDialog(t, isNew = list.none { it.id == t.id },
        onDismiss = { editing = null },
        onDelete = { save(list.filter { it.id != t.id }); editing = null },
        onSave = { n -> save(if (list.any { it.id == n.id }) list.map { if (it.id == n.id) n else it } else list + n); editing = null }) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TriggerDialog(t: Trigger, isNew: Boolean, onDismiss: () -> Unit, onDelete: () -> Unit, onSave: (Trigger) -> Unit) {
    var phrases by remember { mutableStateOf(t.phrases.joinToString(", ")) }
    var action by remember { mutableStateOf(t.action) }
    var category by remember { mutableStateOf(t.category ?: "") }
    var instruction by remember { mutableStateOf(t.instruction ?: "") }
    var ownerOnly by remember { mutableStateOf(t.ownerOnly) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) "New trigger" else "Edit trigger") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(value = phrases, onValueChange = { phrases = it }, label = { Text("Phrases, separated by commas") })
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (a in Trigger.Action.entries) FilterChip(selected = action == a, onClick = { action = a }, label = { Text(a.label()) })
                }
                if (action == Trigger.Action.TODO) OutlinedTextField(value = category, onValueChange = { category = it }, singleLine = true,
                    label = { Text("Category (blank: let the assistant pick)") })
                if (action == Trigger.Action.CUSTOM) OutlinedTextField(value = instruction, onValueChange = { instruction = it },
                    label = { Text("What should the assistant do?") }, placeholder = { Text("e.g. translate it to Spanish and show me") })
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Only my voice", Modifier.weight(1f))
                    Switch(checked = ownerOnly, onCheckedChange = { ownerOnly = it })
                }
            }
        },
        confirmButton = {
            TextButton(enabled = phrases.isNotBlank(), onClick = {
                onSave(t.copy(phrases = phrases.split(",").map { it.trim() }.filter { it.isNotEmpty() }, action = action,
                    category = category.trim().ifEmpty { null }.takeIf { action == Trigger.Action.TODO },
                    instruction = instruction.trim().ifEmpty { null }.takeIf { action == Trigger.Action.CUSTOM }, ownerOnly = ownerOnly))
            }) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (!isNew) TextButton(onClick = onDelete) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}
