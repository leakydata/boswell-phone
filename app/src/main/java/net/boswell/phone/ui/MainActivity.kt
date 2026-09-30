package net.boswell.phone.ui

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import net.boswell.phone.capture.CaptureState
import net.boswell.phone.capture.Link
import net.boswell.phone.capture.Reading
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        permissions.launch(
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.POST_NOTIFICATIONS,
            )
        )
        setContent {
            val dark = isSystemInDarkTheme()
            MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                val ui by vm.ui.collectAsStateWithLifecycle()
                val cap by vm.capture.collectAsStateWithLifecycle()
                AppScaffold(ui, cap, vm)
            }
        }
    }
}

/** "12 s ago", ticking. Every device reading on screen says how old it is. */
@Composable
internal fun ago(atMillis: Long?): String {
    val now = remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(1_000); now.longValue = System.currentTimeMillis() } }
    if (atMillis == null) return "never"
    val s = ((now.longValue - atMillis) / 1000).coerceAtLeast(0)
    return when {
        s < 60 -> "${s}s ago"
        s < 3600 -> "${s / 60}m ago"
        else -> "${s / 3600}h ${(s % 3600) / 60}m ago"
    }
}

@Composable
private fun <T> ReadingRow(label: String, r: Reading<T>?, show: (T) -> String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(if (r == null) "—" else "${show(r.value)}  ·  ${ago(r.atMillis)}", style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun Row2(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
internal fun RecordScreen(ui: UiState, cap: CaptureState, vm: MainViewModel, pad: PaddingValues) {
    val time = remember { SimpleDateFormat("MMM d HH:mm:ss", Locale.getDefault()) }
    run {
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = pad.calculateTopPadding() + 8.dp, bottom = pad.calculateBottomPadding() + 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { Text("Boswell", style = MaterialTheme.typography.headlineMedium) }

            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        val (label, color) = when (cap.link) {
                            Link.IDLE -> "Not connected" to Color.Gray
                            Link.CONNECTING -> "Connecting…" to Color(0xFFB8860B)
                            Link.STREAMING ->
                                if (cap.lastAudioMillis != null && System.currentTimeMillis() - cap.lastAudioMillis > 4_000)
                                    "Connected · quiet (mic asleep until there is sound)" to Color(0xFF2E7D32)
                                else "Recording" to Color(0xFF2E7D32)
                            Link.AWAY -> "Omi away — will retry" to Color(0xFF8D6E63)
                        }
                        Text(label, color = color, style = MaterialTheme.typography.titleMedium)
                        if (cap.link == Link.AWAY && cap.nextRetryMillis != null) {
                            Text("next try at ${time.format(Date(cap.nextRetryMillis))}", style = MaterialTheme.typography.bodySmall)
                        }
                        cap.device?.let { d ->
                            Row2("Device", "${d.model ?: d.name ?: "Omi"} · fw ${d.firmware ?: "?"}")
                            Row2("Address", d.address)
                            Row2("Codec", "${d.codec ?: "?"} (${d.frameSamples * 1000 / 16000} ms Opus) · MTU ${d.mtu}")
                        } ?: ui.savedAddress?.let { Row2("Saved Omi", it) }
                        ReadingRow("Battery", cap.battery) { "$it%" }
                        ReadingRow("Charging", cap.charging) { if (it) "yes" else "no" }
                        ReadingRow("Link RSSI", cap.rssi) { "$it dBm" }
                        ReadingRow("Device clock", cap.deviceClockSkewSeconds) { if (kotlin.math.abs(it) < 3) "in sync" else "off by ${it}s" }
                    }
                }
            }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (cap.link == Link.IDLE) {
                        Button(onClick = vm::connect, enabled = ui.savedAddress != null) { Text("Connect") }
                        OutlinedButton(onClick = vm::scan, enabled = !ui.scanning) { Text(if (ui.scanning) "Scanning…" else "Scan") }
                        if (ui.savedAddress != null) TextButton(onClick = vm::forget) { Text("Forget") }
                    } else {
                        Button(onClick = vm::disconnect) { Text("Disconnect") }
                        OutlinedButton(onClick = vm::refreshRing, enabled = cap.link == Link.STREAMING) { Text("Check backlog") }
                    }
                }
            }

            if (ui.found.isNotEmpty()) {
                item { Text("Found", style = MaterialTheme.typography.titleSmall) }
                items(ui.found, key = { it.address }) { d ->
                    Card(Modifier.fillMaxWidth().clickable { vm.choose(d.address) }) {
                        Row(Modifier.padding(16.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("${d.name ?: "Omi"}\n${d.address}")
                            // Advertisement RSSI, not the link: it is labelled so nobody mistakes it.
                            Text("${d.advertisedRssi} dBm (adv)")
                        }
                    }
                }
            }

            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Stream", style = MaterialTheme.typography.titleSmall)
                        Row2("Last audio", ago(cap.lastAudioMillis))
                        Row2("Frames", "${cap.frames}")
                        Row2("Unusable", "${cap.dropped}")
                        Row2("Device reboots", "${cap.reboots}")
                        Row2("Current clip", "%.1f s / 30 s".format(cap.heldSeconds))
                        Row2("Clips this session", "${cap.clipsWritten}")
                        ReadingRow("Backlog on device", cap.ring) { r ->
                            // 1,115,064 packets hold about 27 h (OMI-PROTOCOL.md): ~0.087 s each.
                            val min = r.pending * (27 * 3600.0 / 1_115_064) / 60
                            "${r.pending} pkts ≈ %.0f min".format(min)
                        }
                    }
                }
            }

            item { Text("Recordings (${ui.clips.size})", style = MaterialTheme.typography.titleSmall) }
            items(ui.clips, key = { it.file.name }) { c ->
                Row(
                    Modifier.fillMaxWidth().clickable { vm.togglePlay(c.file) }.padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(if (ui.playing == c.file) Icons.Filled.Stop else Icons.Filled.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(time.format(Date(c.endedMillis)) + if (c.timeKnown) "" else "  (arrival time)")
                        Text("%.1f s".format(c.seconds), style = MaterialTheme.typography.bodySmall)
                    }
                }
                HorizontalDivider()
            }

            item { Text("Log", style = MaterialTheme.typography.titleSmall) }
            items(cap.log.asReversed().take(60)) { line ->
                Text(line, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
            }
        }
    }
}
