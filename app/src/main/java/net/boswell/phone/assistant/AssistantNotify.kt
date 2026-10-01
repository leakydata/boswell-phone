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
            // Readable on the lock screen: you asked, so you want to see it there.
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
        })
        nm.createNotificationChannel(NotificationChannel(SUGGESTIONS, "Suggestions", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Hints from the assistant listening in live mode"
        })
        nm.createNotificationChannel(NotificationChannel(LISTENING, "Listening for a question", NotificationManager.IMPORTANCE_LOW))
    }

    fun post(c: Context, channel: String, title: String, rawText: String, id: Int = (System.currentTimeMillis() % 100_000).toInt() + 1000) {
        ensureChannels(c)
        val text = Moments.strip(rawText)
        val open = PendingIntent.getActivity(c, 0, Intent(c, MainActivity::class.java).putExtra("open", "ask"), PendingIntent.FLAG_IMMUTABLE)
        // The first quoted moment, a tap away.
        val moment = Moments.ids(rawText).firstOrNull()?.let { runCatching { Moments.resolve(c, it) }.getOrNull() }
        val b = NotificationCompat.Builder(c, channel)
            .setSmallIcon(R.drawable.ic_stat_mic)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setVisibility(if (channel == ANSWERS) NotificationCompat.VISIBILITY_PUBLIC else NotificationCompat.VISIBILITY_PRIVATE)
            .setAutoCancel(true)
        moment?.let { w ->
            b.addAction(0, "Hear it", PendingIntent.getActivity(c, id, Intent(c, MainActivity::class.java)
                .putExtra("open", Moments.openExtra(w)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        }
        val n = b.build()
        runCatching { c.getSystemService(NotificationManager::class.java).notify(id, n) }
        if (channel != LISTENING && AssistantPrefs.voice(c)) speak(c, text)
    }

    fun cancel(c: Context, id: Int) = c.getSystemService(NotificationManager::class.java).cancel(id)

    fun speak(c: Context, text: String, voiceName: String? = AssistantPrefs.ttsVoice(c)) {
        fun go(t: TextToSpeech) {
            val v = voiceName?.let { n -> t.voices?.firstOrNull { it.name == n } }
            if (v != null) t.voice = v else t.language = Locale.getDefault()
            t.speak(text, TextToSpeech.QUEUE_FLUSH, null, "boswell")
        }
        val t = tts
        if (t != null && ttsReady) { go(t); return }
        tts = TextToSpeech(c.applicationContext) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) tts?.let(::go)
        }
    }

    data class VoiceOption(val name: String, val label: String, val offline: Boolean)

    /**
     * The system's voices for the phone's language. Android doesn't say which
     * voice is male or female; Google's own voices carry it in their codes, so
     * those are labeled, and everything can be previewed.
     */
    fun voices(c: Context, done: (List<VoiceOption>) -> Unit) {
        fun list(t: TextToSpeech): List<VoiceOption> {
            val lang = Locale.getDefault()
            return t.voices.orEmpty().filter { it.locale.language == lang.language && !it.features.contains("notInstalled") }
                .sortedWith(compareBy({ it.isNetworkConnectionRequired }, { it.locale.country != lang.country }, { it.name }))
                .mapIndexed { i, v ->
                    val code = Regex("-x-([a-z]{3})").find(v.name)?.groupValues?.get(1)
                    val gender = when (code) { "iol", "iom", "tpd" -> "male"; null -> null; else -> "female" }
                    val region = v.locale.displayCountry.takeIf { it.isNotBlank() && v.locale.country != lang.country }?.let { " · $it" } ?: ""
                    VoiceOption(v.name, "Voice ${i + 1}" + (gender?.let { " · $it" } ?: "") + region, !v.isNetworkConnectionRequired)
                }
        }
        val t = tts
        if (t != null && ttsReady) { done(list(t)); return }
        tts = TextToSpeech(c.applicationContext) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            done(if (ttsReady) tts?.let(::list).orEmpty() else emptyList())
        }
    }
}
