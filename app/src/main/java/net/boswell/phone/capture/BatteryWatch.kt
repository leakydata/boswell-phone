package net.boswell.phone.capture

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import net.boswell.phone.R
import net.boswell.phone.assistant.AssistantNotify
import net.boswell.phone.sync.Mode
import net.boswell.phone.sync.Modes
import net.boswell.phone.sync.SyncWorker

/**
 * Warnings before a battery runs out. A flat Omi was the desktop's most
 * common cause of lost hours -- and a dead recorder looks exactly like one
 * out of range -- so running low is said out loud, once per drop.
 *
 * In live mode the phone streams continuously, which costs both batteries
 * most; when either is very low the warning offers Sync mode, where the Omi
 * just records to its own memory until it's collected.
 */
object BatteryWatch {
    const val LOW = 20
    const val VERY_LOW = 10
    const val PHONE_LOW = 15
    private const val ID_OMI = 90
    private const val ID_PHONE = 91

    private fun p(c: Context) = c.getSharedPreferences("boswell", Context.MODE_PRIVATE)

    /** Called with each Omi battery reading. */
    fun omi(c: Context, level: Int, charging: Boolean?) {
        val band = when { charging == true -> 0; level <= VERY_LOW -> 2; level <= LOW -> 1; else -> 0 }
        val last = p(c).getInt("omi_battery_band", 0)
        // Recovering (charging, or clearly back above the bar) re-arms the warnings.
        if (band == 0 && (charging == true || level > LOW + 5)) { p(c).edit().putInt("omi_battery_band", 0).apply(); return }
        if (band <= last) return
        p(c).edit().putInt("omi_battery_band", band).apply()
        val live = Modes.mode(c) == Mode.LIVE
        if (band == 2) post(c, ID_OMI, "Omi battery very low ($level%)",
            if (live) "Charge it soon. Live mode uses the most battery; Sync lets the Omi record on its own until it's charged."
            else "Charge it soon, or recording will stop when it runs out.", offerSync = live)
        else post(c, ID_OMI, "Omi battery low ($level%)", "Charge it soon to keep recording.", offerSync = false)
    }

    /** Checked alongside: the phone's own battery, which live mode also leans on. */
    fun phone(c: Context) {
        if (Modes.mode(c) != Mode.LIVE) return
        val bm = c.getSystemService(BatteryManager::class.java) ?: return
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val charging = bm.isCharging
        val warned = p(c).getBoolean("phone_battery_warned", false)
        if (charging || level > PHONE_LOW + 5) { if (warned) p(c).edit().putBoolean("phone_battery_warned", false).apply(); return }
        if (level > PHONE_LOW || warned) return
        p(c).edit().putBoolean("phone_battery_warned", true).apply()
        post(c, ID_PHONE, "Phone battery low ($level%)",
            "Live mode keeps Bluetooth streaming all the time. Sync mode uses much less: the Omi records on its own and the phone collects it later.", offerSync = true)
    }

    private fun post(c: Context, id: Int, title: String, text: String, offerSync: Boolean) {
        AssistantNotify.ensureChannels(c)
        val b = NotificationCompat.Builder(c, AssistantNotify.ANSWERS)
            .setSmallIcon(R.drawable.ic_stat_mic)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
        if (offerSync) b.addAction(0, "Switch to Sync", PendingIntent.getBroadcast(c, id,
            Intent(c, SwitchToSyncReceiver::class.java).putExtra("id", id), PendingIntent.FLAG_IMMUTABLE))
        runCatching { NotificationManagerCompat.from(c).notify(id, b.build()) }.logged("posting a notification")
        CaptureRepository.log(title)
    }
}

/** The "Switch to Sync" button on a battery warning. */
class SwitchToSyncReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Modes.setMode(context, Mode.SYNC)
        CaptureService.stop(context)
        SyncWorker.schedule(context)
        NotificationManagerCompat.from(context).cancel(intent.getIntExtra("id", 0))
        CaptureRepository.log("switched to Sync mode from a battery warning")
    }
}
