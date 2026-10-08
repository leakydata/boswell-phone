package net.boswell.phone.assistant

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import androidx.core.app.NotificationCompat
import net.boswell.phone.R
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Properties
import javax.mail.Address
import javax.mail.Flags
import javax.mail.Folder
import javax.mail.Message
import javax.mail.Multipart
import javax.mail.Part
import javax.mail.Session
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeMessage
import javax.mail.search.AndTerm
import javax.mail.search.ComparisonTerm
import javax.mail.search.FlagTerm
import javax.mail.search.FromStringTerm
import javax.mail.search.OrTerm
import javax.mail.search.ReceivedDateTerm
import javax.mail.search.SearchTerm
import javax.mail.search.SubjectTerm
import javax.mail.search.BodyTerm
import net.boswell.phone.capture.logged

/**
 * Email over IMAP (reading) and SMTP (sending) with an app password, so it
 * works with Gmail, Outlook, iCloud, Yahoo, Fastmail and most others without
 * a sign-in service of our own. The password is kept with [Secrets].
 *
 * Reading opens the inbox read-only, so nothing is marked as read. Sending is
 * held for the person's confirmation, exactly like texts: a notification with
 * Send, or their own "yes" within two minutes.
 */
object Email {
    data class Account(val address: String, val imapHost: String, val imapPort: Int, val smtpHost: String, val smtpPort: Int)

    private fun p(c: Context) = c.getSharedPreferences("boswell", Context.MODE_PRIVATE)
    const val PASSWORD = "email_app_password"

    /** The usual servers for an address's provider; anything else guesses imap./smtp. on its domain. */
    fun guess(address: String): Account {
        val domain = address.substringAfter('@').lowercase().trim()
        return when (domain) {
            "gmail.com", "googlemail.com" -> Account(address, "imap.gmail.com", 993, "smtp.gmail.com", 465)
            "outlook.com", "hotmail.com", "live.com", "msn.com" -> Account(address, "outlook.office365.com", 993, "smtp-mail.outlook.com", 587)
            "icloud.com", "me.com", "mac.com" -> Account(address, "imap.mail.me.com", 993, "smtp.mail.me.com", 587)
            "yahoo.com", "ymail.com" -> Account(address, "imap.mail.yahoo.com", 993, "smtp.mail.yahoo.com", 465)
            "aol.com" -> Account(address, "imap.aol.com", 993, "smtp.aol.com", 465)
            "fastmail.com", "fastmail.fm" -> Account(address, "imap.fastmail.com", 993, "smtp.fastmail.com", 465)
            "proton.me", "protonmail.com" -> Account(address, "127.0.0.1", 1143, "127.0.0.1", 1025)   // Proton Mail Bridge
            else -> Account(address, "imap.$domain", 993, "smtp.$domain", 465)
        }
    }

    /** Where an app password is made, for the providers that need one. */
    fun appPasswordHelp(address: String): String = when (address.substringAfter('@').lowercase()) {
        "gmail.com", "googlemail.com" -> "Gmail: turn on 2-Step Verification, then make one at myaccount.google.com/apppasswords."
        "icloud.com", "me.com", "mac.com" -> "iCloud: account.apple.com → Sign-In and Security → App-Specific Passwords."
        "yahoo.com", "ymail.com" -> "Yahoo: Account security → Generate app password."
        "outlook.com", "hotmail.com", "live.com", "msn.com" -> "Outlook: account.microsoft.com → Security → Advanced security options → App passwords (needs two-step verification)."
        "fastmail.com", "fastmail.fm" -> "Fastmail: Settings → Privacy & Security → Integrations → New app password."
        else -> "Use an app password from your email provider if it offers one, rather than your main password."
    }

    fun account(c: Context): Account? {
        val a = p(c).getString("email_address", null) ?: return null
        val g = guess(a)
        return Account(a, p(c).getString("email_imap", null) ?: g.imapHost, p(c).getInt("email_imap_port", g.imapPort),
            p(c).getString("email_smtp", null) ?: g.smtpHost, p(c).getInt("email_smtp_port", g.smtpPort))
    }

    fun configured(c: Context) = account(c) != null && Secrets.get(c, PASSWORD) != null

