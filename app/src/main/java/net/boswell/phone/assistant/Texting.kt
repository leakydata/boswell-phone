package net.boswell.phone.assistant

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.telephony.SmsManager
import androidx.core.app.NotificationCompat
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import net.boswell.phone.R
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** A contact the assistant may read texts from and text (after confirmation). */
@Serializable
data class TextContact(val name: String, val number: String,
                       /** ask: hold for Send / yes. auto: send right away when they ask, after a 10 s chance to cancel. */
                       val mode: String = "ask")

/**
 * Texting, within limits the code enforces (not just the model's
 * instructions):
 *
 *  - only contacts the person chose in Device -> Assistant -> Texting;
 *  - reading is texts to and from those contacts;
 *  - sending is never automatic: a text is held and shown as a
 *    notification with Send, and goes only when they tap Send or answer
 *    "yes, send it" within two minutes -- checked against their own words,
 *    so the model can't confirm on its own.
 */
object Texting {
    private val json = Json { ignoreUnknownKeys = true }
    private fun prefs(c: Context) = c.getSharedPreferences("boswell", Context.MODE_PRIVATE)

    fun enabled(c: Context) = prefs(c).getBoolean("texting", false)
    fun setEnabled(c: Context, on: Boolean) = prefs(c).edit().putBoolean("texting", on).apply()

    fun contacts(c: Context): List<TextContact> = prefs(c).getString("texting_contacts", null)
        ?.let { runCatching { json.decodeFromString(ListSerializer(TextContact.serializer()), it) }.getOrNull() } ?: emptyList()

    fun setContacts(c: Context, list: List<TextContact>) = prefs(c).edit()
        .putString("texting_contacts", json.encodeToString(ListSerializer(TextContact.serializer()), list.distinctBy { digits(it.number) })).apply()

    /** Everyone who may be texted: the hand-made list plus people linked to a contact and allowed to be texted. */
    fun all(c: Context): List<TextContact> = (contacts(c) + runCatching { Contacts.textable(c) }.getOrDefault(emptyList())).distinctBy { digits(it.number) }

