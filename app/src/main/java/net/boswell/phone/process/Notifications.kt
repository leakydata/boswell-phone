package net.boswell.phone.process

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context

object Notifications {
    const val CAPTURE = "capture"
    const val WORK = "work"

    fun ensureChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CAPTURE, "Recording", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(WORK, "Downloads and processing", NotificationManager.IMPORTANCE_LOW))
    }
}
