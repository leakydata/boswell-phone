package net.boswell.phone.ui

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import net.boswell.phone.archive.Archive
import net.boswell.phone.asr.CloudAsr
import net.boswell.phone.assistant.AssistantStore
import net.boswell.phone.assistant.LlmReply
import net.boswell.phone.assistant.Secrets
import net.boswell.phone.capture.CaptureService
import java.io.File

data class CompareRow(val clip: String, val started: Double?, val phone: String, val cloud: String? = null, val seconds: Double = 0.0,
                      val cost: Double = 0.0, val error: String? = null)

data class CompareState(
    val engine: CloudAsr.Engine = CloudAsr.Engine.PARAKEET,
    val running: Boolean = false,
    val rows: List<CompareRow> = emptyList(),
    val hasKey: Boolean = false,
) {
    val done: List<CompareRow> get() = rows.filter { it.cloud != null }
    /** Share of the cloud's words the phone got the same, over every finished clip. */
    val agreement: Double? get() {
        var e = 0; var n = 0
        for (r in done) { val (ed, size) = CloudAsr.edits(CloudAsr.words(r.cloud!!), CloudAsr.words(r.phone)); e += ed; n += size }
        return if (n == 0) null else (1.0 - e.toDouble() / n).coerceAtLeast(0.0)
    }
}

class CompareViewModel(app: Application) : AndroidViewModel(app) {
    val state = MutableStateFlow(CompareState(hasKey = Secrets.has(app, Secrets.OPENROUTER)))

    fun setEngine(e: CloudAsr.Engine) = state.update { it.copy(engine = e) }

    /** Transcribe these clips (or the 10 latest with speech) in the cloud and set each beside the phone's version. */
    fun run(clips: List<String>?) = viewModelScope.launch(Dispatchers.IO) {
        val app = getApplication<Application>()
        android.util.Log.i("Boswell", "compare: starting with ${state.value.engine.label} on ${clips?.size ?: "recent"} clips")
        val key = Secrets.get(app, Secrets.OPENROUTER) ?: run { android.util.Log.w("Boswell", "compare: no key"); return@launch }
        val engine = state.value.engine
        val archive = Archive(app)
        val names = try { clips ?: archive.recentSpeechClips(10) } finally { }
        val rows = names.map { CompareRow(it, archive.clipStarted(it), archive.asrText(it)) }
        archive.close()
        android.util.Log.i("Boswell", "compare: ${rows.size} clips")
        state.update { it.copy(running = true, rows = rows) }
        val gate = Semaphore(4)
        rows.map { row ->
            async {
                gate.withPermit {
                    val f = File(CaptureService.clipsDir(app), row.clip)
                    val done = runCatching { CloudAsr.transcribe(key, engine, f) }.fold(
                        { r -> row.copy(cloud = r.text, seconds = r.seconds, cost = r.cost) },
                        { e -> row.copy(cloud = "", error = e.message ?: e.toString()) })
                    val store = AssistantStore(app)
                    try {
                        store.logCall("compare", engine.id, LlmReply(null, emptyList(), kotlinx.serialization.json.JsonObject(emptyMap()), done.cost, 0, 0), done.error)
                    } finally { store.close() }
                    state.update { s -> s.copy(rows = s.rows.map { if (it.clip == row.clip) done else it }) }
                }
            }
        }.awaitAll()
        state.update { it.copy(running = false) }
    }
}

/**
 * The phone's transcription beside a cloud engine's, on the same clips:
 * how much they agree, what it cost, and each clip with the words that
 * differ marked. A report only -- transcripts are not changed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompareScreen(clips: List<String>?, onBack: () -> Unit) {
    val vm: CompareViewModel = viewModel()
    val s by vm.state.collectAsStateWithLifecycle()

    Scaffold(topBar = {
        TopAppBar(title = { Text("Compare with the cloud") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "back") } })
    }) { pad ->
        LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = pad.calculateTopPadding() + 8.dp, bottom = pad.calculateBottomPadding() + 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Transcribe the same clips with a cloud engine and see where it and the phone differ. Your transcripts aren't changed.",
                        style = MaterialTheme.typography.bodyMedium)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(CloudAsr.Engine.entries) { e ->
                            FilterChip(selected = s.engine == e, onClick = { vm.setEngine(e) }, label = { Text(e.label) }, enabled = !s.running)
                        }
                    }
                    Text(s.engine.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("This sends the audio of ${clips?.let { "the ${it.size} selected clip${if (it.size == 1) "" else "s"}" } ?: "your 10 most recent clips with speech"} to OpenRouter.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (!s.hasKey) Text("Add an OpenRouter key in Device → Assistant first.", color = MaterialTheme.colorScheme.error)
                    Button(onClick = { vm.run(clips) }, enabled = s.hasKey && !s.running) {
                        Text(if (s.rows.isEmpty()) "Compare" else "Compare again")
                    }
                }
            }
            if (s.rows.isNotEmpty()) item {
                val cost = s.done.sumOf { it.cost }
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                    Column(Modifier.padding(16.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (s.running) LinearProgressIndicator(progress = { s.done.size / s.rows.size.toFloat() }, modifier = Modifier.fillMaxWidth())
                        Text(s.agreement?.let { "The phone matched ${"%.0f".format(it * 100)}% of ${s.engine.label}'s words" } ?: "Comparing…",
                            style = MaterialTheme.typography.titleMedium)
                        Text("${s.done.size} of ${s.rows.size} clips · ${"$%.4f".format(cost)}" +
                            (s.done.filter { it.error == null }.map { it.seconds }.sorted().let { if (it.isEmpty()) "" else " · about ${"%.1f".format(it[it.size / 2])} s per clip in the cloud" }),
                            style = MaterialTheme.typography.bodySmall)
                        Text("Marked words are where they differ. Neither is the answer key: listen to the clip to know who's right.",
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            items(s.rows, key = { it.clip }) { r ->
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                    Column(Modifier.padding(14.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(r.started?.let { Fmt.time(it) } ?: r.clip, style = MaterialTheme.typography.labelLarge)
                        when {
                            r.cloud == null -> Text("Waiting…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            r.error != null -> Text("Couldn't transcribe: ${r.error}", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                            else -> {
                                val (a, b) = marked(r.phone, r.cloud, MaterialTheme.colorScheme.errorContainer)
                                Label("Phone"); Text(a, style = MaterialTheme.typography.bodyMedium)
                                Label(s.engine.label); Text(b, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Label(text: String) = Text(text, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold,
    color = MaterialTheme.colorScheme.primary)

/** Both texts with the words the other doesn't have highlighted. */
private fun marked(phone: String, cloud: String, highlight: Color): Pair<AnnotatedString, AnnotatedString> {
    val a = CloudAsr.words(phone); val b = CloudAsr.words(cloud)
    val (sa, sb) = CloudAsr.shared(a, b)
    fun build(ws: List<String>, keep: BooleanArray) = buildAnnotatedString {
        ws.forEachIndexed { i, w ->
            if (i > 0) append(" ")
            if (keep[i]) append(w) else withStyle(SpanStyle(background = highlight)) { append(w) }
        }
    }
    return build(a, sa) to build(b, sb)
}
