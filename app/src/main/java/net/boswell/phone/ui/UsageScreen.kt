package net.boswell.phone.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.boswell.phone.assistant.AssistantPrefs
import net.boswell.phone.assistant.AssistantStore
import net.boswell.phone.assistant.Llm
import net.boswell.phone.assistant.Secrets
import java.time.LocalDate
import java.time.ZoneId

private data class UsageView(
    val today: AssistantStore.Usage, val week: AssistantStore.Usage, val month: AssistantStore.Usage,
    val byPurpose: List<Triple<String, Int, Double>>, val perDay: List<Pair<LocalDate, Double>>,
    val watcherToday: Double, val budget: Double, val key: Llm.KeyInfo?,
)

private fun purposeName(p: String) = when (p) {
    "typed", "ask" -> "Typed questions"
    "button" -> "Questions on the Omi"
    "capture" -> "Double-tap captures"
    "trigger" -> "Voice triggers"
    "watcher" -> "Listen-along hints"
    else -> p
}

private fun money(d: Double) = if (d < 0.01 && d > 0) "$%.4f".format(d) else "$%.2f".format(d)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UsageScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var v by remember { mutableStateOf<UsageView?>(null) }
    LaunchedEffect(Unit) {
        v = withContext(Dispatchers.IO) {
            val s = AssistantStore(ctx)
            try {
                val zone = ZoneId.systemDefault()
                val today = LocalDate.now()
                fun start(d: LocalDate) = d.atStartOfDay(zone).toEpochSecond().toDouble()
                val month = start(today.withDayOfMonth(1))
                UsageView(
                    today = s.usage(start(today)), week = s.usage(start(today.minusDays(6))), month = s.usage(month),
                    byPurpose = s.byPurpose(month), perDay = s.perDay(14),
                    watcherToday = s.spentToday("watcher"), budget = AssistantPrefs.budget(ctx),
                    key = Secrets.get(ctx, Secrets.OPENROUTER)?.let { Llm(it, AssistantPrefs.model(ctx)).keyInfo() },
                )
            } finally { s.close() }
        }
    }
    Scaffold(topBar = {
        TopAppBar(navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "back") } },
            title = { Text("AI usage") })
    }) { pad ->
        val u = v
        LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = pad.calculateTopPadding() + 8.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (u == null) { item { Text("Adding it up…") }; return@LazyColumn }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for ((label, x) in listOf("Today" to u.today, "7 days" to u.week, "This month" to u.month)) {
                        Card(Modifier.weight(1f), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                            Column(Modifier.padding(12.dp)) {
                                Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(money(x.cost), style = MaterialTheme.typography.titleLarge)
                                Text("${x.calls} call${if (x.calls == 1) "" else "s"}", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Listen-along budget today", style = MaterialTheme.typography.titleSmall)
                        LinearProgressIndicator(progress = { (u.watcherToday / u.budget).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                        Text("${money(u.watcherToday)} of ${money(u.budget)}" +
                            if (u.watcherToday >= u.budget) " · reached, hints paused until tomorrow" else "",
                            style = MaterialTheme.typography.bodySmall)
                        Text("Questions, captures and triggers are never stopped by this budget.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Last 14 days", style = MaterialTheme.typography.titleSmall)
                        val max = u.perDay.maxOf { it.second }.coerceAtLeast(1e-6)
                        val bar = MaterialTheme.colorScheme.primary
                        val track = MaterialTheme.colorScheme.surfaceContainerHighest
                        Canvas(Modifier.fillMaxWidth().height(90.dp)) {
                            val w = size.width / u.perDay.size
                            u.perDay.forEachIndexed { i, (_, c) ->
                                drawRoundRect(track, Offset(i * w + w * 0.15f, 0f), Size(w * 0.7f, size.height), CornerRadius(6f))
                                val h = (c / max * size.height).toFloat()
                                if (h > 0) drawRoundRect(bar, Offset(i * w + w * 0.15f, size.height - h), Size(w * 0.7f, h), CornerRadius(6f))
                            }
                        }
                        Row(Modifier.fillMaxWidth()) {
                            Text(Fmt.shortDay(u.perDay.first().first), Modifier.weight(1f), style = MaterialTheme.typography.labelSmall)
                            Text("busiest day ${money(max)}", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("This month by use", style = MaterialTheme.typography.titleSmall)
                        if (u.byPurpose.isEmpty()) Text("Nothing yet.")
                        for ((p, n, c) in u.byPurpose) Row(Modifier.fillMaxWidth()) {
                            Text(purposeName(p), Modifier.weight(1f))
                            Text("$n · ${money(c)}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (u.month.calls > 0) Text("About ${money(u.month.cost / u.month.calls)} per call on average · ${u.month.errors} failed",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Your OpenRouter key", style = MaterialTheme.typography.titleSmall)
                        val k = u.key
                        if (k == null) Text("Couldn't reach OpenRouter.", style = MaterialTheme.typography.bodySmall)
                        else {
                            Text("Today ${money(k.daily)} · week ${money(k.weekly)} · month ${money(k.monthly)}")
                            Text(if (k.limit != null) "Limit ${money(k.limit)} · ${money(k.remaining ?: 0.0)} left" else "No spending limit set on this key",
                                style = MaterialTheme.typography.bodySmall)
                            Text("Counts everything using this key, including other apps; OpenRouter's days are UTC.",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}
