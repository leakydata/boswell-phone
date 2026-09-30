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
