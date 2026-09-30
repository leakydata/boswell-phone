package net.boswell.phone.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.boswell.phone.archive.ClipRow
import net.boswell.phone.archive.Conversation
import net.boswell.phone.capture.CaptureRepository
import net.boswell.phone.capture.Link
import net.boswell.phone.process.ProcessingRepository
import net.boswell.phone.sound.Sounds
import net.boswell.phone.ui.theme.Voices
import java.time.LocalDate
import java.time.ZoneId

@Composable
fun TodayScreen(vm: ArchiveViewModel, pad: PaddingValues, onOpen: (Long) -> Unit, onSearch: () -> Unit, onDevice: () -> Unit) {
    val s by vm.day.collectAsStateWithLifecycle()
    val proc by ProcessingRepository.state.collectAsStateWithLifecycle()
    val isToday = s.day == LocalDate.now()
    var dragged = remember { floatArrayOf(0f) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().pointerInput(s.day) {
            // Swipe between days: right for the day before, left for the day after.
            detectHorizontalDragGestures(
                onDragStart = { dragged[0] = 0f },
                onDragEnd = { if (dragged[0] > 120) vm.shiftDay(-1) else if (dragged[0] < -120 && !isToday) vm.shiftDay(1) },
            ) { _, dx -> dragged[0] += dx }
        },
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = pad.calculateTopPadding() + 8.dp, bottom = pad.calculateBottomPadding() + 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(Fmt.day(s.day), style = MaterialTheme.typography.headlineLarge)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { vm.shiftDay(-1) }, modifier = Modifier.size(32.dp)) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "previous day") }
                        IconButton(onClick = { vm.shiftDay(1) }, enabled = !isToday, modifier = Modifier.size(32.dp)) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "next day") }
                        if (!isToday) Text("Back to today", color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.clickable { vm.showDay(LocalDate.now()) }.padding(start = 4.dp))
                    }
                }
                IconButton(onClick = onSearch) { Icon(Icons.Filled.Search, "search") }
                StatusPill(onDevice)
            }
        }

        item { DayRibbon(s.ribbon, s.conversations, s.day, onOpen) }

        item {
            val talk = s.conversations.sumOf { it.speechSeconds }
            val people = s.conversations.flatMap { it.speakers }.distinct().mapNotNull { s.voices[it] }.filter { it.named }.map { it.name }
            val summary = buildString {
                append(if (s.conversations.isEmpty()) "No conversations" else "${s.conversations.size} conversation${if (s.conversations.size == 1) "" else "s"}")
                if (talk > 0) append(" · ${Fmt.duration(talk)} of talk")
                if (people.isNotEmpty()) append(" · with ${people.take(3).joinToString(", ")}${if (people.size > 3) " +${people.size - 3}" else ""}")
            }
            Text(summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        if (proc.running && proc.pending > 0) item {
            Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                Text("Transcribing on your phone · ${proc.pending} left", Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    style = MaterialTheme.typography.bodyMedium)
            }
        }

        if (s.conversations.isEmpty() && !s.loading) item { EmptyDay(isToday, onDevice) }

        items(s.conversations, key = { it.id }) { c -> ConversationCard(c, s.voices, onOpen) }
    }
}

@Composable
private fun EmptyDay(isToday: Boolean, onDevice: () -> Unit) {
    val cap by CaptureRepository.state.collectAsStateWithLifecycle()
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(if (isToday) "Nothing to read yet" else "A quiet day", style = MaterialTheme.typography.titleMedium)
            Text(
                when {
                    !isToday -> "Nothing was said near the Omi this day."
                    cap.link == Link.IDLE -> "Connect your Omi and conversations will appear here as they happen."
                    else -> "Your Omi is listening. Conversations appear here a few seconds after they happen."
                },
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (isToday && cap.link == Link.IDLE) Button(onClick = onDevice) { Text("Connect Omi") }
        }
    }
}

