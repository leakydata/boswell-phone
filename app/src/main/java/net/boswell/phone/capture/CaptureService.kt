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
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
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
            ACTION_SYNC -> {
                val address = intent.getStringExtra(EXTRA_ADDRESS) ?: return START_NOT_STICKY
                goForeground("Syncing with Omi…")
                startSync(address)
            }
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
                var drain = false
                try {
                    session(address)
                } catch (e: OnCharger) {
                    drain = true
                } catch (e: CancellationException) {
                    // Only a real stop ends the loop; anything else that looks
                    // like a cancellation (a stray timeout) is a failed attempt.
                    if (!isActive) throw e
                    CaptureRepository.log("link lost: ${e.message}")
                } catch (e: Exception) {
                    CaptureRepository.log("link lost: ${e.message}")
                } finally {
                    withContext(audioThread) { runCatching { clipper?.flush() } }
                    connection?.close()
                    connection = null
                }
                if (drain) {
                    drainOnCharger(address)
                    backoffMs = BACKOFF_MIN_MS
                    continue
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
        val conn = OmiConnection(this, device, onAudio = { data ->
            if (packets.trySend(data).isFailure) CaptureRepository.update { it.copy(dropped = it.dropped + 1) }
        }, onButton = { code -> lifecycleScope.launch { onButton(code) } })
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
        CaptureRepository.state.value.let { st ->
            CaptureRepository.log("battery ${st.battery?.value ?: "?"}% · " + when (st.charging?.value) { true -> "charging"; false -> "not charging"; null -> "charging unknown" })
        }
        syncClock(conn)
        checkCharger()

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
                question?.add(pcm)
                net.boswell.phone.setup.Enrollment.feed(pcm)
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
        // The button, on the connection that is already open. Never fatal: the
        // desktop's BlueZ refused this subscription on every attempt, and a
        // recorder that streams but cannot report its button is still a recorder.
        val buttonOk = conn.has(OmiUuids.BUTTON) && runCatching { conn.enableNotifications(OmiUuids.BUTTON) }
            .onFailure { CaptureRepository.log("button: ${it.message}") }.isSuccess
        CaptureRepository.update { it.copy(buttonReady = buttonOk) }
        if (buttonOk) CaptureRepository.log("button ready: tap to ask")
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
                    if (ok && question == null) checkCharger()
                }
                if (tick % net.boswell.phone.assistant.Watcher.EVERY_SECONDS == 0 && watching?.isActive != true) {
                    watching = lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) { runCatching { watcher.tick() } }
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

    // ------------------------------------------------------------ assistant

    @Volatile private var question: net.boswell.phone.assistant.QuestionCapture? = null
    private var watching: Job? = null
    private val watcher by lazy { net.boswell.phone.assistant.Watcher(this) }

    /** A short buzz on the Omi, if it has a motor. Best effort: feedback, never a failure. */
    private fun buzz(level: Int) {
        val c = connection ?: return
        if (!c.has(OmiUuids.HAPTIC)) return
        lifecycleScope.launch { runCatching { c.write(OmiUuids.HAPTIC, byteArrayOf(level.toByte())) } }
    }

    private fun onButton(code: Int) {
        CaptureRepository.log("button event $code")
        if (code == 1 || code == 2) CaptureRepository.update { it.copy(lastButton = code to System.currentTimeMillis()) }
        if (ButtonTest.active) { if (code == 1 || code == 2) buzz(1); return }
        when (code) {
            1 -> {
                val q = question
                if (q != null) q.finish()      // a second tap ends the question
                else startQuestion(capture = false)
            }
            2 -> when (net.boswell.phone.assistant.AssistantPrefs.doubleTap(this)) {
                net.boswell.phone.assistant.AssistantPrefs.DoubleTap.TODO -> if (question == null) startQuestion(capture = true)
                net.boswell.phone.assistant.AssistantPrefs.DoubleTap.BOOKMARK -> {
                    val store = net.boswell.phone.assistant.AssistantStore(this)
                    store.addBookmark(System.currentTimeMillis() / 1000.0); store.close()
                    net.boswell.phone.assistant.AssistantNotify.post(this, net.boswell.phone.assistant.AssistantNotify.ANSWERS, "Bookmarked", "Marked this moment.")
                }
                net.boswell.phone.assistant.AssistantPrefs.DoubleTap.SUMMARIZE -> lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    val a = net.boswell.phone.assistant.Assistant(this@CaptureService).ask("Summarize what was said in the last 10 minutes.", "button")
                    net.boswell.phone.assistant.AssistantNotify.post(this@CaptureService, net.boswell.phone.assistant.AssistantNotify.ANSWERS, "Last 10 minutes", a.text)
                }
            }
            3 -> CaptureRepository.log("long press: the Omi turns itself off")
        }
    }

    /**
     * Tap to ask: listen until the question is over, transcribe it here, and
     * hand it to the assistant. The recognizer loads while you talk, so the
     * two seconds it takes are not added on after.
     */
    private fun startQuestion(capture: Boolean) {
        val models = net.boswell.phone.models.ModelStore(this)
        if (!models.isInstalled(net.boswell.phone.models.ModelCatalog.ASR)) {
            net.boswell.phone.assistant.AssistantNotify.post(this, net.boswell.phone.assistant.AssistantNotify.ANSWERS, "Can't listen yet", "Download the transcription model first (Device → On-device models).")
            return
        }
        val q = net.boswell.phone.assistant.QuestionCapture()
        question = q
        buzz(1)          // heard the tap: talk now
        CaptureRepository.update { it.copy(asking = "listening") }
        net.boswell.phone.assistant.AssistantNotify.post(this, net.boswell.phone.assistant.AssistantNotify.LISTENING, "Listening…",
            if (capture) "Say what to remember. Tap once when you're done." else "Ask your question. Tap again when you're done.", LISTENING_ID)
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.Default) {
            val asr = async { net.boswell.phone.asr.LocalAsr(models) }
            val started = System.currentTimeMillis()
            while (!q.done) {
                delay(100)
                val last = CaptureRepository.state.value.lastAudioMillis ?: 0L
                if (System.currentTimeMillis() - maxOf(last, started) > 1_000) q.idle()
                if (System.currentTimeMillis() - started > 25_000) q.finish()
            }
            question = null
            CaptureRepository.update { it.copy(asking = "thinking") }
            net.boswell.phone.assistant.AssistantNotify.cancel(this@CaptureService, LISTENING_ID)
            val text = asr.await().use { it.transcribe(q.audio()) }.joinToString(" ") { it.text }.trim()
            if (text.isEmpty()) {
                buzz(3)
                net.boswell.phone.assistant.AssistantNotify.post(this@CaptureService, net.boswell.phone.assistant.AssistantNotify.ANSWERS, "Didn't catch that", "Tap the Omi and try again.")
            } else {
                CaptureRepository.log(if (capture) "captured: $text" else "asked: $text")
                val source = if (capture) net.boswell.phone.assistant.Assistant.CAPTURE else "button"
                val a = withContext(kotlinx.coroutines.Dispatchers.IO) { net.boswell.phone.assistant.Assistant(this@CaptureService).ask(text, source) }
                buzz(if (a.error) 3 else 2)          // the answer is on the phone
                net.boswell.phone.assistant.AssistantNotify.post(this@CaptureService, net.boswell.phone.assistant.AssistantNotify.ANSWERS,
                    if (capture) "To-do" else text.take(60), a.text)
            }
            CaptureRepository.update { it.copy(asking = null) }
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
        val charging = runCatching { conn.read(OmiUuids.CHARGING)[0].toInt() != 0 }.getOrNull()
        charging?.let { c -> CaptureRepository.update { it.copy(charging = Reading(c, now)) } }
        battery?.let { BatteryWatch.omi(this, it, charging) }
        BatteryWatch.phone(this)
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

    /**
     * A sync visit: connect, set the device clock (stored packets are stamped
     * with it), download at most VISIT_SECONDS of backlog into the spool, let
     * go, then turn the spool into clips and queue them for transcription.
     */
    private fun startSync(address: String) {
        if (captureJob?.isActive == true) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            CaptureRepository.log("no Bluetooth permission"); stopSelf(); return
        }
        captureJob = lifecycleScope.launch {
            val r = syncVisit(address)
            net.boswell.phone.sync.Modes.recordSync(this@CaptureService, r.text)
            captureJob = null
            stopSelf()
        }
    }

    private class VisitResult(val text: String, val failed: Boolean, val emptied: Boolean)

    /** One sync visit; never throws except to stop. */
    private suspend fun syncVisit(address: String): VisitResult {
        val deviceId = address.lowercase().filter { it in "0123456789abcdef" }
        var r: VisitResult
        try {
            // Anything a previous visit spooled but did not convert.
            withContext(audioThread) { drainSpools(deviceId) }
            val device = getSystemService(BluetoothManager::class.java).adapter.getRemoteDevice(address)
            CaptureRepository.update { it.copy(link = Link.SYNCING, sync = SyncStatus(0, 0, 0.0, "connecting")) }
            CaptureRepository.log("sync: connecting to $address")
            val conn = OmiConnection(this@CaptureService, device, onAudio = {})
            connection = conn
            conn.connect()
            val info = DeviceInfo(address = address, model = readText(conn, OmiUuids.MODEL),
                firmware = readText(conn, OmiUuids.FIRMWARE), mtu = conn.mtu)
            CaptureRepository.update { it.copy(device = info) }
            readVolatile(conn)
            syncClock(conn)
            CaptureRepository.state.value.rssi?.let { CaptureRepository.log("sync: link ${it.value} dBm") }
            conn.preferThroughput()
            delay(500)
            val outcome = net.boswell.phone.sync.OmiSync(spoolDir(this@CaptureService), deviceId).visit(conn, VISIT_SECONDS,
                onRing = { ring -> CaptureRepository.update { it.copy(ring = Reading(ring, System.currentTimeMillis())) } },
                onProgress = { p ->
                    CaptureRepository.update { it.copy(sync = SyncStatus(p.took, p.target, p.bytesPerSecond, "downloading")) }
                    notify("Syncing · ${p.took * 100 / p.target.coerceAtLeast(1)}%")
                })
            conn.close()
            connection = null
            CaptureRepository.update { it.copy(sync = SyncStatus(outcome.took, outcome.waiting, 0.0, "making clips")) }
            val clips = withContext(audioThread) { drainSpools(deviceId) }
            val text = when {
                outcome.waiting == 0L -> "nothing waiting"
                else -> "${outcome.took} of ${outcome.waiting} packets → $clips clips" + (outcome.stoppedEarly?.let { " ($it)" } ?: "")
            }
            CaptureRepository.log("sync: $text")
            if (clips > 0) net.boswell.phone.process.ProcessingWorker.enqueue(this@CaptureService)
            r = VisitResult(text, failed = outcome.stoppedEarly != null && outcome.took == 0L,
                emptied = outcome.waiting == 0L || outcome.took >= outcome.waiting)
        } catch (e: CancellationException) {
            if (!currentCoroutineContext().isActive) throw e
            r = VisitResult("could not reach the Omi (${e.message})", failed = true, emptied = false)
            CaptureRepository.log("sync: ${r.text}")
        } catch (e: Exception) {
            r = VisitResult("could not reach the Omi (${e.message})", failed = true, emptied = false)
            CaptureRepository.log("sync: ${r.text}")
        } finally {
            connection?.close()
            connection = null
        }
        CaptureRepository.update { it.copy(link = Link.IDLE, sync = null) }
        return r
    }

    /**
     * Set the Omi's clock from the phone's, on every connection. Without a
     * valid clock the Omi's firmware stores nothing at all (sd_card.c: no
     * rtc_is_valid(), no write), and switching it off and on can lose it --
     * so a Live-only user who walked out of range would lose that audio.
     */
    private suspend fun syncClock(conn: OmiConnection) {
        val skew = CaptureRepository.state.value.deviceClockSkewSeconds?.value
        if (skew != null && kotlin.math.abs(skew) <= 2) return
        runCatching { conn.setClock(System.currentTimeMillis() / 1000) }
            .onSuccess { CaptureRepository.log(if (skew == null || skew < -1_000_000_000) "set the Omi's clock (it had none)" else "set the Omi's clock (was off by ${skew}s)") }
            .onFailure { CaptureRepository.log("could not set the Omi's clock: ${it.message}") }
    }

    // ------------------------------------------------------ charger drain

    /**
     * In Live mode the Omi only stores what it hears while out of range, and
     * reading that backlog can't share a connection with the live stream. A
     * charging Omi isn't being worn, so that's when the backlog is fetched:
     * once each time it goes on the charger, then back to live.
     */
    @Volatile private var drainedThisCharge = false
    /** After a drain that couldn't finish (a weak link), when to try again during the same charge. */
    @Volatile private var drainRetryAt = 0L

    private class OnCharger : Exception("on the charger")

    private fun checkCharger() {
        val charging = CaptureRepository.state.value.charging?.value ?: return
        if (!charging) { drainedThisCharge = false; drainRetryAt = 0L; return }
        if (!drainedThisCharge && System.currentTimeMillis() >= drainRetryAt &&
            net.boswell.phone.sync.Modes.syncOnCharger(this)) throw OnCharger()
    }

    private suspend fun drainOnCharger(address: String) {
        CaptureRepository.log("on the charger: collecting what the Omi stored")
        notify("On the charger · collecting stored audio")
        // Failures in a row, not in total: a weak link that drops now and then
        // still makes progress between drops.
        var fails = 0
        var emptied = false
        while (currentCoroutineContext().isActive) {
            val r = syncVisit(address)
            if (r.emptied) { emptied = true; break }
            if (r.failed) { if (++fails >= 3) break; delay(5_000L * fails); continue }
            fails = 0
        }
        if (emptied) drainedThisCharge = true
        else {
            drainRetryAt = System.currentTimeMillis() + DRAIN_RETRY_MS
            CaptureRepository.log("on the charger: couldn't finish; trying again in ${DRAIN_RETRY_MS / 60_000} min")
        }
        net.boswell.phone.sync.Modes.recordSync(this, "on the charger")
    }

    /** Convert every spool file into clips. Returns how many clips were made. */
    private fun drainSpools(deviceId: String): Int {
        val dir = spoolDir(this)
        val drainer = net.boswell.phone.sync.SpoolDrainer(clipsDir(this), deviceId)
        var n = 0
        for (f in dir.listFiles { x -> x.extension == "raw" }.orEmpty().sortedBy { it.name }) {
            val r = drainer.drain(f, File(dir, "kept"))
            n += r.clips.size
            if (r.kept) CaptureRepository.log("spool ${f.name}: ${r.bad} frame(s) would not decode; kept the raw file")
        }
        return n
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
        const val ACTION_SYNC = "net.boswell.phone.SYNC"
        const val EXTRA_ADDRESS = "address"
        private const val CHANNEL = "capture"
        private const val NOTIFICATION_ID = 1
        /** The mic sleeps after ~3 s of quiet (OMI_VAD_HOLD_MS); a gap longer than that is a pause. */
        private const val PAUSE_CLOSES_CLIP_MS = 4_000L
        private const val LINK_CHECK_SECONDS = 30
        private const val DRAIN_RETRY_MS = 5 * 60_000L
        private const val LISTENING_ID = 77
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

        /** One sync visit: download the backlog, then let the device go. */
        fun sync(context: Context, address: String) =
            ContextCompat.startForegroundService(
                context,
                Intent(context, CaptureService::class.java).setAction(ACTION_SYNC).putExtra(EXTRA_ADDRESS, address),
            )

        fun spoolDir(context: Context) = File(context.filesDir, "omi_spool").apply { mkdirs() }

        /** Visits are bounded: on a weak link a full drain can take many hours, and the radio is exclusive meanwhile. */
        private const val VISIT_SECONDS = 10 * 60L
    }
}
