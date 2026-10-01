package net.boswell.phone.ui

import android.app.Application
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.boswell.phone.assistant.Assistant
import net.boswell.phone.assistant.AssistantPrefs
import net.boswell.phone.assistant.AssistantStore
import net.boswell.phone.assistant.Exchange
import net.boswell.phone.capture.CaptureRepository

data class AskState(val exchanges: List<Exchange> = emptyList(), val thinking: Boolean = false, val ready: Boolean = false,
                    val spentToday: Double = 0.0, val budget: Double = 0.5, val freshTopic: Boolean = false)

class AskViewModel(app: Application) : AndroidViewModel(app) {
    val state = MutableStateFlow(AskState())

    init {
        refresh()
        // A button question finishing lands here too.
        viewModelScope.launch { CaptureRepository.state.distinctUntilChangedBy { it.asking }.collect { refresh() } }
    }

    fun refresh() = viewModelScope.launch {
        val (ex, spent) = withContext(Dispatchers.IO) {
            val s = AssistantStore(getApplication()); try { s.exchanges() to s.spentToday() } finally { s.close() }
        }
        state.value = state.value.copy(exchanges = ex, spentToday = spent, ready = Assistant(getApplication()).ready(),
            budget = AssistantPrefs.budget(getApplication()))
    }

    fun newTopic() {
        AssistantPrefs.newTopic(getApplication())
        state.value = state.value.copy(freshTopic = true)
    }

    fun ask(q: String) = viewModelScope.launch {
        state.value = state.value.copy(freshTopic = false)
        state.value = state.value.copy(thinking = true)
        withContext(Dispatchers.IO) { Assistant(getApplication()).ask(q, "typed") }
        state.value = state.value.copy(thinking = false)
        refresh()
    }
}

@Composable
fun AskScreen(pad: PaddingValues, onSetup: () -> Unit, onUsage: () -> Unit = {}, onMoment: (Long, Long) -> Unit = { _, _ -> },
              onLogs: () -> Unit = {}) {
    val vm: AskViewModel = viewModel()
    val s by vm.state.collectAsStateWithLifecycle()
    val cap by CaptureRepository.state.collectAsStateWithLifecycle()
    var q by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { vm.refresh() }

    Column(Modifier.padding(top = pad.calculateTopPadding(), bottom = pad.calculateBottomPadding()).imePadding()) {
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), reverseLayout = true) {
            if (cap.asking != null || s.thinking) item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.padding(end = 12.dp).width(20.dp), strokeWidth = 2.dp)
                    Text(if (cap.asking == "listening") "Listening to your question…" else "Thinking…")
                }
            }
            items(s.exchanges, key = { it.id }) { e -> ExchangeCard(e, onMoment) }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Ask", style = MaterialTheme.typography.headlineLarge)
                    Text(
                        if (!s.ready) "Add an OpenRouter key in Device → Assistant to start."
                        else "Tap the Omi's button quickly (a firm push doesn't count) and ask out loud, or type below. Answers use what was said around you. " +
                            "Spent today: $%.3f · watcher budget $%.2f".format(s.spentToday, s.budget),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (!s.ready) androidx.compose.material3.TextButton(onClick = onSetup) { Text("Set up the assistant") }
                    else {
                        Text("Follow-up questions within 15 minutes carry on the same conversation. Start a new topic to begin fresh.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row {
                            androidx.compose.material3.TextButton(onClick = { vm.newTopic() }) { Text(if (s.freshTopic) "New topic started ✓" else "New topic") }
                            androidx.compose.material3.TextButton(onClick = onLogs) { Text("Remembered") }
                            androidx.compose.material3.TextButton(onClick = onUsage) { Text("AI usage") }
                        }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(value = q, onValueChange = { q = it }, placeholder = { Text("What did we decide about…") },
                modifier = Modifier.weight(1f), maxLines = 4, enabled = s.ready)
            Spacer(Modifier.width(8.dp))
            FilledIconButton(onClick = { vm.ask(q.trim()); q = "" }, enabled = s.ready && q.isNotBlank() && !s.thinking) {
                Icon(Icons.AutoMirrored.Filled.Send, "ask")
            }
        }
    }
}