    fun save(c: Context, a: Account, password: String?) {
        p(c).edit().putString("email_address", a.address.trim()).putString("email_imap", a.imapHost.trim()).putInt("email_imap_port", a.imapPort)
            .putString("email_smtp", a.smtpHost.trim()).putInt("email_smtp_port", a.smtpPort).apply()
        if (password != null) Secrets.put(c, PASSWORD, password.replace(" ", ""))   // app passwords are shown in groups of four
    }

    fun forget(c: Context) {
        p(c).edit().remove("email_address").remove("email_imap").remove("email_imap_port").remove("email_smtp").remove("email_smtp_port").apply()
        Secrets.put(c, PASSWORD, null)
    }

    private fun session(a: Account): Session = Session.getInstance(Properties().apply {
        put("mail.store.protocol", "imaps")
        put("mail.imaps.host", a.imapHost); put("mail.imaps.port", a.imapPort.toString())
        put("mail.imaps.connectiontimeout", "15000"); put("mail.imaps.timeout", "30000")
        put("mail.smtp.host", a.smtpHost); put("mail.smtp.port", a.smtpPort.toString()); put("mail.smtp.auth", "true")
        put("mail.smtp.connectiontimeout", "15000"); put("mail.smtp.timeout", "30000")
        if (a.smtpPort == 465) put("mail.smtp.ssl.enable", "true") else put("mail.smtp.starttls.enable", "true").also { put("mail.smtp.starttls.required", "true") }
        // Proton Mail Bridge listens on the phone itself with its own certificate.
        if (a.imapHost == "127.0.0.1") { put("mail.store.protocol", "imap"); put("mail.imap.host", a.imapHost); put("mail.imap.port", a.imapPort.toString()) }
    })

    private fun <T> inbox(c: Context, block: (Folder) -> T): T {
        val a = account(c) ?: error("email isn't set up (Device → Assistant → Email)")
        val pw = Secrets.get(c, PASSWORD) ?: error("email isn't set up (Device → Assistant → Email)")
        val store = session(a).getStore(if (a.imapHost == "127.0.0.1") "imap" else "imaps")
        store.connect(a.imapHost, a.imapPort, a.address, pw)
        try {
            val f = store.getFolder("INBOX")
            f.open(Folder.READ_ONLY)   // read-only: reading here never marks anything as read
            try { return block(f) } finally { f.close(false) }
        } finally { store.close() }
    }

    /** Sign in to both servers; null if fine, otherwise what went wrong, in words. */
    fun test(c: Context): String? = runCatching {
        val count = inbox(c) { it.messageCount }
        val a = account(c)!!
        session(a).getTransport("smtp").use { t -> t.connect(a.smtpHost, a.smtpPort, a.address, Secrets.get(c, PASSWORD)) }
        net.boswell.phone.capture.CaptureRepository.log("email: signed in, $count messages in the inbox")
        null
    }.getOrElse { e ->
        val m = (e.message ?: e.javaClass.simpleName).lowercase()
        when {
            "authenticat" in m || "invalid credentials" in m || "login" in m -> "The password was refused. Use an app password (see the tip above), not your usual one."
            "unknownhost" in m || "unable to resolve" in m -> "Couldn't find the mail server. Check the server names."
            "timed out" in m || "timeout" in m -> "The mail server didn't answer. Check the server names and ports, and the connection."
            else -> "Couldn't sign in: ${e.message}"
        }
    }

    private val fmt = DateTimeFormatter.ofPattern("EEE MMM d, h:mm a")
    private fun at(d: Date?) = d?.let { Instant.ofEpochMilli(it.time).atZone(ZoneId.systemDefault()).format(fmt) } ?: "?"

    private fun who(a: Array<Address>?) = a.orEmpty().joinToString(", ") { x ->
        (x as? InternetAddress)?.let { ia -> ia.personal?.let { "$it <${ia.address}>" } ?: ia.address } ?: x.toString()
    }

