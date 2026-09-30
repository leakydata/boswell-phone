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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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

    /** Move (or clear) a due time; the reminder moves with it. */
    fun setDue(t: Todo, due: Double?) = viewModelScope.launch {
        withContext(Dispatchers.IO) {
            store.update(t.id, t.text, t.category, due)
            TodoReminders.cancel(getApplication(), t.id)
            if (due != null && !t.done && due > System.currentTimeMillis() / 1000.0) TodoReminders.schedule(getApplication(), t.id, due)
        }
        refresh()
    }

    fun addOn(text: String, category: String?, due: Double) = viewModelScope.launch {
        withContext(Dispatchers.IO) {
            val id = store.add(text, category, due, "typed")
            if (due > System.currentTimeMillis() / 1000.0) TodoReminders.schedule(getApplication(), id, due)
        }
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
    var byDay by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    Column(Modifier.padding(top = pad.calculateTopPadding())) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            listOf("Lists", "By day").forEachIndexed { i, label ->
                SegmentedButton(selected = byDay == (i == 1), onClick = { byDay = i == 1 },
                    shape = SegmentedButtonDefaults.itemShape(i, 2)) { Text(label) }
            }
        }
        val inner = PaddingValues(bottom = pad.calculateBottomPadding())
        if (byDay) TodoByDay(vm, inner) else TodoLists(vm, inner)
    }
}

@Composable
private fun TodoLists(vm: TodoViewModel, pad: PaddingValues) {
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

    Column(Modifier.padding(bottom = pad.calculateBottomPadding()).imePadding()) {
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            item {
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

    editing?.let { t -> EditTodoDialog(vm, t) { editing = null } }
}

@Composable
fun EditTodoDialog(vm: TodoViewModel, t: Todo, onClose: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var et by remember(t.id) { mutableStateOf(t.text) }
    var ec by remember(t.id) { mutableStateOf(t.category) }
    var due by remember(t.id) { mutableStateOf(t.due) }
    val zone = java.time.ZoneId.systemDefault()
    fun pickDue() {
        val start = due?.let { java.time.Instant.ofEpochSecond(it.toLong()).atZone(zone) } ?: java.time.LocalDate.now().atTime(9, 0).atZone(zone)
        android.app.DatePickerDialog(ctx, { _, y, m, d ->
            android.app.TimePickerDialog(ctx, { _, h, min ->
                due = java.time.LocalDateTime.of(y, m + 1, d, h, min).atZone(zone).toEpochSecond().toDouble()
            }, start.hour, start.minute, android.text.format.DateFormat.is24HourFormat(ctx)).show()
        }, start.year, start.monthValue - 1, start.dayOfMonth).show()
    }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Edit") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = et, onValueChange = { et = it }, label = { Text("To-do") })
                OutlinedTextField(value = ec, onValueChange = { ec = it }, singleLine = true, label = { Text("Category") })
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(due?.let { "Due ${Fmt.shortDay(java.time.Instant.ofEpochSecond(it.toLong()).atZone(zone).toLocalDate())} ${Fmt.time(it)}" } ?: "No due date",
                        Modifier.weight(1f))
                    TextButton(onClick = ::pickDue) { Text(if (due == null) "Set" else "Change") }
                    if (due != null) TextButton(onClick = { due = null }) { Text("Clear") }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                vm.save(t, et, ec)
                if (due != t.due) vm.setDue(t.copy(text = et, category = ec), due)
                onClose()
            }) { Text("Save") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { vm.delete(t); onClose() }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = onClose) { Text("Cancel") }
            }
        },
    )
}

/**
 * The same to-dos by the day they are due: a week strip to pick a day (dots
 * where something is due), swipe to move a day, and that day's list --
 * with anything overdue shown on today.
 */