/** Recording / Quiet / Away / Off, with battery. Tapping it opens the device page. */
@Composable
fun StatusPill(onClick: () -> Unit) {
    val cap by CaptureRepository.state.collectAsStateWithLifecycle()
    val syncMode = net.boswell.phone.sync.Modes.mode(androidx.compose.ui.platform.LocalContext.current) == net.boswell.phone.sync.Mode.SYNC
    val last = cap.lastAudioMillis
    val quiet = cap.link == Link.STREAMING && (last == null || System.currentTimeMillis() - last > 4_000)
    val (label, dot) = when (cap.link) {
        Link.STREAMING -> if (quiet) "Listening" to Color(0xFF66BB6A) else "Recording" to Color(0xFFE53935)
        Link.CONNECTING -> "Connecting" to Color(0xFFFFB300)
        Link.AWAY -> "Omi away" to Color(0xFF9E9E9E)
        Link.SYNCING -> (cap.sync?.takeIf { it.target > 0 }?.let { "Syncing ${it.took * 100 / it.target}%" } ?: "Syncing") to Color(0xFF42A5F5)
        Link.IDLE -> if (syncMode) "Sync mode" to Color(0xFF42A5F5).copy(alpha = 0.5f) else "Omi off" to Color(0xFF9E9E9E)
    }
    val animated by animateColorAsState(dot, label = "dot")
    Surface(onClick = onClick, shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(animated))
            Spacer(Modifier.width(6.dp))
            Text(label, style = MaterialTheme.typography.labelLarge)
            cap.battery?.let { Text("  ${it.value}%", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

/**
 * The day at a glance: 24 hours left to right. Talk is solid, other sounds
 * (typing, TV, music) are tinted, and background-only recording is faint.
 * Tapping a stretch of talk opens that conversation.
 */
@Composable
fun DayRibbon(clips: List<ClipRow>, conversations: List<Conversation>, day: LocalDate, onOpen: (Long) -> Unit) {
    val start = day.atStartOfDay(ZoneId.systemDefault()).toEpochSecond().toDouble()
    val talk = MaterialTheme.colorScheme.primary
    val sound = MaterialTheme.colorScheme.tertiary
    val faint = MaterialTheme.colorScheme.outlineVariant
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    val tick = MaterialTheme.colorScheme.outline
    Column {
        Canvas(
            Modifier.fillMaxWidth().height(44.dp).clip(RoundedCornerShape(10.dp)).pointerInput(conversations) {
                detectTapGesturesCompat { x ->
                    val t = start + x / size.width * 86_400
                    conversations.minByOrNull { c -> if (t in c.started..c.ended) 0.0 else minOf(kotlin.math.abs(t - c.started), kotlin.math.abs(t - c.ended)) }
                        ?.takeIf { c -> minOf(kotlin.math.abs(t - c.started), kotlin.math.abs(t - c.ended)) < 1800 || t in c.started..c.ended }
                        ?.let { onOpen(it.id) }
                }
            },
        ) {
            drawRect(track)
            fun x(t: Double) = ((t - start) / 86_400 * size.width).toFloat().coerceIn(0f, size.width)
            for (c in clips) {
                val color = when {
                    c.conversation != null -> null
                    c.topSound != null -> sound.copy(alpha = 0.75f)
                    else -> faint
                } ?: continue
                drawRect(color, Offset(x(c.started), size.height * 0.25f), Size((x(c.ended) - x(c.started)).coerceAtLeast(2f), size.height * 0.5f))
            }
            for (c in conversations) {
                drawRoundRect(talk, Offset(x(c.started), 0f), Size((x(c.ended) - x(c.started)).coerceAtLeast(4f), size.height), CornerRadius(4f, 4f))
            }
            for (h in 1 until 24) {
                val hx = h / 24f * size.width
                drawLine(tick.copy(alpha = if (h % 6 == 0) 0.6f else 0.2f), Offset(hx, size.height * (if (h % 6 == 0) 0.7f else 0.85f)), Offset(hx, size.height), strokeWidth = 2f)
            }
            if (day == LocalDate.now()) {
                val now = x(System.currentTimeMillis() / 1000.0)
                drawLine(Color(0xFFE53935), Offset(now, 0f), Offset(now, size.height), strokeWidth = 3f)
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 2.dp)) {
            for (label in listOf("12a", "6a", "12p", "6p", "")) {
                Text(label, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private suspend fun androidx.compose.ui.input.pointer.PointerInputScope.detectTapGesturesCompat(onTap: (Float) -> Unit) =
    detectTapGestures { onTap(it.x) }

@Composable
fun ConversationCard(c: Conversation, voices: Map<String, Voice>, onOpen: (Long) -> Unit) {
    Card(
        onClick = { onOpen(c.id) },
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(Fmt.time(c.started), style = MaterialTheme.typography.titleMedium)
                Text("  ·  ${Fmt.duration(c.ended - c.started)}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.weight(1f))
                Faces(c.speakers.mapNotNull { voices[it] })
            }
            Text(c.snippet, style = MaterialTheme.typography.bodyLarge, maxLines = 3, overflow = TextOverflow.Ellipsis)
            val names = c.speakers.mapNotNull { voices[it] }.filter { it.named }.map { it.name }
            if (names.isNotEmpty() || c.sounds.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (names.isNotEmpty()) Text(names.joinToString(", "), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    for (snd in c.sounds.map(Sounds::display).distinct().take(3)) SoundChip(snd)
                }
            }
        }
    }
}

@Composable
fun SoundChip(label: String) {
    Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.tertiaryContainer) {
        Text(label, Modifier.padding(horizontal = 8.dp, vertical = 2.dp), style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onTertiaryContainer)
    }
}

/** Overlapping initials, one per voice, in each voice's color. */
@Composable
fun Faces(voices: List<Voice>, size: Int = 28) {
    Box {
        voices.take(4).forEachIndexed { i, v ->
            Avatar(v, size, Modifier.offset(x = (i * (size * 0.7)).dp))
        }
        Spacer(Modifier.width((size + (voices.take(4).size - 1).coerceAtLeast(0) * size * 0.7).dp))
    }
}

@Composable
fun Avatar(v: Voice, size: Int = 36, modifier: Modifier = Modifier) {
    Box(
        modifier.size(size.dp).clip(CircleShape).background(if (v.media) MaterialTheme.colorScheme.surfaceVariant else Voices.color(v.key)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (v.media) "TV" else if (v.named) Voices.initials(v.name) else "?",
            color = if (v.media) MaterialTheme.colorScheme.onSurfaceVariant else Color.White,
            fontSize = (size * 0.4).sp, fontWeight = FontWeight.SemiBold,
        )
    }
}
