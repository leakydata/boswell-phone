package net.boswell.phone.assistant

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract

/** A phone contact's details, read fresh each time (the phone's contacts stay the place they live). */
data class ContactInfo(val name: String, val phones: List<String>, val emails: List<String>, val birthday: String?, val lookup: String)

/**
 * The link between Boswell's people (voices) and the phone's contacts.
 * Boswell keeps only the contact's lookup URI on the person; numbers,
 * emails and birthdays are read from Contacts when needed, so editing a
 * contact updates Boswell too. Reading needs the contacts permission.
 */
object Contacts {
    fun allowed(c: Context) = c.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    /** A picked contact's URI -> its lookup URI, which survives contact edits and syncs. */
    fun lookupUri(c: Context, picked: Uri): String? = runCatching {
        ContactsContract.Contacts.getLookupUri(c.contentResolver, picked)?.toString()
    }.getOrNull() ?: picked.toString()

    fun info(c: Context, lookup: String): ContactInfo? {
        if (!allowed(c)) return null
        return runCatching {
            val uri = ContactsContract.Contacts.lookupContact(c.contentResolver, Uri.parse(lookup)) ?: return null
            val (id, name) = c.contentResolver.query(uri, arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.DISPLAY_NAME_PRIMARY), null, null, null)?.use { cur ->
                if (cur.moveToFirst()) cur.getLong(0) to (cur.getString(1) ?: "") else null
            } ?: return null
            fun list(u: Uri, col: String, where: String, args: Array<String>) =
                c.contentResolver.query(u, arrayOf(col), where, args, null)?.use { cur -> buildList { while (cur.moveToNext()) cur.getString(0)?.let(::add) } }.orEmpty()
            val phones = list(ContactsContract.CommonDataKinds.Phone.CONTENT_URI, ContactsContract.CommonDataKinds.Phone.NUMBER,
                "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?", arrayOf(id.toString())).distinctBy { Texting.digits(it) }
            val emails = list(ContactsContract.CommonDataKinds.Email.CONTENT_URI, ContactsContract.CommonDataKinds.Email.ADDRESS,
                "${ContactsContract.CommonDataKinds.Email.CONTACT_ID} = ?", arrayOf(id.toString())).distinct()
            val birthday = list(ContactsContract.Data.CONTENT_URI, ContactsContract.CommonDataKinds.Event.START_DATE,
                "${ContactsContract.Data.CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} = ? AND ${ContactsContract.CommonDataKinds.Event.TYPE} = ?",
                arrayOf(id.toString(), ContactsContract.CommonDataKinds.Event.CONTENT_ITEM_TYPE, ContactsContract.CommonDataKinds.Event.TYPE_BIRTHDAY.toString())).firstOrNull()
            ContactInfo(name, phones, emails, birthday, lookup)
        }.getOrNull()
    }

    /** Linked people who may be texted, as texting contacts (first number), with their mode. */
    fun textable(c: Context): List<TextContact> {
        val store = net.boswell.phone.speakers.SpeakerStore(c)
        val links = try { store.links() } finally { store.close() }
        return links.filter { it.mayText != "off" }.mapNotNull { l ->
            val i = info(c, l.contact) ?: return@mapNotNull null
            TextContact(l.name ?: i.name, i.phones.firstOrNull() ?: return@mapNotNull null, l.mayText)
        }
    }
}
