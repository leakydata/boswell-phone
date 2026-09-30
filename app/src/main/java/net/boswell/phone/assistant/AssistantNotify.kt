package net.boswell.phone.assistant

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.speech.tts.TextToSpeech
import androidx.core.app.NotificationCompat
import net.boswell.phone.R
import net.boswell.phone.ui.MainActivity
import java.util.Locale

/**
 * How the assistant reaches you: a notification, and -- only if switched on --
 * the answer read aloud through whatever the phone is playing to.
 */
object AssistantNotify {
    /**
     * High importance, so an answer you are waiting for appears as a banner.
     * A channel's importance is fixed once created, hence the new id; the
     * first version ("answers", default importance) is deleted.
     */
    const val ANSWERS = "answers_v2"
    const val SUGGESTIONS = "suggestions"
    const val LISTENING = "listening"

    private var tts: TextToSpeech? = null
    private var ttsReady = false

    fun ensureChannels(c: Context) {
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.deleteNotificationChannel("answers")
        nm.createNotificationChannel(NotificationChannel(ANSWERS, "Answers", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Answers to questions you ask, and reminders"
        })
        nm.createNotificationChannel(NotificationChannel(SUGGESTIONS, "Suggestions", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Hints from the assistant listening in live mode"
        })
        nm.createNotificationChannel(NotificationChannel(LISTENING, "Listening for a question", NotificationManager.IMPORTANCE_LOW))
    }

    fun post(c: Context, channel: String, title: String, text: String, id: Int = (System.currentTimeMillis() % 100_000).toInt() + 1000) {
        ensureChannels(c)
        val open = PendingIntent.getActivity(c, 0, Intent(c, MainActivity::class.java).putExtra("open", "ask"), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(c, channel)
            .setSmallIcon(R.drawable.ic_stat_mic)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { c.getSystemService(NotificationManager::class.java).notify(id, n) }
        if (channel != LISTENING && AssistantPrefs.voice(c)) speak(c, text)
    }

    fun cancel(c: Context, id: Int) = c.getSystemService(NotificationManager::class.java).cancel(id)

    private fun speak(c: Context, text: String) {
        val t = tts
        if (t != null && ttsReady) { t.speak(text, TextToSpeech.QUEUE_ADD, null, "boswell"); return }
        tts = TextToSpeech(c.applicationContext) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) {
                tts?.language = Locale.getDefault()
                tts?.speak(text, TextToSpeech.QUEUE_ADD, null, "boswell")
            }
        }
    }
}
