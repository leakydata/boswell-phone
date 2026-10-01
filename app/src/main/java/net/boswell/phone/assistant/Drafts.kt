package net.boswell.phone.assistant

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import androidx.core.app.NotificationCompat
import net.boswell.phone.R

/**
 * Message drafts: the assistant writes it, the person sends it. A draft is a
 * notification whose tap opens their messaging (or email) app with the
 * recipient and text filled in. Nothing is sent from here, ever.
 */
object Drafts {
    /** The draft made while answering, so the answer itself can carry "Open draft". */
    @Volatile var last: Pair<Long, Intent>? = null

    /** A draft made in the last two minutes, taken (once) for the answer that made it. */
    fun takeRecent(): Intent? = last?.takeIf { System.currentTimeMillis() - it.first < 120_000 }?.second.also { last = null }

    fun prepare(c: Context, to: String, text: String, via: String): String {
        val email = via.equals("email", true) || to.contains("@")
        val target = if (email) (if (to.contains("@")) to else lookup(c, to, email = true)) else (if (to.any { it.isDigit() }) to else lookup(c, to, email = false))
        val intent = if (email) Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:${target ?: ""}")).putExtra(Intent.EXTRA_TEXT, text)
            else Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${target ?: ""}")).putExtra("sms_body", text)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        last = System.currentTimeMillis() to intent
        val id = (System.currentTimeMillis() % 100_000).toInt() + 300_000
        AssistantNotify.ensureChannels(c)
        val n = NotificationCompat.Builder(c, AssistantNotify.ANSWERS)
            .setSmallIcon(R.drawable.ic_stat_mic)
            .setContentTitle("Draft to ${to}" + if (target == null) " (pick the contact)" else "")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(PendingIntent.getActivity(c, id, intent, PendingIntent.FLAG_IMMUTABLE))
            .addAction(0, "Open to send", PendingIntent.getActivity(c, id + 1, intent, PendingIntent.FLAG_IMMUTABLE))
            .setAutoCancel(true)
            .build()
        runCatching { c.getSystemService(android.app.NotificationManager::class.java).notify(id, n) }
        return "draft ready as a notification" + (if (target == null) "; ${to} wasn't found in contacts, so they'll pick the recipient" else " to $to ($target)") +
            ". It is not sent until they tap it and send it themselves."
    }

    /** A contact's number or email by name, if contacts may be read. */
    private fun lookup(c: Context, name: String, email: Boolean): String? {
        if (c.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return null
        val uri = if (email) ContactsContract.CommonDataKinds.Email.CONTENT_URI else ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val col = if (email) ContactsContract.CommonDataKinds.Email.ADDRESS else ContactsContract.CommonDataKinds.Phone.NUMBER
        return runCatching {
            c.contentResolver.query(uri, arrayOf(col), "${ContactsContract.Contacts.DISPLAY_NAME_PRIMARY} LIKE ?", arrayOf("%${name.trim()}%"), null)?.use { cur ->
                if (cur.moveToFirst()) cur.getString(0) else null
            }
        }.getOrNull()
    }
}