    fun canRead(c: Context) = c.checkSelfPermission(Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED
    fun canSend(c: Context) = c.checkSelfPermission(Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED

    /** The last ten digits: how numbers are compared, whatever their formatting or country code. */
    fun digits(n: String) = n.filter { it.isDigit() }.takeLast(10)

    /** A chosen contact by name (any part of it) or number. */
    fun find(c: Context, who: String): TextContact? {
        val list = all(c)
        val d = digits(who)
        if (d.length >= 7) list.firstOrNull { digits(it.number) == d }?.let { return it }
        val w = who.trim().lowercase()
        return list.firstOrNull { it.name.lowercase() == w }
            ?: list.firstOrNull { c2 -> c2.name.lowercase().split(" ").any { it == w } || w.split(" ").all { part -> c2.name.lowercase().contains(part) } }
    }

    // ---------------------------------------------------------------- read

    /** Texts with chosen contacts: those of the last [hours], or without hours the latest [latest] whenever they were. */
    fun read(c: Context, who: String?, hours: Int?, latest: Int = 20): String {
        if (!enabled(c)) return "texting is off (Device -> Assistant -> Texting)"
        if (!canRead(c)) return "Boswell isn't allowed to read texts yet (Device -> Assistant -> Texting)"
        val allowed = if (who != null) listOfNotNull(find(c, who)) else all(c)
        if (allowed.isEmpty()) return if (who != null) "$who isn't one of the contacts chosen for texting" else "no contacts are chosen for texting"
        val byDigits = allowed.associateBy { digits(it.number) }
        val since = hours?.let { System.currentTimeMillis() - it * 3_600_000L } ?: 0L
        val cap = if (hours == null) latest else 60
        val t = DateTimeFormatter.ofPattern("MMM d, h:mm a").withZone(ZoneId.systemDefault())
        val out = mutableListOf<Pair<Long, String>>()
        c.contentResolver.query(Uri.parse("content://sms"), arrayOf("address", "body", "date", "type"), "date >= ?", arrayOf(since.toString()), "date DESC")?.use { cur ->
            while (cur.moveToNext() && out.size < cap) {
                val who2 = byDigits[digits(cur.getString(0) ?: continue)] ?: continue
                val mine = cur.getInt(3) == 2        // 2 = sent
                val at = cur.getLong(2)
                out += at to "${t.format(Instant.ofEpochMilli(at))} ${if (mine) "me -> ${who2.name}" else "${who2.name} -> me"}: ${cur.getString(1)}"
            }
        }
        return out.sortedBy { it.first }.joinToString("\n") { it.second }
            .ifBlank { "no texts with ${allowed.joinToString { it.name }}" + (hours?.let { " in the last $it hours" } ?: "") }
    }

    // ---------------------------------------------------------------- send

    private data class Pending(val to: TextContact, val text: String, val at: Long, val id: Int)
    @Volatile private var pending: Pending? = null

    /**
     * Text a chosen contact. Normally held for Send / yes; for a contact set
     * to "send right away", and only when the person asked directly ([direct]:
     * their own typed or spoken request, never a routine), it goes after a
     * 10-second chance to cancel.
     */
    fun prepare(c: Context, who: String, text: String, direct: Boolean = false): String {
        if (!enabled(c)) return "texting is off (Device -> Assistant -> Texting)"
        val to = find(c, who) ?: return "$who isn't one of the contacts chosen for texting, so I can't text them; I can make a draft instead (draft_message)"
        if (!canSend(c)) return "Boswell isn't allowed to send texts yet (Device -> Assistant -> Texting); I can make a draft instead"
        if (to.mode == "auto" && direct) return AutoSend.start(c, to, text.trim())
        val id = (System.currentTimeMillis() % 100_000).toInt() + 500_000
        pending = Pending(to, text.trim(), System.currentTimeMillis(), id)
        AssistantNotify.ensureChannels(c)
        val send = PendingIntent.getBroadcast(c, id, Intent(c, TextSendReceiver::class.java).putExtra("id", id), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val edit = PendingIntent.getActivity(c, id + 1, Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${to.number}")).putExtra("sms_body", text)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(c, AssistantNotify.ANSWERS)
            .setSmallIcon(R.drawable.ic_stat_mic)
            .setContentTitle("Send to ${to.name}?")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText("“$text”\n${to.number}"))
            .addAction(0, "Send", send)
            .addAction(0, "Edit", edit)
            .setTimeoutAfter(CONFIRM_MS)
            .setAutoCancel(true)
            .build()
        runCatching { c.getSystemService(NotificationManager::class.java).notify(id, n) }
        return "held for confirmation: “$text” to ${to.name} (${to.number}). It is NOT sent. Ask them to confirm: they can tap Send on the notification, or say yes within two minutes."
    }

    /**
     * Send the held text -- only if [userWords] (the person's own latest
     * words, not the model's) confirm it, within two minutes of holding it.
     */
    fun confirm(c: Context, userWords: String?): String {
        val p = pending ?: return "there is no text waiting to be sent"
        if (System.currentTimeMillis() - p.at > CONFIRM_MS) { pending = null; return "the held text expired; ask again to send it" }
        if (!isConfirmation(userWords)) return "not sent: they haven't confirmed in their own words. Ask them to say yes or tap Send."
        return send(c, p)
    }

    /** Send now, no questions (the auto-send countdown has run out). */
    internal fun sendNow(c: Context, to: TextContact, text: String): String = send(c, Pending(to, text, System.currentTimeMillis(), -1))

    internal fun sendPending(c: Context, id: Int): String {
        val p = pending?.takeIf { it.id == id } ?: return "nothing to send"
        return send(c, p)
    }

    private fun send(c: Context, p: Pending): String {
        if (!canSend(c)) return "not allowed to send texts"
        return runCatching {
            val sms = c.getSystemService(SmsManager::class.java)
            val parts = sms.divideMessage(p.text)
            if (parts.size > 1) sms.sendMultipartTextMessage(p.to.number, null, parts, null, null)
            else sms.sendTextMessage(p.to.number, null, p.text, null, null)
            if (p.id >= 0) { pending = null; c.getSystemService(NotificationManager::class.java).cancel(p.id) }
            net.boswell.phone.capture.CaptureRepository.log("texted ${p.to.name}")
            "sent to ${p.to.name}: “${p.text}”"
        }.getOrElse { "couldn't send: ${it.message}" }
    }

    /** The person's own words say to send it: a yes, and nothing that takes it back. */
    fun isConfirmation(words: String?): Boolean {
        val w = words?.lowercase()?.replace("’", "'").orEmpty()
        return Regex("""\b(yes|yeah|yep|yup|sure|send it|send that|send the text|go ahead|do it|ok send|okay send|confirm(ed)?)\b""").containsMatchIn(w) &&
            !Regex("""\b(no|nope|don't|do not|dont|cancel|wait|stop|hold on|never ?mind|not yet)\b""").containsMatchIn(w)
    }

    const val CONFIRM_MS = 120_000L
}

/** The Send button on a held text. */
class TextSendReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val r = Texting.sendPending(context, intent.getIntExtra("id", -1))
        AssistantNotify.post(context, AssistantNotify.ANSWERS, "Text", r.replaceFirstChar { it.uppercase() })
    }
}


/**
 * "Send right away": a text to a trusted contact goes 10 seconds after it's
 * asked for, unless cancelled -- a notification (and a buzz on the Omi) says
 * so, with Cancel. Scheduled as background work so it survives the app
 * closing; Cancel removes the work before it runs.
 */
object AutoSend {
    const val DELAY_S = 10L

