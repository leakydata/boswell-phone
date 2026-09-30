package net.boswell.phone.ui

import android.app.Application
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.boswell.phone.todo.Todo
import net.boswell.phone.todo.TodoReminders
import net.boswell.phone.todo.TodoStore

class TodoViewModel(app: Application) : AndroidViewModel(app) {
    val items = MutableStateFlow<List<Todo>>(emptyList())
    private val store = TodoStore(app)

    fun refresh() = viewModelScope.launch { items.value = withContext(Dispatchers.IO) { store.all() } }

    fun toggle(t: Todo) = viewModelScope.launch {
        withContext(Dispatchers.IO) {
            store.setDone(t.id, !t.done)
            if (!t.done) TodoReminders.cancel(getApplication(), t.id)
            else t.due?.takeIf { it > System.currentTimeMillis() / 1000.0 }?.let { TodoReminders.schedule(getApplication(), t.id, it) }
        }
        refresh()
    }

    fun add(text: String, category: String?) = viewModelScope.launch {
        withContext(Dispatchers.IO) { store.add(text, category, null, "typed") }
        refresh()
    }

    fun save(t: Todo, text: String, category: String) = viewModelScope.launch {
        withContext(Dispatchers.IO) { store.update(t.id, text, category, t.due) }
        refresh()
    }

    fun delete(t: Todo) = viewModelScope.launch {
        withContext(Dispatchers.IO) { store.delete(t.id); TodoReminders.cancel(getApplication(), t.id) }
        refresh()
    }

    override fun onCleared() = store.close()
}

@Composable
fun TodoScreen(pad: PaddingValues) {
    val vm: TodoViewModel = viewModel()
    val items by vm.items.collectAsStateWithLifecycle()
    var filter by remember { mutableStateOf<String?>(null) }
    var showDone by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Todo?>(null) }
    var text by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { vm.refresh() }

    val categories = items.filter { !it.done }.groupBy { it.category }.entries.sortedByDescending { it.value.size }.map { it.key }
    val open = items.filter { !it.done && (filter == null || it.category == filter) }
    val done = items.filter { it.done && (filter == null || it.category == filter) }
    val now = System.currentTimeMillis() / 1000.0

    Column(Modifier.padding(top = pad.calculateTopPadding(), bottom = pad.calculateBottomPadding()).imePadding()) {
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            item {
                Text("To-do", style = MaterialTheme.typography.headlineLarge)
                Text("Double tap the Omi and say it, ask the assistant, or type below.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (categories.size > 1) item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                    item { FilterChip(selected = filter == null, onClick = { filter = null }, label = { Text("All ${items.count { !it.done }}") }) }
                    items(categories) { c ->
                        FilterChip(selected = filter == c, onClick = { filter = if (filter == c) null else c },
                            label = { Text("$c ${items.count { !it.done && it.category == c }}") })
                    }
                }
            }
            if (open.isEmpty()) item { Text("Nothing to do.", Modifier.padding(vertical = 16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            val grouped = open.groupBy { it.category }
            for ((cat, list) in grouped) {
                if (filter == null && grouped.size > 1) item(key = "h$cat") {
                    Text(cat, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 10.dp))
                }
                items(list, key = { it.id }) { t -> TodoRow(t, now, onToggle = { vm.toggle(t) }, onEdit = { editing = t }) }
            }
            if (done.isNotEmpty()) item {
                TextButton(onClick = { showDone = !showDone }) { Text(if (showDone) "Hide done (${done.size})" else "Done (${done.size})") }
            }
            if (showDone) items(done, key = { "d${it.id}" }) { t -> TodoRow(t, now, onToggle = { vm.toggle(t) }, onEdit = { editing = t }) }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(value = text, onValueChange = { text = it }, modifier = Modifier.weight(1f), singleLine = true,
                placeholder = { Text(if (filter != null) "Add to $filter" else "Add a to-do") })
            Spacer(Modifier.width(8.dp))
            FilledIconButton(onClick = { vm.add(text, filter); text = "" }, enabled = text.isNotBlank()) { Icon(Icons.Filled.Add, "add") }
        }
    }

    editing?.let { t ->
        var et by remember(t.id) { mutableStateOf(t.text) }
        var ec by remember(t.id) { mutableStateOf(t.category) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("Edit") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = et, onValueChange = { et = it }, label = { Text("To-do") })
                    OutlinedTextField(value = ec, onValueChange = { ec = it }, singleLine = true, label = { Text("Category") })
                }
            },
            confirmButton = { TextButton(onClick = { vm.save(t, et, ec); editing = null }) { Text("Save") } },
            dismissButton = {
                Row {
                    TextButton(onClick = { vm.delete(t); editing = null }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                    TextButton(onClick = { editing = null }) { Text("Cancel") }
                }
            },
        )
    }
}

@Composable
private fun TodoRow(t: Todo, now: Double, onToggle: () -> Unit, onEdit: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onEdit), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = t.done, onCheckedChange = { onToggle() })
        Column(Modifier.weight(1f)) {
            Text(t.text, style = MaterialTheme.typography.bodyLarge,
                textDecoration = if (t.done) TextDecoration.LineThrough else null,
                color = if (t.done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
            val meta = listOfNotNull(
                t.due?.let { d -> (if (d < now && !t.done) "overdue · " else "") + "${Fmt.shortDay(java.time.Instant.ofEpochSecond(d.toLong()).atZone(java.time.ZoneId.systemDefault()).toLocalDate())} ${Fmt.time(d)}" },
                if (t.source == "voice") "from the Omi" else null,
            )
            if (meta.isNotEmpty()) Text(meta.joinToString(" · "), style = MaterialTheme.typography.labelMedium,
                color = if (t.due != null && t.due < now && !t.done) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
