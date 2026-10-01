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
data class TextContact(val name: String, val number: String)

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

    fun canRead(c: Context) = c.checkSelfPermission(Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED
    fun canSend(c: Context) = c.checkSelfPermission(Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED

    /** The last ten digits: how numbers are compared, whatever their formatting or country code. */
    fun digits(n: String) = n.filter { it.isDigit() }.takeLast(10)

    /** A chosen contact by name (any part of it) or number. */
    fun find(c: Context, who: String): TextContact? {
        val list = contacts(c)
        val d = digits(who)
        if (d.length >= 7) list.firstOrNull { digits(it.number) == d }?.let { return it }
        val w = who.trim().lowercase()
        return list.firstOrNull { it.name.lowercase() == w }
            ?: list.firstOrNull { c2 -> c2.name.lowercase().split(" ").any { it == w } || w.split(" ").all { part -> c2.name.lowercase().contains(part) } }
    }

    // ---------------------------------------------------------------- read

    fun read(c: Context, who: String?, hours: Int): String {
        if (!enabled(c)) return "texting is off (Device -> Assistant -> Texting)"
        if (!canRead(c)) return "Boswell isn't allowed to read texts yet (Device -> Assistant -> Texting)"
        val allowed = if (who != null) listOfNotNull(find(c, who)) else contacts(c)
        if (allowed.isEmpty()) return if (who != null) "$who isn't one of the contacts chosen for texting" else "no contacts are chosen for texting"
        val byDigits = allowed.associateBy { digits(it.number) }
        val since = System.currentTimeMillis() - hours * 3_600_000L
        val t = DateTimeFormatter.ofPattern("EEE h:mm a").withZone(ZoneId.systemDefault())
        val out = mutableListOf<Pair<Long, String>>()
        c.contentResolver.query(Uri.parse("content://sms"), arrayOf("address", "body", "date", "type"), "date >= ?", arrayOf(since.toString()), "date DESC")?.use { cur ->
            while (cur.moveToNext() && out.size < 60) {
                val who2 = byDigits[digits(cur.getString(0) ?: continue)] ?: continue
                val mine = cur.getInt(3) == 2        // 2 = sent
                val at = cur.getLong(2)
                out += at to "${t.format(Instant.ofEpochMilli(at))} ${if (mine) "me -> ${who2.name}" else "${who2.name} -> me"}: ${cur.getString(1)}"
            }
        }
        return out.sortedBy { it.first }.joinToString("\n") { it.second }.ifBlank { "no texts with ${allowed.joinToString { it.name }} in the last $hours hours" }
    }

    // ---------------------------------------------------------------- send

    private data class Pending(val to: TextContact, val text: String, val at: Long, val id: Int)
    @Volatile private var pending: Pending? = null

    /** Hold a text for confirmation and show it with Send. */
    fun prepare(c: Context, who: String, text: String): String {
        if (!enabled(c)) return "texting is off (Device -> Assistant -> Texting)"
        val to = find(c, who) ?: return "$who isn't one of the contacts chosen for texting, so I can't text them; I can make a draft instead (draft_message)"
        if (!canSend(c)) return "Boswell isn't allowed to send texts yet (Device -> Assistant -> Texting); I can make a draft instead"
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
            pending = null
            c.getSystemService(NotificationManager::class.java).cancel(p.id)
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
