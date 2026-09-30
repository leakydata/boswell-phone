package net.boswell.phone.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.Instant
import java.time.ZoneId

@Composable
fun SearchScreen(vm: ArchiveViewModel, onBack: () -> Unit, onOpen: (Long, Long) -> Unit) {
    var q by rememberSaveable { mutableStateOf("") }
    val hits by vm.search.collectAsStateWithLifecycle()
    val voices by vm.searchVoices.collectAsStateWithLifecycle()
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Column(Modifier.statusBarsPadding()) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "back") }
            OutlinedTextField(
                value = q, onValueChange = { q = it; vm.search(it) }, singleLine = true,
                placeholder = { Text("Search everything that was said") },
                modifier = Modifier.weight(1f).focusRequester(focus),
            )
        }
        val grouped = hits.groupBy { Instant.ofEpochSecond(it.line.t0.toLong()).atZone(ZoneId.systemDefault()).toLocalDate() }
        LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (q.isNotBlank() && hits.isEmpty()) item { Text("Nothing found.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            for ((day, dayHits) in grouped) {
                item(key = "d$day") { Text(Fmt.day(day), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp)) }
                items(dayHits, key = { it.line.id }) { h ->
                    val v = voices[h.line.speaker]
                    Card(onClick = { h.conversation?.let { onOpen(it, h.line.id) } }, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (v != null) { Avatar(v, 20); Spacer(Modifier.width(6.dp)) }
                                Text("${v?.name ?: "Unattributed"} · ${Fmt.time(h.line.t0)}", style = MaterialTheme.typography.labelLarge)
                            }
                            Text(highlight(h.snippet), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                }
            }
        }
    }
}

/** FTS snippets mark matches with [brackets]; show them bold instead. */
@Composable
private fun highlight(s: String) = buildAnnotatedString {
    var i = 0
    while (i < s.length) {
        val a = s.indexOf('[', i)
        if (a < 0) { append(s.substring(i)); break }
        val b = s.indexOf(']', a)
        if (b < 0) { append(s.substring(i)); break }
        append(s.substring(i, a))
        withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)) { append(s.substring(a + 1, b)) }
        i = b + 1
    }
}