@Composable
private fun TodoByDay(vm: TodoViewModel, pad: PaddingValues) {
    val items by vm.items.collectAsStateWithLifecycle()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val zone = java.time.ZoneId.systemDefault()
    val today = java.time.LocalDate.now()
    var day by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(today.toEpochDay()) }
    val selected = java.time.LocalDate.ofEpochDay(day)
    var editing by remember { mutableStateOf<Todo?>(null) }
    var text by remember { mutableStateOf("") }
    val drag = remember { floatArrayOf(0f) }
    LaunchedEffect(Unit) { vm.refresh() }

    fun dayOf(t: Todo) = t.due?.let { java.time.Instant.ofEpochSecond(it.toLong()).atZone(zone).toLocalDate() }
    val dueDays = items.filter { !it.done }.mapNotNull(::dayOf).toSet()
    val onDay = items.filter { dayOf(it) == selected }.sortedWith(compareBy({ it.done }, { it.due }))
    val overdue = if (selected == today) items.filter { !it.done && dayOf(it)?.isBefore(today) == true }.sortedBy { it.due } else emptyList()
    val now = System.currentTimeMillis() / 1000.0

    Column(Modifier.padding(bottom = pad.calculateBottomPadding()).imePadding()) {
        // Week strip: Monday-first week containing the selected day.
        val weekStart = selected.minusDays((selected.dayOfWeek.value - 1).toLong())
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.IconButton(onClick = { day -= 7 }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "previous week") }
            for (i in 0 until 7) {
                val d = weekStart.plusDays(i.toLong())
                val sel = d == selected
                Column(
                    Modifier.weight(1f).clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                        .background(if (sel) MaterialTheme.colorScheme.primaryContainer else androidx.compose.ui.graphics.Color.Transparent)
                        .clickable { day = d.toEpochDay() }.padding(vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(d.dayOfWeek.getDisplayName(java.time.format.TextStyle.NARROW, java.util.Locale.getDefault()),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("${d.dayOfMonth}", style = MaterialTheme.typography.titleMedium,
                        color = if (d == today) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                    androidx.compose.foundation.layout.Box(Modifier.padding(top = 2.dp).size(5.dp).clip(androidx.compose.foundation.shape.CircleShape)
                        .background(if (d in dueDays) MaterialTheme.colorScheme.tertiary else androidx.compose.ui.graphics.Color.Transparent))
                }
            }
            androidx.compose.material3.IconButton(onClick = { day += 7 }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "next week") }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(Fmt.day(selected), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            if (selected != today) TextButton(onClick = { day = today.toEpochDay() }) { Text("Today") }
            TextButton(onClick = {
                android.app.DatePickerDialog(ctx, { _, y, m, d -> day = java.time.LocalDate.of(y, m + 1, d).toEpochDay() },
                    selected.year, selected.monthValue - 1, selected.dayOfMonth).show()
            }) { Text("Pick a date") }
        }
        LazyColumn(
            Modifier.weight(1f).pointerInput(day) {
                detectHorizontalDragGestures(onDragStart = { drag[0] = 0f },
                    onDragEnd = { if (drag[0] > 120) day -= 1 else if (drag[0] < -120) day += 1 }) { _, dx -> drag[0] += dx }
            },
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (overdue.isNotEmpty()) {
                item { Text("Overdue", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.error) }
                items(overdue, key = { "o${it.id}" }) { t -> TodoRow(t, now, onToggle = { vm.toggle(t) }, onEdit = { editing = t }) }
                item { Text("Due ${Fmt.day(selected).lowercase()}", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 10.dp)) }
            }
            if (onDay.isEmpty()) item {
                val next = items.filter { !it.done && dayOf(it)?.isAfter(selected) == true }.minByOrNull { it.due ?: Double.MAX_VALUE }
                Column(Modifier.padding(vertical = 16.dp)) {
                    Text("Nothing due.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    next?.let { n -> TextButton(onClick = { day = dayOf(n)!!.toEpochDay() }) {
                        Text("Next: ${Fmt.shortDay(dayOf(n)!!)} · ${n.text}") } }
                }
            }
            items(onDay, key = { it.id }) { t -> TodoRow(t, now, onToggle = { vm.toggle(t) }, onEdit = { editing = t }) }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(value = text, onValueChange = { text = it }, modifier = Modifier.weight(1f), singleLine = true,
                placeholder = { Text("Add for ${Fmt.shortDay(selected)}") })
            Spacer(Modifier.width(8.dp))
            FilledIconButton(onClick = {
                vm.addOn(text, null, selected.atTime(9, 0).atZone(zone).toEpochSecond().toDouble()); text = ""
            }, enabled = text.isNotBlank()) { Icon(Icons.Filled.Add, "add") }
        }
    }
    editing?.let { t -> EditTodoDialog(vm, t) { editing = null } }
}

@Composable
fun TodoRow(t: Todo, now: Double, onToggle: () -> Unit, onEdit: () -> Unit) {
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
