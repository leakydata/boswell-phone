package net.boswell.phone.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import net.boswell.phone.capture.CaptureService
import net.boswell.phone.sync.Mode
import net.boswell.phone.sync.Modes
import net.boswell.phone.sync.SyncWorker

/**
 * Drive the app from adb while the phone is locked (debug builds only):
 *   adb shell am broadcast -a net.boswell.phone.debug.MODE --es mode SYNC -n net.boswell.phone.debug/net.boswell.phone.debug.DebugReceiver
 *   adb shell am broadcast -a net.boswell.phone.debug.SYNC -n net.boswell.phone.debug/net.boswell.phone.debug.DebugReceiver
 */
class DebugReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val address = Modes.address(context) ?: ""
        when (intent.action) {
            "net.boswell.phone.debug.MODE" -> {
                val m = Mode.valueOf(intent.getStringExtra("mode") ?: return)
                Modes.setMode(context, m)
                when (m) {
                    Mode.OFF -> { SyncWorker.cancel(context); CaptureService.stop(context) }
                    Mode.LIVE -> { SyncWorker.cancel(context); CaptureService.start(context, address) }
                    Mode.SYNC -> { CaptureService.stop(context); SyncWorker.schedule(context) }
                }
            }
            "net.boswell.phone.debug.SYNC" -> CaptureService.sync(context, address)
            // am broadcast -a net.boswell.phone.debug.DELETE --es clip omi_123.wav
            "net.boswell.phone.debug.DELETE" -> {
                val clip = intent.getStringExtra("clip") ?: return
                val pending = goAsync()
                Thread { try { net.boswell.phone.process.ClipActions.delete(context, listOf(clip)); android.util.Log.i("Boswell", "debug deleted $clip") } finally { pending.finish() } }.start()
            }
            // am broadcast -a net.boswell.phone.debug.EDIT --es clip omi_1.wav --ef start 0.0 --es text "..."
            "net.boswell.phone.debug.EDIT" -> {
                val clip = intent.getStringExtra("clip") ?: return
                val start = intent.getFloatExtra("start", 0f).toDouble()
                val text = intent.getStringExtra("text") ?: return
                val pending = goAsync()
                Thread { try { net.boswell.phone.process.ClipActions.editLine(context, clip, start, text); android.util.Log.i("Boswell", "debug edited $clip") } finally { pending.finish() } }.start()
            }
            "net.boswell.phone.debug.CAL_REFRESH" -> { net.boswell.phone.todo.Calendar.refresh(context); android.util.Log.i("Boswell", "calendar refresh requested") }
            // am broadcast -a net.boswell.phone.debug.NAME --el id 2 --es name "Nathan Jones"
            "net.boswell.phone.debug.NAME" -> {
                val st = net.boswell.phone.speakers.SpeakerStore(context)
                try { android.util.Log.i("Boswell", "named -> ${st.name(intent.getLongExtra("id", -1), intent.getStringExtra("name") ?: return)}") } finally { st.close() }
            }
            // am broadcast -a net.boswell.phone.debug.BATTERY --ei level 8 --ez charging false
            "net.boswell.phone.debug.BATTERY" ->
                net.boswell.phone.capture.BatteryWatch.omi(context, intent.getIntExtra("level", 50), intent.getBooleanExtra("charging", false))
            // am broadcast -a net.boswell.phone.debug.FEED --es clip omi_1.wav
            // Plays a recorded clip into the voice-enrollment step at real-time pace, standing in for the Omi's stream.
            "net.boswell.phone.debug.FEED" -> {
                val f = java.io.File(net.boswell.phone.capture.CaptureService.clipsDir(context), intent.getStringExtra("clip") ?: return)
                val pending = goAsync()
                Thread {
                    try {
                        val (pcm, _) = net.boswell.phone.audio.Wav.readPcm(f)
                        var i = 0
                        while (i + 320 <= pcm.size) {
                            net.boswell.phone.setup.Enrollment.feed(pcm.copyOfRange(i, i + 320))
                            net.boswell.phone.capture.CaptureRepository.update { it.copy(lastAudioMillis = System.currentTimeMillis()) }
                            i += 320; Thread.sleep(20)
                        }
                        android.util.Log.i("Boswell", "debug feed done: ${net.boswell.phone.setup.Enrollment.state.value}")
                    } finally { pending.finish() }
                }.start()
            }
            // am broadcast -a net.boswell.phone.debug.CALENDAR --el id 16
            "net.boswell.phone.debug.CALENDAR" -> {
                net.boswell.phone.todo.Calendar.choose(context, intent.getLongExtra("id", -1).takeIf { it >= 0 })
                android.util.Log.i("Boswell", "calendar: ${net.boswell.phone.todo.Calendar.chosen(context)?.let { it.name + " / " + it.id } ?: "none"}")
            }
            // am broadcast -a net.boswell.phone.debug.TRIGGERS_ON --ez on true
            "net.boswell.phone.debug.TRIGGERS_ON" -> {
                net.boswell.phone.assistant.Triggers.setEnabled(context, intent.getBooleanExtra("on", true))
                android.util.Log.i("Boswell", "voice triggers ${if (net.boswell.phone.assistant.Triggers.enabled(context)) "on" else "off"}")
            }
            // am broadcast -a net.boswell.phone.debug.TRIGGER --es q "..." [--ez force false]  (as a line said by "Me", right now)
            "net.boswell.phone.debug.TRIGGER" -> {
                val q = intent.getStringExtra("q") ?: return
                val owner = net.boswell.phone.assistant.AssistantPrefs.owner(context)
                val pending = goAsync()
                Thread {
                    try {
                        val t = net.boswell.phone.process.Transcript(
                            clip = "debug_${System.currentTimeMillis()}.wav", created = System.currentTimeMillis() / 1000.0,
                            segments = listOf(net.boswell.phone.process.Segment(0.0, 3.0, "SPEAKER_00", q)),
                            speakers = mapOf("SPEAKER_00" to net.boswell.phone.process.SpeakerId(null, 0.9, "matched", 0.5, emptyList(), owner, 3.0)),
                            embeddings = emptyMap(), engine = "debug", processMs = 0)
                        net.boswell.phone.assistant.TriggerEngine(context).run(t, System.currentTimeMillis() / 1000.0, force = intent.getBooleanExtra("force", true))
                        android.util.Log.i("Boswell", "debug trigger done")
                    } finally { pending.finish() }
                }.start()
            }
            // am broadcast -a net.boswell.phone.debug.ASK --es q "..." [--es source capture]
            "net.boswell.phone.debug.ASK" -> {
                val q = intent.getStringExtra("q") ?: return
                val source = intent.getStringExtra("source") ?: "typed"
                val pending = goAsync()
                Thread {
                    try {
                        val a = net.boswell.phone.assistant.Assistant(context).ask(q, source)
                        net.boswell.phone.assistant.AssistantNotify.post(context, net.boswell.phone.assistant.AssistantNotify.ANSWERS,
                            if (source == "capture") "To-do" else q.take(60), a.text)
                        android.util.Log.i("Boswell", "debug ask -> ${a.text} (${a.cost})")
                    } finally { pending.finish() }
                }.start()
            }
        }
    }
}