/** Things in an answer worth a button: numbers to call, links and emails to open, addresses to map. */
private val PHONE = Regex("""(?<!\d)(?:\+?1[\s.-]?)?\(?\d{3}\)?[\s.-]?\d{3}[\s.-]?\d{4}(?!\d)""")
private val URL = Regex("""\bhttps?://\S+|\b[\w-]+\.(?:com|org|net|io|gov|edu)(?:/\S*)?""", RegexOption.IGNORE_CASE)
private val EMAIL = Regex("""[\w.+-]+@[\w-]+\.[\w.]+""")
private val ADDRESS = Regex("""\b\d{1,6}\s+(?:[A-Z][a-z]+\s){1,3}(?:Street|St|Avenue|Ave|Road|Rd|Drive|Dr|Lane|Ln|Boulevard|Blvd|Court|Ct|Way|Place|Pl)\b""")

@Composable
private fun ExchangeCard(e: Exchange, onMoment: (Long, Long) -> Unit = { _, _ -> }) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val shown = remember(e.answer) { net.boswell.phone.assistant.Moments.strip(e.answer) }
    // Quoted moments, resolved off the main thread: "▶ 3:05 PM" plays that line.
    val moments by androidx.compose.runtime.produceState(emptyList<net.boswell.phone.assistant.Moments.Where>(), e.answer) {
        value = withContext(Dispatchers.IO) {
            net.boswell.phone.assistant.Moments.ids(e.answer).take(4).mapNotNull { net.boswell.phone.assistant.Moments.resolve(ctx, it) }
        }
    }
    val details = remember(shown) {
        buildList {
            EMAIL.findAll(shown).forEach { add("Email" to android.content.Intent(android.content.Intent.ACTION_SENDTO, android.net.Uri.parse("mailto:${it.value}"))) }
            PHONE.findAll(shown).forEach { add("Call ${it.value}" to android.content.Intent(android.content.Intent.ACTION_DIAL, android.net.Uri.parse("tel:${it.value.filter { c -> c.isDigit() || c == '+' }}"))) }
            URL.findAll(shown).filter { u -> EMAIL.findAll(shown).none { it.value.contains(u.value) } }.forEach {
                val u = if (it.value.startsWith("http")) it.value else "https://${it.value}"
                add("Open ${it.value.removePrefix("https://").removePrefix("http://").take(24)}" to android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(u)))
            }
            ADDRESS.findAll(shown).forEach { add("Map" to android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("geo:0,0?q=" + android.net.Uri.encode(it.value)))) }
        }.distinctBy { it.first }.take(4)
    }
    val watcher = e.source == "watcher"
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (!watcher && e.question != null) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Surface(shape = RoundedCornerShape(16.dp, 4.dp, 16.dp, 16.dp), color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.padding(start = 48.dp)) {
                Text(e.question, Modifier.padding(horizontal = 14.dp, vertical = 9.dp))
            }
        }
        Card(colors = CardDefaults.cardColors(containerColor = if (watcher) MaterialTheme.colorScheme.tertiaryContainer
            else if (e.error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainer),
            modifier = Modifier.padding(end = 32.dp)) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (watcher) Text("Hint · ${e.question ?: ""}", style = MaterialTheme.typography.labelLarge)
                Text(shown, style = MaterialTheme.typography.bodyLarge)
                if (moments.isNotEmpty() || details.isNotEmpty()) androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (m in moments) androidx.compose.material3.AssistChip(onClick = { onMoment(m.conversation, m.line) },
                        label = { Text("▶ ${Fmt.time(m.at)}") })
                    for ((label, intent) in details) androidx.compose.material3.AssistChip(onClick = { runCatching { ctx.startActivity(intent) } },
                        label = { Text(label) })
                }
                Text("${Fmt.time(e.at)} · ${when (e.source) { "button" -> "asked on the Omi"; "watcher" -> "while listening"; "trigger" -> "from a voice trigger"; "capture" -> "double tap"; else -> "typed" }}" +
                    (if (e.cost > 0) " · $%.4f".format(e.cost) else ""), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
