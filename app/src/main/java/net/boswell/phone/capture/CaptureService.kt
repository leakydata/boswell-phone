package net.boswell.phone.capture

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.boswell.phone.R
import net.boswell.phone.audio.OpusFrameDecoder
import net.boswell.phone.omi.LivePacket
import net.boswell.phone.omi.OmiConnection
import net.boswell.phone.omi.OmiUuids
import net.boswell.phone.omi.RunTracker
import net.boswell.phone.ui.MainActivity
import java.io.File
import java.util.concurrent.Executors

/**
 * Holds the Omi connection while the app is in the background.
 *
 * The radio is exclusive -- one connection at a time, shared with the desktop
 * daemon and the vendor app -- so this is the only thing that talks to the
 * device, and everything else asks it.
 */
class CaptureService : LifecycleService() {

    private var captureJob: Job? = null
    private var connection: OmiConnection? = null
    private var clipper: Clipper? = null
    /** When audio started flowing in the current session; null until it does. */
    private var streamingSince: Long? = null

    /** Decoding and file writes happen on one thread, in arrival order. */
    private val audioThread = Executors.newSingleThreadExecutor { Thread(it, "omi-audio") }
        .asCoroutineDispatcher()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START -> {
                val address = intent.getStringExtra(EXTRA_ADDRESS) ?: return START_NOT_STICKY
                goForeground("Connecting to Omi…")
                startCapture(address)
            }
            ACTION_STOP -> lifecycleScope.launch { stopCapture("stopped by user"); stopSelf() }
            ACTION_RING_INFO -> lifecycleScope.launch { refreshRing() }
        }
        return START_NOT_STICKY
    }

    private fun goForeground(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Recording", NotificationManager.IMPORTANCE_LOW)
        )
        startForeground(NOTIFICATION_ID, notification(text), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
    }

    private fun notification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_mic)
            .setContentTitle("Boswell")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
                )
            )
            .build()

    private fun notify(text: String) =
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))

    private fun startCapture(address: String) {
        if (captureJob?.isActive == true) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            CaptureRepository.log("no Bluetooth permission")
            stopSelf()
            return
        }
        captureJob = lifecycleScope.launch {
            var backoffMs = BACKOFF_MIN_MS
            while (isActive) {
                streamingSince = null
                try {
                    session(address)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    CaptureRepository.log("link lost: ${e.message}")
                } finally {
                    withContext(audioThread) { runCatching { clipper?.flush() } }
                    connection?.close()
                    connection = null
                }
                // A session that streamed for a while earns a fast retry; one
                // that never got going backs off, so an absent device is not
                // hammered all night.
                val streamedFor = streamingSince?.let { System.currentTimeMillis() - it } ?: 0L
                backoffMs = if (streamedFor > 60_000) BACKOFF_MIN_MS else (backoffMs * 2).coerceAtMost(BACKOFF_MAX_MS)
                val retryAt = System.currentTimeMillis() + backoffMs
                CaptureRepository.update { it.copy(link = Link.AWAY, nextRetryMillis = retryAt) }
                notify("Omi away — retrying in ${backoffMs / 1000}s")
                delay(backoffMs)
            }
        }
    }

    /** One connection, from connect to loss. Always ends by throwing. */
    private suspend fun session(address: String) {
        val adapter = getSystemService(BluetoothManager::class.java).adapter
        val device = adapter.getRemoteDevice(address)
        CaptureRepository.update { it.copy(link = Link.CONNECTING, nextRetryMillis = null) }
        CaptureRepository.log("connecting to $address")

        val packets = Channel<ByteArray>(capacity = 2_000)
        val conn = OmiConnection(this, device) { data ->
            if (packets.trySend(data).isFailure) CaptureRepository.update { it.copy(dropped = it.dropped + 1) }
        }
        connection = conn
        conn.connect()

        val codec = runCatching { conn.read(OmiUuids.CODEC)[0].toInt() and 0xff }.getOrNull()
        val frameSamples = OmiUuids.CODEC_FRAME_SAMPLES[codec] ?: 320
        val info = DeviceInfo(
            name = runCatching { device.name }.getOrNull(),
            address = address,
            model = readText(conn, OmiUuids.MODEL),
            firmware = readText(conn, OmiUuids.FIRMWARE),
            hardware = readText(conn, OmiUuids.HARDWARE),
            codec = codec,
            frameSamples = frameSamples,
            mtu = conn.mtu,
        )
        CaptureRepository.update { it.copy(device = info) }
        CaptureRepository.log("connected: ${info.model ?: "Omi"} fw ${info.firmware} codec $codec mtu ${conn.mtu}")
        readVolatile(conn)

        val decoder = OpusFrameDecoder(OmiUuids.SAMPLE_RATE, frameSamples)
        val run = RunTracker()
        val frameMs = frameSamples * 1000 / OmiUuids.SAMPLE_RATE
        val clip = Clipper(clipsDir(this), info.address.lowercase().filter { it in "0123456789abcdef" },
            frameMs = frameMs).also { it.bootId = run.id }
        clipper = clip

        val consumer = lifecycleScope.launch(audioThread) {
            for (data in packets) {
                val p = LivePacket.parse(data) ?: continue
                if (p.index != 0) {
                    // A frame split across notifications. Not seen on a CV 1 at
                    // this MTU; guessing at the other half would be worse.
                    CaptureRepository.update { it.copy(dropped = it.dropped + 1) }
                    continue
                }
                if (run.note(p.counter)) {
                    // The device rebooted mid-clip: file what belongs to the
                    // run that ended before anything new is mixed in.
                    clip.flush()
                    clip.bootId = run.id
                    decoder.reset()
                    CaptureRepository.update { it.copy(reboots = it.reboots + 1) }
                    CaptureRepository.log("device counter restarted (reboot)")
                }
                val pcm = runCatching { decoder.decode(p.opus) }.getOrNull()
                if (pcm == null) {
                    CaptureRepository.update { it.copy(dropped = it.dropped + 1) }
                    continue
                }
                val written = clip.add(run.extended, pcm)
                val now = System.currentTimeMillis()
                CaptureRepository.update {
                    it.copy(
                        link = Link.STREAMING,
                        frames = it.frames + 1,
                        lastAudioMillis = now,
                        heldSeconds = clip.heldSeconds,
                        clipsWritten = if (written != null) it.clipsWritten + 1 else it.clipsWritten,
                    )
                }
                if (written != null) {
                    CaptureRepository.log("clip ${written.name}")
                    net.boswell.phone.process.ProcessingWorker.enqueue(this@CaptureService)
                }
            }
        }

        conn.enableNotifications(OmiUuids.AUDIO)
        CaptureRepository.log("subscribed to audio")
        val began = System.currentTimeMillis()
        streamingSince = began
        // Connected and subscribed is "streaming" even before a frame arrives:
        // in a quiet room the first one may be minutes away.
        CaptureRepository.update { it.copy(link = Link.STREAMING) }
        notify("Recording from Omi")

        try {
            var tick = 0
            var lastNotes = conn.notifications
            var lastFrames = CaptureRepository.state.value.frames
            var failedChecks = 0
            while (!conn.disconnected.isCompleted) {
                delay(1_000)
                tick++
                val now = System.currentTimeMillis()
                val last = maxOf(CaptureRepository.state.value.lastAudioMillis ?: 0L, began)

                // Silence is not a fault. The CV 1's microphone has hardware
                // acoustic activity detection (CONFIG_OMI_ENABLE_T5838_AAD):
                // after ~3 s of quiet it stops its clock and the stream pauses
                // until there is sound. A quiet room sends nothing for as long
                // as it stays quiet -- measured here as minutes of zero
                // notifications on a healthy link.
                //
                // So a pause closes the clip instead: otherwise a clip would
                // join audio from either side of a long silence and be filed
                // under one arrival time, placing half of it in the wrong place.
                if (now - last > PAUSE_CLOSES_CLIP_MS && clip.heldSeconds > 0) {
                    val f = withContext(audioThread) { clip.flush() }
                    if (f != null) {
                        CaptureRepository.update { it.copy(clipsWritten = it.clipsWritten + 1, heldSeconds = 0.0) }
                        CaptureRepository.log("clip ${f.name} (closed by a pause)")
                        net.boswell.phone.process.ProcessingWorker.enqueue(this@CaptureService)
                    }
                }

                // Liveness is the link answering, not audio arriving: a read
                // that fails twice in a row means the link is gone even if the
                // stack has not said so yet.
                if (tick % LINK_CHECK_SECONDS == 0) {
                    val ok = runCatching { readVolatile(conn, strict = true) }.isSuccess
                    failedChecks = if (ok) 0 else failedChecks + 1
                    if (failedChecks >= 2) throw IllegalStateException("device stopped answering")
                }
                if (tick % 10 == 0) {
                    val n = conn.notifications; val f = CaptureRepository.state.value.frames
                    android.util.Log.i("Boswell", "rate: ${(n - lastNotes) / 10.0}/s arriving, ${(f - lastFrames) / 10.0}/s decoded")
                    lastNotes = n; lastFrames = f
                }
                if (tick % 5 == 0) {
                    val st = CaptureRepository.state.value
                    val quiet = now - last > PAUSE_CLOSES_CLIP_MS
                    notify("${if (quiet) "Listening (quiet)" else "Recording"} · ${st.clipsWritten} clips · battery ${st.battery?.value ?: "?"}%")
                }
            }
            throw IllegalStateException("device disconnected")
        } finally {
            packets.close()
            consumer.join()
            CaptureRepository.update { it.copy(heldSeconds = 0.0) }
        }
    }

    private suspend fun readText(conn: OmiConnection, uuid: java.util.UUID): String? =
        runCatching { String(conn.read(uuid)).trim('\u0000', ' ') }.getOrNull()

    /** Battery, charging, RSSI, clock. With [strict], a failed battery read throws: it is the link check. */
    private suspend fun readVolatile(conn: OmiConnection, strict: Boolean = false) {
        val now = System.currentTimeMillis()
        val battery = if (strict) conn.read(OmiUuids.BATTERY)[0].toInt() and 0xff
        else runCatching { conn.read(OmiUuids.BATTERY)[0].toInt() and 0xff }.getOrNull()
        battery?.let { b -> CaptureRepository.update { it.copy(battery = Reading(b, now)) } }
        runCatching { conn.read(OmiUuids.CHARGING)[0].toInt() != 0 }.getOrNull()?.let { c ->
            CaptureRepository.update { it.copy(charging = Reading(c, now)) }
        }
        runCatching { conn.rssi() }.getOrNull()?.let { r ->
            CaptureRepository.update { it.copy(rssi = Reading(r, now)) }
        }
        runCatching {
            val v = conn.read(OmiUuids.TIME_READ)
            (v[0].toLong() and 0xff) or ((v[1].toLong() and 0xff) shl 8) or
                ((v[2].toLong() and 0xff) shl 16) or ((v[3].toLong() and 0xff) shl 24)
        }.getOrNull()?.let { devEpoch ->
            CaptureRepository.update { it.copy(deviceClockSkewSeconds = Reading(devEpoch - now / 1000, now)) }
        }
    }

    private suspend fun refreshRing() {
        val conn = connection ?: return CaptureRepository.log("ring info: not connected")
        runCatching { conn.ringInfo() }
            .onSuccess { r ->
                CaptureRepository.update { it.copy(ring = Reading(r, System.currentTimeMillis())) }
                CaptureRepository.log("ring: ${r.pending} packets waiting, read ${r.read} write ${r.write}")
            }
            .onFailure { CaptureRepository.log("ring info failed: ${it.message}") }
    }

    private suspend fun stopCapture(why: String) {
        captureJob?.cancel()
        captureJob?.join()
        captureJob = null
        withContext(audioThread) { runCatching { clipper?.flush() } }
        connection?.close()
        connection = null
        CaptureRepository.update { it.copy(link = Link.IDLE, nextRetryMillis = null, heldSeconds = 0.0) }
        CaptureRepository.log(why)
    }

    override fun onDestroy() {
        runCatching { clipper?.flush() }
        connection?.close()
        audioThread.close()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "net.boswell.phone.START"
        const val ACTION_STOP = "net.boswell.phone.STOP"
        const val ACTION_RING_INFO = "net.boswell.phone.RING_INFO"
        const val EXTRA_ADDRESS = "address"
        private const val CHANNEL = "capture"
        private const val NOTIFICATION_ID = 1
        /** The mic sleeps after ~3 s of quiet (OMI_VAD_HOLD_MS); a gap longer than that is a pause. */
        private const val PAUSE_CLOSES_CLIP_MS = 4_000L
        private const val LINK_CHECK_SECONDS = 30
        private const val BACKOFF_MIN_MS = 5_000L
        private const val BACKOFF_MAX_MS = 120_000L

        fun clipsDir(context: Context) = File(context.filesDir, "clips").apply { mkdirs() }

        fun start(context: Context, address: String) =
            ContextCompat.startForegroundService(
                context,
                Intent(context, CaptureService::class.java).setAction(ACTION_START).putExtra(EXTRA_ADDRESS, address),
            )

        fun stop(context: Context) =
            context.startService(Intent(context, CaptureService::class.java).setAction(ACTION_STOP))

        fun refreshRing(context: Context) =
            context.startService(Intent(context, CaptureService::class.java).setAction(ACTION_RING_INFO))
    }
}