    /** The readable text of a message: its plain-text part, or its HTML with the tags taken out. */
    private fun textOf(p: Part, depth: Int = 0): String? {
        if (depth > 6) return null
        return when {
            p.isMimeType("text/plain") -> p.content as? String
            p.isMimeType("text/html") -> (p.content as? String)?.let { html ->
                html.replace(Regex("(?is)<(script|style)[^>]*>.*?</\\1>"), " ").replace(Regex("(?i)<br\\s*/?>|</p>|</div>"), "\n")
                    .replace(Regex("<[^>]+>"), " ").replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                    .replace("&#39;", "'").replace("&quot;", "\"")
            }
            p.isMimeType("multipart/alternative") -> (p.content as Multipart).let { mp ->
                (0 until mp.count).map { mp.getBodyPart(it) }.let { parts ->
                    parts.firstOrNull { it.isMimeType("text/plain") }?.let { textOf(it, depth + 1) } ?: parts.firstNotNullOfOrNull { textOf(it, depth + 1) }
                }
            }
            p.isMimeType("multipart/*") -> (p.content as Multipart).let { mp -> (0 until mp.count).firstNotNullOfOrNull { textOf(mp.getBodyPart(it), depth + 1) } }
            else -> null
        }
    }

    private fun tidy(s: String) = s.replace("\r", "").lines().map { it.trim() }
        .filterNot { it.startsWith(">") }                  // quoted earlier messages
        .joinToString("\n").replace(Regex("\n{3,}"), "\n\n").trim()

    /**
     * The newest inbox messages, optionally matching words (subject or body),
     * a sender, unread only, or the last N days. Newest first, each with its
     * opening text.
     */
    fun read(c: Context, query: String?, from: String?, unreadOnly: Boolean, days: Int?, limit: Int): String = runCatching {
        inbox(c) { f ->
            val terms = buildList<SearchTerm> {
                query?.takeIf { it.isNotBlank() }?.let { add(OrTerm(SubjectTerm(it), BodyTerm(it))) }
                from?.takeIf { it.isNotBlank() }?.let { add(FromStringTerm(it)) }
                if (unreadOnly) add(FlagTerm(Flags(Flags.Flag.SEEN), false))
                days?.let { add(ReceivedDateTerm(ComparisonTerm.GE, Date(System.currentTimeMillis() - it * 86_400_000L))) }
            }
            val msgs: List<Message> = if (terms.isEmpty()) {
                val n = f.messageCount
                if (n == 0) emptyList() else f.getMessages((n - limit + 1).coerceAtLeast(1), n).toList()
            } else f.search(if (terms.size == 1) terms[0] else AndTerm(terms.toTypedArray())).toList()
            val newest = msgs.sortedByDescending { it.receivedDate ?: it.sentDate }.take(limit)
            if (newest.isEmpty()) return@inbox "no messages" + if (terms.isNotEmpty()) " matching that" else ""
            newest.joinToString("\n\n") { m ->
                val unread = !m.flags.contains(Flags.Flag.SEEN)
                val body = runCatching { textOf(m)?.let(::tidy) }.getOrNull().orEmpty()
                "${at(m.receivedDate ?: m.sentDate)}${if (unread) " (unread)" else ""}\nFrom: ${who(m.from)}\nSubject: ${m.subject ?: "(none)"}\n" +
                    body.take(700) + if (body.length > 700) " …" else ""
            }
        }
    }.getOrElse { "couldn't read email: ${it.message}" }

    // ---------------------------------------------------------------- send

    /** An email address, or the one email a person in the phone's contacts has. */
    fun resolve(c: Context, who: String): Pair<String, String>? {
        val w = who.trim()
        Regex("[\\w.+-]+@[\\w-]+(\\.[\\w-]+)+").find(w)?.let { return w.substringBefore('<').trim().ifEmpty { it.value } to it.value }
        if (!Contacts.allowed(c)) return null
        val found = c.contentResolver.query(ContactsContract.CommonDataKinds.Email.CONTENT_URI,
            arrayOf(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY, ContactsContract.CommonDataKinds.Email.ADDRESS),
            "${ContactsContract.Contacts.DISPLAY_NAME_PRIMARY} LIKE ?", arrayOf("%$w%"), null)?.use { cur ->
            buildList { while (cur.moveToNext()) add(cur.getString(0) to cur.getString(1)) }
        }.orEmpty().distinctBy { it.second.lowercase() }
        // One person: their address (the first, if they have several). Several people: don't guess.
        return found.takeIf { f -> f.map { it.first }.distinct().size == 1 }?.firstOrNull()
    }

