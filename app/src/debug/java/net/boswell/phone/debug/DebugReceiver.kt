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
