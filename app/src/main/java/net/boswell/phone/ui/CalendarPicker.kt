package net.boswell.phone.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import net.boswell.phone.todo.Calendar

/**
 * Pick the calendar new events go into, grouped by account. Refresh asks
 * Google to sync the calendar list now, so one created on the web a minute
 * ago shows up without waiting for the daily sync.
 */
@Composable
fun CalendarPicker(onDismiss: () -> Unit, onChosen: (Calendar.Cal) -> Unit) {
    val ctx = LocalContext.current
    var cals by remember { mutableStateOf(Calendar.calendars(ctx).filter { it.writable }) }
    var refreshing by remember { mutableStateOf(0) }
    val chosen = remember { Calendar.chosen(ctx)?.id }
    LaunchedEffect(refreshing) {
        if (refreshing == 0) return@LaunchedEffect
        Calendar.refresh(ctx)
        // The sync runs in Google's app; look again as it lands.
        repeat(6) { delay(2_500); cals = Calendar.calendars(ctx).filter { it.writable } }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add events to…") },
        text = {
            LazyColumn {
                item {
                    Text("A calendar made on the web appears here after the Calendar app syncs. Missing one? Open Calendar, pull down to refresh, and make sure the calendar is ticked to sync, then come back.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                val byAccount = cals.sortedWith(compareBy({ it.account }, { !it.synced }, { it.name })).groupBy { it.account }
                for ((account, list) in byAccount) {
                    item(key = "a$account") { Text(account, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 10.dp, bottom = 2.dp)) }
                    items(list, key = { it.id }) { c ->
                        Row(Modifier.fillMaxWidth().clickable { Calendar.choose(ctx, c.id); onChosen(c) }.padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.padding(end = 12.dp).size(14.dp).clip(CircleShape).background(Color(c.color)))
                            Column(Modifier.weight(1f)) {
                                Text(c.name)
                                if (!c.synced) Text("not synced to this phone yet — choosing it turns sync on",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (c.id == chosen) Text("✓", color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        dismissButton = {
            Row {
                // Only Google's own app can make the phone fetch a calendar created
                // elsewhere; opening it (and pulling down to refresh) does that.
                TextButton(onClick = {
                    Calendar.refresh(ctx); refreshing++
                    ctx.packageManager.getLaunchIntentForPackage("com.google.android.calendar")?.let { ctx.startActivity(it) }
                }) { Text("Missing one?") }
            }
        },
    )
}