    private data class Pending(val name: String, val to: String, val subject: String, val body: String, val at: Long, val id: Int)
    @Volatile private var pending: Pending? = null

    fun prepare(c: Context, who: String, subject: String, body: String): String {
        if (!configured(c)) return "email isn't set up (Device → Assistant → Email); I can make a draft instead (draft_message with via=email)"
        val (name, to) = resolve(c, who) ?: return "I couldn't find one email address for $who in the contacts; give me the address, or I can make a draft"
        val id = (System.currentTimeMillis() % 100_000).toInt() + 600_000
        pending = Pending(name, to, subject.trim(), body.trim(), System.currentTimeMillis(), id)
        AssistantNotify.ensureChannels(c)
        val send = PendingIntent.getBroadcast(c, id, Intent(c, EmailSendReceiver::class.java).putExtra("id", id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val edit = PendingIntent.getActivity(c, id + 1, Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:")).putExtra(Intent.EXTRA_EMAIL, arrayOf(to))
            .putExtra(Intent.EXTRA_SUBJECT, subject).putExtra(Intent.EXTRA_TEXT, body).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(c, AssistantNotify.ANSWERS)
            .setSmallIcon(R.drawable.ic_stat_mic)
            .setContentTitle("Email $name?")
            .setContentText(subject)
            .setStyle(NotificationCompat.BigTextStyle().bigText("To: $to\nSubject: $subject\n\n$body"))
            .addAction(0, "Send", send)
            .addAction(0, "Edit", edit)
            .setTimeoutAfter(Texting.CONFIRM_MS)
            .setAutoCancel(true)
            .build()
        runCatching { c.getSystemService(NotificationManager::class.java).notify(id, n) }.logged("posting a notification")
        return "held for confirmation: email to $name <$to>, subject “$subject”. It is NOT sent. Ask them to confirm: tap Send on the notification, or say yes within two minutes."
    }

    /** Send the held email, only if the person's own words confirm it. */
    fun confirm(c: Context, userWords: String?): String {
        val p = pending ?: return "there is no email waiting to be sent"
        if (System.currentTimeMillis() - p.at > Texting.CONFIRM_MS) { pending = null; return "the held email expired; ask again to send it" }
        if (!Texting.isConfirmation(userWords)) return "not sent: they haven't confirmed in their own words. Ask them to say yes or tap Send."
        return send(c, p)
    }

    internal fun sendPending(c: Context, id: Int): String {
        val p = pending?.takeIf { it.id == id } ?: return "nothing to send"
        return send(c, p)
    }

    private fun send(c: Context, p: Pending): String = runCatching {
        val a = account(c) ?: error("email isn't set up")
        val s = session(a)
        val m = MimeMessage(s).apply {
            setFrom(InternetAddress(a.address)); setRecipients(Message.RecipientType.TO, InternetAddress.parse(p.to))
            subject = p.subject; setText(p.body, "utf-8"); sentDate = Date()
        }
        s.getTransport("smtp").use { t -> t.connect(a.smtpHost, a.smtpPort, a.address, Secrets.get(c, PASSWORD)); t.sendMessage(m, m.allRecipients) }
        pending = null
        c.getSystemService(NotificationManager::class.java).cancel(p.id)
        net.boswell.phone.capture.CaptureRepository.log("emailed ${p.name}")
        "sent to ${p.name} <${p.to}>: “${p.subject}”"
    }.getOrElse { "couldn't send: ${it.message}" }
}

/** The Send button on a held email. Sending is network work: off the main thread. */
class EmailSendReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val done = goAsync()
        Thread {
            try {
                val r = Email.sendPending(context, intent.getIntExtra("id", -1))
                AssistantNotify.post(context, AssistantNotify.ANSWERS, "Email", r.replaceFirstChar { it.uppercase() })
            } finally { done.finish() }
        }.start()
    }
}
