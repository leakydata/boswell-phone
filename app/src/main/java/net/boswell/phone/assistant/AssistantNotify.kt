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
 * How the assistant reaches you: a notification (on a locked phone, only that
 * something is waiting, unless answers are allowed there), and -- only if switched on --
 * the answer read aloud through whatever the phone is playing to, turning
 * other audio down meanwhile, and never during a call.
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
    /** Its own channel, so fact checks can be silenced without silencing answers. */
    const val FACT_CHECKS = "fact_checks"

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
        nm.createNotificationChannel(NotificationChannel(FACT_CHECKS, "Fact checks", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Claims heard in live mode that turned out false or misleading"
        })
        nm.createNotificationChannel(NotificationChannel(LISTENING, "Listening for a question", NotificationManager.IMPORTANCE_LOW))
    }

    /** [speak]: read aloud too; by default when spoken answers are on (fact checks have their own setting). */
    fun post(c: Context, channel: String, title: String, rawText: String, id: Int = (System.currentTimeMillis() % 100_000).toInt() + 1000,
             speak: Boolean = channel != LISTENING && AssistantPrefs.voice(c)) {
        ensureChannels(c)
        val text = SpeechText.plain(Moments.strip(rawText))
        val open = PendingIntent.getActivity(c, 0, Intent(c, MainActivity::class.java).putExtra("open", "ask"), PendingIntent.FLAG_IMMUTABLE)
        // The first quoted moment, a tap away.
        val moment = Moments.ids(rawText).firstOrNull()?.let { runCatching { Moments.resolve(c, it) }.getOrNull() }
        val b = NotificationCompat.Builder(c, channel)
            .setSmallIcon(R.drawable.ic_stat_mic)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
        val showLocked = AssistantPrefs.answersOnLockScreen(c)
        // Hidden on the lock screen, the phone shows only that something is waiting, whatever its own setting.
        val stub = NotificationCompat.Builder(c, channel)
            .setSmallIcon(R.drawable.ic_stat_mic)
            .setContentTitle("Boswell")
            .setContentText(when (channel) { FACT_CHECKS -> "A fact check"; SUGGESTIONS -> "A suggestion"; else -> "An answer" } + " is waiting. Unlock to read it.")
            .setContentIntent(open)
            .setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        b.setVisibility(if (showLocked) NotificationCompat.VISIBILITY_PUBLIC else NotificationCompat.VISIBILITY_PRIVATE)
        if (!showLocked) b.setPublicVersion(stub.build())
        moment?.let { w ->
            b.addAction(0, "Hear it", PendingIntent.getActivity(c, id, Intent(c, MainActivity::class.java)
                .putExtra("open", Moments.openExtra(w)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        }
        Drafts.takeRecent()?.let { draft ->
            b.addAction(0, "Open draft", PendingIntent.getActivity(c, id + 7, draft, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        }
        val nm = c.getSystemService(NotificationManager::class.java)
        if (!showLocked && channel != LISTENING && locked(c)) {
            // The answer itself replaces the stub once the phone is unlocked, without a second alert.
            hidden[id] = b.setOnlyAlertOnce(true).setSilent(true).build()
            whenUnlocked(c)
            runCatching { nm.notify(id, stub.build()) }
        } else runCatching { nm.notify(id, b.build()) }
        if (speak) this.speak(c, text)
    }

    private val hidden = java.util.concurrent.ConcurrentHashMap<Int, android.app.Notification>()
    @Volatile private var unlockWatch: android.content.BroadcastReceiver? = null

    private fun locked(c: Context) = c.getSystemService(android.app.KeyguardManager::class.java)?.isKeyguardLocked == true

    /** Show what was held back as soon as the person unlocks the phone (if this process is still alive; else tapping opens it). */
    private fun whenUnlocked(c: Context) {
        if (unlockWatch != null) return
        val app = c.applicationContext
        synchronized(this) {
            if (unlockWatch != null) return
            val r = object : android.content.BroadcastReceiver() {
                override fun onReceive(ctx: Context, i: Intent) {
                    val nm = app.getSystemService(NotificationManager::class.java)
                    // Only those still showing: one swiped away stays gone.
                    val showing = nm.activeNotifications.map { it.id }.toSet()
                    for (id in hidden.keys.toList()) hidden.remove(id)?.let { if (id in showing) runCatching { nm.notify(id, it) } }
                }
            }
            androidx.core.content.ContextCompat.registerReceiver(app, r, android.content.IntentFilter(Intent.ACTION_USER_PRESENT),
                androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
            unlockWatch = r
        }
    }

    fun cancel(c: Context, id: Int) = c.getSystemService(NotificationManager::class.java).cancel(id)

    /**
     * Everything Boswell says aloud goes through here -- answers, reminders,
     * the voice preview -- so every utterance is noted (AssistantStore's
     * spoken table): when it started and ended, the exact words, and the
     * voice. The Omi hears the phone too, and that is how a recording
     * transcribed later tells Boswell's voice from a person's (BoswellLines).
     */
    fun speak(c: Context, text: String, voiceName: String? = AssistantPrefs.ttsVoice(c)) {
        val app = c.applicationContext
        // Never into a call (phone or video): the notification is enough then.
        if (inCall(app)) return
        engine(app) { t ->
            if (t == null) return@engine
            val v = voiceName?.let { n -> t.voices?.firstOrNull { it.name == n } }
            if (v != null) t.voice = v else t.language = Locale.getDefault()
            val said = SpeechText.clean(text)
            val id = "boswell-${utterances.incrementAndGet()}"
            pending[id] = said to (t.voice?.name ?: voiceName)
            duck(app)
            if (t.speak(said, TextToSpeech.QUEUE_FLUSH, null, id) != TextToSpeech.SUCCESS) { pending.remove(id); unduck(app) }
        }
    }

    private val SPEECH by lazy { android.media.AudioAttributes.Builder()
        .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH).build() }

    /**
     * What else is playing (a video, music) is turned down while Boswell
     * speaks, the way directions are spoken over music, and comes back when
     * it's done: what it says is short, and was easy to miss under a video.
     */
    private val focus by lazy { android.media.AudioFocusRequest.Builder(android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(SPEECH).build() }

    private fun duck(app: Context) = runCatching { app.getSystemService(android.media.AudioManager::class.java).requestAudioFocus(focus) }

    private fun unduck(app: Context) = runCatching { app.getSystemService(android.media.AudioManager::class.java).abandonAudioFocusRequest(focus) }

    /** On a phone or video call, or the phone ringing. */
    private fun inCall(app: Context): Boolean = app.getSystemService(android.media.AudioManager::class.java).mode.let {
        it == android.media.AudioManager.MODE_IN_CALL || it == android.media.AudioManager.MODE_IN_COMMUNICATION ||
            it == android.media.AudioManager.MODE_RINGTONE || it == android.media.AudioManager.MODE_CALL_SCREENING
    }

    /** Spoken (or just finished, within BoswellLines.AFTER) right now: the Omi may be hearing Boswell. */
    fun speakingNow(): Boolean = System.currentTimeMillis() < speakingUntil + (net.boswell.phone.process.BoswellLines.AFTER * 1000).toLong()

    private val utterances = java.util.concurrent.atomic.AtomicLong()
    /** Utterance id -> (the words, the voice), until it starts. */
    private val pending = java.util.concurrent.ConcurrentHashMap<String, Pair<String, String?>>()
    /** Utterance id -> its row in the spoken table, while it plays. */
    private val rows = java.util.concurrent.ConcurrentHashMap<String, Long>()
    @Volatile private var speakingUntil = 0L

    private fun now() = System.currentTimeMillis() / 1000.0

    /**
     * Notes each utterance as it plays. The row is written when it starts,
     * with a guess at its end (about 0.45 s a word), so an answer is on record
     * even if the app dies mid-sentence; the real end replaces the guess.
     * A flushed utterance ends where it was cut off.
     */
    private fun listener(app: Context) = object : android.speech.tts.UtteranceProgressListener() {
        override fun onStart(id: String) {
            val (said, voice) = pending.remove(id) ?: return
            val at = now()
            val guess = at + said.split(' ').size * 0.45 + 1.0
            speakingUntil = (guess * 1000).toLong()
            runCatching { AssistantStore(app).use { it.addSpoken(at, guess, said, voice) } }.onSuccess { rows[id] = it }
        }
        override fun onDone(id: String) = ended(id)
        override fun onStop(id: String, interrupted: Boolean) = ended(id)
        @Deprecated("Deprecated in Java") override fun onError(id: String) = ended(id)
        private fun ended(id: String) {
            pending.remove(id)
            speakingUntil = System.currentTimeMillis()
            val row = rows.remove(id)
            // Unless a newer utterance cut this one off: that one keeps the other audio down.
            if (pending.isEmpty() && rows.isEmpty()) unduck(app)
            row ?: return
            runCatching { AssistantStore(app).use { it.endSpoken(row, now()) } }
        }
    }

    /** The one TextToSpeech, made once (with the listener that notes what it says); null if it couldn't start. */
    private fun engine(app: Context, use: (TextToSpeech?) -> Unit) {
        val t = tts
        if (t != null && ttsReady) { use(t); return }
        tts = TextToSpeech(app) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            tts?.takeIf { ttsReady }?.let { it.setOnUtteranceProgressListener(listener(app)); it.setAudioAttributes(SPEECH) }
            use(tts?.takeIf { ttsReady })
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
        engine(c.applicationContext) { t -> done(t?.let(::list).orEmpty()) }
    }
}
