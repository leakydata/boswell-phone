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
        if (intent == null) {
            // Restarted by Android after the process died (a crash, or memory
            // reclaimed): carry on in whatever mode was chosen. Without this a
            // crash ended Live mode silently and it stayed off.
            val address = net.boswell.phone.sync.Modes.address(this)
            if (net.boswell.phone.sync.Modes.mode(this) == net.boswell.phone.sync.Mode.LIVE && address != null) {
                CaptureRepository.log("restarted after the app was stopped")
                if (!goForeground("Connecting to Omi…")) return START_NOT_STICKY
                startCapture(address)
                return START_STICKY
            }
            stopSelf()
            return START_NOT_STICKY
        }
        when (intent.action) {
            ACTION_START -> {
                val address = intent.getStringExtra(EXTRA_ADDRESS) ?: return START_NOT_STICKY
                if (!goForeground("Connecting to Omi…")) return START_NOT_STICKY
                startCapture(address)
                return START_STICKY       // live: come back if the process dies
            }
            ACTION_STOP -> lifecycleScope.launch { stopCapture("stopped by user"); stopSelf() }
            ACTION_LED -> {
                lifecycleScope.launch { connection?.let { applyLed(it) } }
                return if (captureJob?.isActive == true) START_STICKY else START_NOT_STICKY
            }
            ACTION_BUZZ -> {
                buzz(intent.getIntExtra("level", 3))
                return if (captureJob?.isActive == true) START_STICKY else START_NOT_STICKY
            }
            ACTION_SYNC -> {
                val address = intent.getStringExtra(EXTRA_ADDRESS) ?: return START_NOT_STICKY
                if (!goForeground("Syncing with Omi…")) return START_NOT_STICKY
                startSync(address)
            }
        }
        return START_NOT_STICKY
    }

    /** False (and the service stops) when Android won't allow it: Bluetooth permission not granted yet. */
    private fun goForeground(text: String): Boolean {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Recording", NotificationManager.IMPORTANCE_LOW)
        )
        return try {
            startForeground(NOTIFICATION_ID, notification(text), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            true
        } catch (e: SecurityException) {
            CaptureRepository.log("can't connect to the Omi yet: Bluetooth permission not granted")
            stopSelf()
            false
        }
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
            // After a direct attempt fails, wait for the Omi to come back instead:
            // Android's auto-connect watches for it in the controller at almost no
            // cost and connects the moment it advertises. Spaced-out retries (up to
            // 2 min, each with a 30 s timeout) once left someone back in range for
            // three minutes before it noticed.
            var waitForReturn = false
            while (isActive) {
                streamingSince = null
                var drain = false
                val attemptStarted = System.currentTimeMillis()
                try {
                    session(address, waitForReturn)
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
                val streamedFor = streamingSince?.let { System.currentTimeMillis() - it } ?: 0L
                val lasted = System.currentTimeMillis() - attemptStarted
                if (streamedFor > 60_000) {
                    // It was working: one quick direct try, then wait for it.
                    backoffMs = BACKOFF_MIN_MS; waitForReturn = false
                } else {
                    // Alternate: wait with auto-connect, then one direct try, then wait again.
                    // Auto-connect alone once sat for over ten minutes while the Omi
                    // advertised right beside the phone (2026-10-02); a direct try gets it.
                    waitForReturn = !waitForReturn
                    // Spaced retries only when attempts fail at once (Bluetooth off,
                    // the stack refusing): auto-connect does the waiting otherwise.
                    backoffMs = if (lasted < 3_000) (backoffMs * 2).coerceAtMost(BACKOFF_MAX_MS) else 2_000L
                }
                CaptureRepository.update { it.copy(link = Link.AWAY, nextRetryMillis = null) }
                notify(if (waitForReturn) "Omi away — connects as soon as it's back in range" else "Omi away — reconnecting")
                delay(backoffMs)
            }
        }
    }

    /**
     * The Omi by address, always as a random LE address (it advertises a static random
     * one). getRemoteDevice lets Android guess the type from whatever it saw last, and once
     * it has the address down as public (e.g. after the system pairing screen listed the
     * Omi) every connection times out with status 147 while the Omi advertises right there.
     */
    private fun omiDevice(adapter: android.bluetooth.BluetoothAdapter, address: String) =
        adapter.getRemoteLeDevice(address, android.bluetooth.BluetoothDevice.ADDRESS_TYPE_RANDOM)

    /** One connection, from connect to loss. Always ends by throwing. */
    private suspend fun session(address: String, waitForReturn: Boolean = false) {
        val adapter = getSystemService(BluetoothManager::class.java).adapter
        val device = omiDevice(adapter, address)
        CaptureRepository.update { it.copy(link = Link.CONNECTING, nextRetryMillis = null) }
        CaptureRepository.log("connecting to $address")

        val packets = Channel<ByteArray>(capacity = 2_000)
        val conn = OmiConnection(this, device, onAudio = { data ->
            if (packets.trySend(data).isFailure) CaptureRepository.update { it.copy(dropped = it.dropped + 1) }
        }, onButton = { code -> lifecycleScope.launch { onButton(code) } })
        connection = conn
        if (waitForReturn) {
            CaptureRepository.update { it.copy(link = Link.AWAY) }
            CaptureRepository.log("waiting for the Omi to come back in range")
        }
        // Auto-connect waits (up to 3 min, then a direct try, then it's renewed) and
        // fires when the Omi is back; a direct attempt gives up after 30 s.
        conn.connect(timeoutMs = if (waitForReturn) 3 * 60_000L else 30_000L, autoConnect = waitForReturn)

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
        applyLed(conn)
        checkCharger()

        val decoder = OpusFrameDecoder(OmiUuids.SAMPLE_RATE, frameSamples)
        val run = RunTracker()
        val frameMs = frameSamples * 1000 / OmiUuids.SAMPLE_RATE
        val clip = Clipper(clipsDir(this), info.address.lowercase().filter { it in "0123456789abcdef" },
            clipSeconds = clipSecondsNow(), frameMs = frameMs).also { it.bootId = run.id }
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
                val written = clip.add(run.extended, pcm, p.opus)
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
        val buttonOk = subscribeButton(conn)
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
                clip.clipSeconds = clipSecondsNow()
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
                    if (ok && CaptureRepository.state.value.buttonReady == false && conn.has(OmiUuids.BUTTON)) {
                        if (subscribeButton(conn, tries = 1)) CaptureRepository.update { it.copy(buttonReady = true) }
                    }
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

    /**
     * A button question's words from Parakeet in the cloud, or null to use the
     * phone: no key, turned off, no network, a slow answer (8 s) or an error.
     */
    private fun cloudQuestion(audio: FloatArray): String? {
        if (!net.boswell.phone.assistant.AssistantPrefs.cloudQuestions(this)) return null
        val key = net.boswell.phone.assistant.Secrets.get(this, net.boswell.phone.assistant.Secrets.OPENROUTER) ?: return null
        val f = File.createTempFile("question", ".wav", cacheDir)
        val store = net.boswell.phone.assistant.AssistantStore(this)
        try {
            net.boswell.phone.audio.Wav.write(f, ShortArray(audio.size) { (audio[it] * 32767f).toInt().coerceIn(-32768, 32767).toShort() }, 16_000)
            val engine = net.boswell.phone.asr.CloudAsr.Engine.PARAKEET
            val (words, cost) = net.boswell.phone.asr.CloudAsr.transcribeWords(key, engine, f, timeoutMs = 8_000)
            store.logCall("question", engine.id, net.boswell.phone.assistant.LlmReply(null, emptyList(), kotlinx.serialization.json.JsonObject(emptyMap()), cost, 0, 0))
            return words.joinToString(" ") { it.text }.trim().ifEmpty { null }
        } catch (e: Exception) {
            store.logCall("question", "cloud", null, e.message ?: e.toString())
            CaptureRepository.log("question: cloud unavailable (${e.message}), using the phone")
            return null
        } finally {
            f.delete(); store.close()
        }
    }

    /**
     * 10 s clips when the home server does the work: it answers in under a second,
     * so a sentence shows up about 10 s after it's said instead of up to 30 s.
     * On the phone, 30 s clips are cheaper to process (one model load per clip).
     */
    private fun clipSecondsNow() = if (net.boswell.phone.home.HomeServer.enabled(this)) HOME_CLIP_SECONDS else 30

    /**
     * The LED brightness chosen in the app (Device -> Omi), written to the Omi
     * when it differs from what the Omi reports; the Omi keeps it in its own
     * flash. Then read back, so the app shows what the Omi actually has.
     */
    private suspend fun applyLed(conn: OmiConnection) {
        if (!conn.has(OmiUuids.LED_BRIGHTNESS)) return
        val now = runCatching { conn.read(OmiUuids.LED_BRIGHTNESS)[0].toInt() and 0xff }.getOrNull()
        val want = getSharedPreferences("boswell", MODE_PRIVATE).getInt("led_brightness", -1)
        if (want in 0..100 && want != now) {
            runCatching { conn.write(OmiUuids.LED_BRIGHTNESS, byteArrayOf(want.toByte())) }
                .onSuccess { CaptureRepository.log("set the Omi's light to $want%") }
                .onFailure { CaptureRepository.log("couldn't set the Omi's light: ${it.message}") }
        }
        val after = runCatching { conn.read(OmiUuids.LED_BRIGHTNESS)[0].toInt() and 0xff }.getOrNull() ?: now
        CaptureRepository.update { it.copy(ledBrightness = after) }
        after?.let { CaptureRepository.log("Omi light: ${if (it == 0) "off" else "$it%"}") }
        applyMicGain(conn)
    }

    /**
     * The microphone gain chosen in the app, the same way: the Omi's firmware takes a level
     * 0-8 (0 mutes; 6, its default, is +10 dB; each level above adds 5 dB, up to +20 dB at 8)
     * and keeps it in its own flash.
     */
    private suspend fun applyMicGain(conn: OmiConnection) {
        if (!conn.has(OmiUuids.MIC_GAIN)) return
        val now = runCatching { conn.read(OmiUuids.MIC_GAIN)[0].toInt() and 0xff }.getOrNull()
        val want = getSharedPreferences("boswell", MODE_PRIVATE).getInt("mic_gain", -1)
        if (want in 1..8 && want != now) {
            runCatching { conn.write(OmiUuids.MIC_GAIN, byteArrayOf(want.toByte())) }
                .onSuccess { CaptureRepository.log("set the Omi's microphone gain to $want") }
                .onFailure { CaptureRepository.log("couldn't set the Omi's microphone gain: ${it.message}") }
        }
        val after = runCatching { conn.read(OmiUuids.MIC_GAIN)[0].toInt() and 0xff }.getOrNull() ?: now
        CaptureRepository.update { it.copy(micGain = after) }
        after?.let { CaptureRepository.log("Omi microphone gain: $it of 8") }
    }

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
                if (q != null) q.finishAt = System.currentTimeMillis() + 700   // a second tap ends it, once the last words arrive
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
            // The speech model decides when the question is over (loudness can't
            // tell a finished question from a noisy room): every half second it
            // looks at the last 6 s. Measured on five real questions: all whole,
            // each ended 0.8-1.6 s after the last word.
            val listener = launch(kotlinx.coroutines.Dispatchers.Default) {
                if (!models.isInstalled(net.boswell.phone.models.ModelCatalog.SEGMENTATION) ||
                    !models.isInstalled(net.boswell.phone.models.ModelCatalog.VOICEPRINT)) return@launch
                runCatching {
                    net.boswell.phone.diarize.OrtModels(models.path(net.boswell.phone.models.ModelCatalog.SEGMENTATION, ".onnx"),
                        models.path(net.boswell.phone.models.ModelCatalog.VOICEPRINT, "voiceprint.onnx")).use { m ->
                        val d = m.diarizer()
                        q.modelListening = true
                        while (!q.done) {
                            delay(500)
                            val t = q.tail(6.0)
                            if (t.size >= 8_000) q.heard(d.speechSpans(t), t.size / 16_000.0)
                        }
                    }
                }.onFailure { q.modelListening = false; CaptureRepository.log("question: speech model unavailable (${it.message})") }
            }
            val started = System.currentTimeMillis()
            var longestGap = 0L
            while (!q.done) {
                delay(100)
                val last = CaptureRepository.state.value.lastAudioMillis ?: 0L
                val gap = System.currentTimeMillis() - maxOf(last, started)
                longestGap = maxOf(longestGap, gap)
                if (gap > 2_000) q.idle()
                if (System.currentTimeMillis() - started > 25_000) q.limit()
                q.finishAt?.let { if (System.currentTimeMillis() >= it) q.finish() }
            }
            listener.cancel()
            // The question's span, for the watcher to leave alone: tap to the end of listening.
            val asked = started / 1000.0 to System.currentTimeMillis() / 1000.0
            CaptureRepository.log("question ended by ${q.endedBy ?: "?"}${if (q.modelListening) " (speech model)" else ""} after %.1f s of audio; longest gap in the stream %.1f s".format(q.seconds, longestGap / 1000.0))
            question = null
            CaptureRepository.update { it.copy(asking = "thinking") }
            net.boswell.phone.assistant.AssistantNotify.cancel(this@CaptureService, LISTENING_ID)
            val audio = q.audio()
            // Cloud first when allowed (short questions are where the phone's
            // recognizer slips most); the phone whenever the cloud can't answer.
            val text = cloudQuestion(audio) ?: asr.await().use { it.transcribe(audio) }.joinToString(" ") { it.text }.trim()
            if (text.isEmpty()) {
                buzz(3)
                net.boswell.phone.assistant.AssistantNotify.post(this@CaptureService, net.boswell.phone.assistant.AssistantNotify.ANSWERS, "Didn't catch that", "Tap the Omi and try again.")
            } else {
                CaptureRepository.log(if (capture) "captured: $text" else "asked: $text")
                val source = if (capture) net.boswell.phone.assistant.Assistant.CAPTURE else "button"
                val a = withContext(kotlinx.coroutines.Dispatchers.IO) { net.boswell.phone.assistant.Assistant(this@CaptureService).ask(text, source, asked = asked) }
                buzz(if (a.error) 3 else 2)          // the answer is on the phone
                net.boswell.phone.assistant.AssistantNotify.post(this@CaptureService, net.boswell.phone.assistant.AssistantNotify.ANSWERS,
                    if (capture) "To-do" else text.take(60), a.text)
            }
            CaptureRepository.update { it.copy(asking = null) }
        }
    }

    /**
     * Subscribe to the button, trying again on a weak link (one timeout left the
     * button dead for a whole session). Called again at each link check while
     * it hasn't taken.
     */
    private suspend fun subscribeButton(conn: OmiConnection, tries: Int = 3): Boolean {
        if (!conn.has(OmiUuids.BUTTON)) return false
        repeat(tries) { i ->
            val r = runCatching { conn.enableNotifications(OmiUuids.BUTTON) }
            if (r.isSuccess) { if (i > 0) CaptureRepository.log("button ready (try ${i + 1})"); return true }
            CaptureRepository.log("button: ${r.exceptionOrNull()?.message}")
            delay(500)
        }
        return false
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
            val device = omiDevice(getSystemService(BluetoothManager::class.java).adapter, address)
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
            applyLed(conn)
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

    /** Charging readings in a row: one reading alone never starts a download (a garbled one once did, mid-wear). */
    private var chargingSeen = 0

    private fun checkCharger() {
        val charging = CaptureRepository.state.value.charging?.value ?: return
        if (!charging) { chargingSeen = 0; drainedThisCharge = false; drainRetryAt = 0L; return }
        if (++chargingSeen < 2) return
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
        const val ACTION_BUZZ = "net.boswell.phone.BUZZ"
        const val ACTION_LED = "net.boswell.phone.LED"

        /** Apply the chosen LED brightness and microphone gain now if connected; otherwise at the next connection. */
        fun applyLedNow(context: Context) {
            val l = CaptureRepository.state.value.link
            if (l != Link.STREAMING && l != Link.SYNCING) return
            runCatching { context.startService(Intent(context, CaptureService::class.java).setAction(ACTION_LED)) }
        }

        /** Buzz the Omi if Live capture is connected (a timer going off); nothing otherwise. */
        fun buzz(context: Context, level: Int) {
            if (CaptureRepository.state.value.link != Link.STREAMING) return
            runCatching { context.startService(Intent(context, CaptureService::class.java).setAction(ACTION_BUZZ).putExtra("level", level)) }
        }
        const val EXTRA_ADDRESS = "address"
        private const val CHANNEL = "capture"
        private const val NOTIFICATION_ID = 1
        /** The mic sleeps after ~3 s of quiet (OMI_VAD_HOLD_MS); a gap longer than that is a pause. */
        private const val PAUSE_CLOSES_CLIP_MS = 4_000L
        const val HOME_CLIP_SECONDS = 10
        private const val LINK_CHECK_SECONDS = 30
        private const val DRAIN_RETRY_MS = 5 * 60_000L
        private const val LISTENING_ID = 77
        private const val BACKOFF_MIN_MS = 5_000L
        private const val BACKOFF_MAX_MS = 120_000L

        fun clipsDir(context: Context) = File(context.filesDir, "clips").apply { mkdirs() }

        /** Bluetooth permission, without which Android refuses the connected-device service (e.g. right after a restore). */
        fun allowed(context: Context) = ContextCompat.checkSelfPermission(context, android.Manifest.permission.BLUETOOTH_CONNECT) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

        fun start(context: Context, address: String) {
            if (!allowed(context)) { CaptureRepository.log("not connecting yet: Bluetooth permission not granted"); return }
            ContextCompat.startForegroundService(
                context,
                Intent(context, CaptureService::class.java).setAction(ACTION_START).putExtra(EXTRA_ADDRESS, address),
            )
        }

        fun stop(context: Context) =
            context.startService(Intent(context, CaptureService::class.java).setAction(ACTION_STOP))

        /** One sync visit: download the backlog, then let the device go. */
        fun sync(context: Context, address: String) {
            if (!allowed(context)) { CaptureRepository.log("not syncing yet: Bluetooth permission not granted"); return }
            ContextCompat.startForegroundService(
                context,
                Intent(context, CaptureService::class.java).setAction(ACTION_SYNC).putExtra(EXTRA_ADDRESS, address),
            )
        }

        fun spoolDir(context: Context) = File(context.filesDir, "omi_spool").apply { mkdirs() }

        /** Visits are bounded: on a weak link a full drain can take many hours, and the radio is exclusive meanwhile. */
        private const val VISIT_SECONDS = 10 * 60L
    }
}