    fun start(c: Context, to: TextContact, text: String): String {
        val id = (System.currentTimeMillis() % 100_000).toInt() + 600_000
        val work = androidx.work.OneTimeWorkRequestBuilder<AutoSendWorker>()
            .setInitialDelay(DELAY_S, java.util.concurrent.TimeUnit.SECONDS)
            .setInputData(androidx.work.workDataOf("name" to to.name, "number" to to.number, "text" to text, "id" to id)).build()
        androidx.work.WorkManager.getInstance(c).enqueueUniqueWork("autosend-$id", androidx.work.ExistingWorkPolicy.REPLACE, work)
        AssistantNotify.ensureChannels(c)
        val cancel = PendingIntent.getBroadcast(c, id, Intent(c, AutoSendCancel::class.java).putExtra("id", id), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(c, AssistantNotify.ANSWERS)
            .setSmallIcon(R.drawable.ic_stat_mic)
            .setContentTitle("Sending to ${to.name} in ${DELAY_S}s")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText("\u201c$text\u201d"))
            .addAction(0, "Cancel", cancel)
            .setTimeoutAfter((DELAY_S + 2) * 1000)
            .build()
        runCatching { c.getSystemService(NotificationManager::class.java).notify(id, n) }
        net.boswell.phone.capture.CaptureService.buzz(c, 1)
        return "sending to ${to.name} in $DELAY_S seconds unless they tap Cancel: \u201c$text\u201d (${to.name} is set to send right away)"
    }
}

class AutoSendWorker(context: Context, params: androidx.work.WorkerParameters) : androidx.work.Worker(context, params) {
    override fun doWork(): androidx.work.ListenableWorker.Result {
        val d = inputData
        val to = TextContact(d.getString("name") ?: return androidx.work.ListenableWorker.Result.failure(),
            d.getString("number") ?: return androidx.work.ListenableWorker.Result.failure(), "auto")
        val r = Texting.sendNow(applicationContext, to, d.getString("text") ?: return androidx.work.ListenableWorker.Result.failure())
        applicationContext.getSystemService(NotificationManager::class.java).cancel(d.getInt("id", 0))
        AssistantNotify.post(applicationContext, AssistantNotify.ANSWERS, "Text", r.replaceFirstChar { it.uppercase() })
        return androidx.work.ListenableWorker.Result.success()
    }
}

/** Cancel on a countdown: the text never goes. */
class AutoSendCancel : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getIntExtra("id", 0)
        androidx.work.WorkManager.getInstance(context).cancelUniqueWork("autosend-$id")
        context.getSystemService(NotificationManager::class.java).cancel(id)
        AssistantNotify.post(context, AssistantNotify.ANSWERS, "Text cancelled", "Nothing was sent.")
    }
}
