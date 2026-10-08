package net.boswell.phone.assistant

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import androidx.core.app.NotificationCompat
import net.boswell.phone.R
import net.boswell.phone.capture.logged
import net.boswell.phone.ui.MainActivity

/**
 * Timers and alarms the assistant sets ("set a timer for 10 minutes").
 * Android's alarm clock, so they fire on time with the phone asleep; when
 * they do, the phone rings (alarm sound, on the alarm volume) and the Omi
 * buzzes. Kept by the system, not the app, so they survive the app stopping.
 */
object Alarms {
    const val CHANNEL = "alarms"

    fun schedule(c: Context, atEpoch: Double, label: String, timer: Boolean) {
        val id = (atEpoch.toLong() % 1_000_000).toInt()
        val fire = PendingIntent.getBroadcast(c, id, Intent(c, AlarmReceiver::class.java)
            .putExtra("label", label).putExtra("timer", timer).putExtra("id", id), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val show = PendingIntent.getActivity(c, id, Intent(c, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val am = c.getSystemService(AlarmManager::class.java)
        val ms = (atEpoch * 1000).toLong()
        if (am.canScheduleExactAlarms()) am.setAlarmClock(AlarmManager.AlarmClockInfo(ms, show), fire)
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, ms, fire)
    }

    fun ensureChannel(c: Context) {
        val nm = c.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) != null) return
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Timers and alarms", NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            enableVibration(true)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
        })
    }
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Alarms.ensureChannel(context)
        val label = intent.getStringExtra("label") ?: "Timer"
        val timer = intent.getBooleanExtra("timer", true)
        val n = NotificationCompat.Builder(context, Alarms.CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_mic)
            .setContentTitle(if (timer) "Timer done" else "Alarm")
            .setContentText(label)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .build().apply { flags = flags or android.app.Notification.FLAG_INSISTENT }   // rings until seen
        runCatching { context.getSystemService(NotificationManager::class.java).notify(intent.getIntExtra("id", 77), n) }.logged("posting an alarm")
        net.boswell.phone.capture.CaptureService.buzz(context, 3)
    }
}
