package net.boswell.phone.speakers

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class Person(
    val id: Long,
    val name: String?,
    val voiceprints: Int,
    val seconds: Double,
    /** person | media | ignored | null (not decided). Lives on the voice, not the clip. */
    val kind: String?,
    val lastHeard: Double?,
)

/** Voiceprints that arrived in a person together: one unnamed cluster that was named, or one sighting. */
data class VoiceGroup(val key: Long, val voiceprints: Int, val seconds: Double, val clips: List<String>, val firstHeard: Double)

/**
 * Speaker identity on the phone: the desktop's schema and rules.
 *
 * One row per voiceprint, never an average: a voice in a quiet room and the
 * same voice outdoors are far apart, and an average of them matches neither.
 * Unidentified voices are people rows with a NULL name, so naming a stranger
 * is one UPDATE that turns every voiceprint gathered under them into a
 * reference. Vectors are float32, unit length, stored exactly as the desktop
 * stores them, so the two stores can be merged later.
 *
 * Starts empty. Nothing is imported from the desktop.
 */
class SpeakerStore(context: Context) : SQLiteOpenHelper(context, "speakers.db", null, 9) {
    init {
        Matching.model = net.boswell.phone.diarize.VoiceModels.active(context)
        Matching.owner = net.boswell.phone.assistant.AssistantPrefs.owner(context)
        net.boswell.phone.Databases.share(this)
    }


    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE people (
                id      INTEGER PRIMARY KEY,
                name    TEXT UNIQUE,              -- NULL = an unidentified recurring voice
                kind    TEXT,
                created REAL,
                contact TEXT,                     -- a phone contact's lookup URI, if linked
                may_text TEXT                     -- off | ask | auto (send right away), for a linked contact
            )""")
        db.execSQL("""
            CREATE TABLE voiceprints (
                id        INTEGER PRIMARY KEY,
                person_id INTEGER NOT NULL REFERENCES people(id) ON DELETE CASCADE,
                vec       BLOB NOT NULL,          -- float32, unit length
                dim       INTEGER NOT NULL,
                seconds   REAL,
                clip      TEXT,
                speaker   TEXT,
                origin    TEXT NOT NULL,          -- manual | confirmed | auto
                redundant INTEGER NOT NULL DEFAULT 0,
                impure    INTEGER NOT NULL DEFAULT 0,
                created   REAL,
                -- The unnamed cluster these came from when a name was given,
                -- so a wrong name can be taken back off exactly that group.
                source_cluster INTEGER
            )""")
        db.execSQL("CREATE INDEX vp_person ON voiceprints(person_id)")
        db.execSQL(MERGES)
        db.execSQL(REJECTIONS)
        db.execSQL(ASSIGNED)
        db.execSQL(BOSWELL_VOICE)
        db.execSQL(NOT_BOSWELL)
        db.execSQL(LABEL_CHECKS)
        db.execSQL("""
            CREATE TABLE matches (
                id            INTEGER PRIMARY KEY,
                clip          TEXT,
                speaker       TEXT,
                voiceprint_id INTEGER,
                person_id     INTEGER,
                score         REAL,
                margin        REAL,
                decision      TEXT,
                corrected     INTEGER NOT NULL DEFAULT 0,
                created       REAL
            )""")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) db.execSQL("ALTER TABLE voiceprints ADD COLUMN source_cluster INTEGER")
        if (oldVersion < 3) db.execSQL(MERGES)
        if (oldVersion < 4) db.execSQL(REJECTIONS)
        if (oldVersion < 5) db.execSQL(ASSIGNED)
        if (oldVersion < 6) {
            db.execSQL("ALTER TABLE people ADD COLUMN contact TEXT")
            db.execSQL("ALTER TABLE people ADD COLUMN may_text TEXT")
        }
        if (oldVersion < 7) {
            // Short voiceprints saved before Matching.MIN_PRINT_SECONDS: drop them, as long
            // as the person keeps at least one other (someone known only from short samples
            // keeps them, or they'd vanish). Their recordings stay labeled by assignment.
            db.execSQL("""INSERT OR IGNORE INTO assigned(clip, speaker, person_id)
                SELECT clip, speaker, person_id FROM voiceprints v
                WHERE seconds IS NOT NULL AND seconds < ${Matching.MIN_PRINT_SECONDS} AND origin != 'manual' AND clip IS NOT NULL AND speaker IS NOT NULL
                  AND (SELECT COUNT(*) FROM voiceprints w WHERE w.person_id = v.person_id AND (w.seconds IS NULL OR w.seconds >= ${Matching.MIN_PRINT_SECONDS} OR w.origin = 'manual')) > 0""")
            db.execSQL("""DELETE FROM voiceprints
                WHERE seconds IS NOT NULL AND seconds < ${Matching.MIN_PRINT_SECONDS} AND origin != 'manual'
                  AND (SELECT COUNT(*) FROM voiceprints w WHERE w.person_id = voiceprints.person_id AND (w.seconds IS NULL OR w.seconds >= ${Matching.MIN_PRINT_SECONDS} OR w.origin = 'manual')) > 0""")
        }
        if (oldVersion < 8) { db.execSQL(BOSWELL_VOICE); db.execSQL(NOT_BOSWELL) }
        if (oldVersion < 9) db.execSQL(LABEL_CHECKS)
    }

    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
        net.boswell.phone.Databases.waitForWriters(db)
    }

    private fun now() = System.currentTimeMillis() / 1000.0

    /**
     * A read too big for one cursor window (voiceprints: thousands of rows) is fetched a window
     * at a time, and another connection changing the rows between windows makes it fail
     * ("Couldn't read row"). Inside a transaction every window comes from the same moment.
     * Android 15 has a read-only transaction, which with the write ahead log runs beside
     * writers; before that it has to be one that reserves writing, so other writers wait
     * until it's read (the rows are copied out inside, so not for long).
     */
    private fun <T> whole(read: (SQLiteDatabase) -> T): T {
        val db = writableDatabase
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.VANILLA_ICE_CREAM) db.beginTransactionReadOnly()
        else db.beginTransactionNonExclusive()
        try { return read(db).also { db.setTransactionSuccessful() } } finally { db.endTransaction() }
    }

    private fun refs(named: Boolean): List<Matching.Reference> {
        val sql = """SELECT v.id, v.person_id, v.vec, v.clip FROM voiceprints v JOIN people p ON p.id = v.person_id
                     WHERE p.name IS ${if (named) "NOT NULL" else "NULL"} AND v.impure = 0 AND v.dim = ${Matching.model.dim} ORDER BY v.id"""
        return whole { db -> db.rawQuery(sql, null).use { c ->
            buildList { while (c.moveToNext()) add(Matching.Reference(c.getLong(0), c.getLong(1), unpack(c.getBlob(2)), c.str(3))) }
        } }
    }

    /**
     * Match against named people: unnamed clusters are the question, not the
     * answer -- but they are part of the field a match must be clear of.
     * [exclude] keeps a voice from competing with its own filed voiceprint,
     * and [notPeople] are people it was said not to be. [seconds] is how much
     * speech the voice has and [snr] how far above its room it is, null if
     * unknown (Matching.isOwner). Scores are normalized (AsNorm) against the
     * unnamed voices, as heard in recording [clip], unless [normalized] is false.
     */
    fun match(vec: FloatArray, seconds: Double?, clip: String? = null, snr: Double? = null, exclude: Pair<String, String>? = null,
              notPeople: Set<Long> = emptySet(), normalized: Boolean = true): Matching.Result {
        val named = refs(named = true)
        val all = fieldRows()
        val field = all.filter { (r, speaker) -> exclude == null || r.clip != exclude.first || speaker != exclude.second }.map { it.first }
        val scorer = if (normalized) norm(named, all.map { it.first })?.forVoice(vec, clip, clip?.let(Pooling::clipTime)) else null
        return Matching.match(vec, if (notPeople.isEmpty()) named else named.filter { it.personId !in notPeople }, field, seconds, snr, scorer)
    }

    /** Unnamed voices' voiceprints, without the ones of one clip's voice. */
    fun unnamedField(exclude: Pair<String, String>? = null): List<Matching.Reference> =
        fieldRows().filter { (r, speaker) -> exclude == null || r.clip != exclude.first || speaker != exclude.second }.map { it.first }

    private fun fieldRows(): List<Pair<Matching.Reference, String?>> {
        val sql = """SELECT v.id, v.person_id, v.vec, v.clip, v.speaker FROM voiceprints v JOIN people p ON p.id = v.person_id
                     WHERE p.name IS NULL AND v.impure = 0 AND v.dim = ${Matching.model.dim}"""
        return whole { db -> db.rawQuery(sql, null).use { c ->
            buildList { while (c.moveToNext()) add(Matching.Reference(c.getLong(0), c.getLong(1), unpack(c.getBlob(2)), c.str(3)) to c.str(4)) }
        } }
    }

    /**
     * Normalization (AsNorm) for matching against [named] with [field] as the
     * unnamed voices, or null with another model. The cohort is made again
     * when the named voiceprints, the owner or the model change, when an
     * unnamed voiceprint moves to another cluster (a self-cluster may come or
     * go: AsNorm.SELF_CLUSTER_PRINTS), or when the unnamed voices have been
     * added or gone by more than [COHORT_DRIFT] of it since; in between, a
     * voiceprint is scored against the cohort the first time it's met, and
     * kept. One cohort serves every SpeakerStore of the app.
     */
    fun norm(named: List<Matching.Reference> = refs(named = true), field: List<Matching.Reference> = unnamedField()): AsNorm.Norm? {
        if (Matching.model != net.boswell.phone.diarize.VoiceModel.SPEAKER_ID) return null
        val key = Triple(Matching.model.id, Matching.owner, named.map { it.voiceprintId to it.personId }.hashCode())
        val ids = field.associateTo(HashMap()) { it.voiceprintId to it.personId }
        val c = cached
        if (c != null && c.key == key && c.field.all { (id, cluster) -> ids[id].let { it == null || it == cluster } }) {
            val changed = c.field.keys.count { it !in ids } + ids.keys.count { it !in c.field }
            if (changed <= COHORT_DRIFT * c.field.size) return c.norm
        }
        return AsNorm.Norm(AsNorm.cohort(named, field, Matching.owner)).also { cached = Cached(key, ids, it) }
    }

    fun logMatch(clip: String, speaker: String, r: Matching.Result) {
        writableDatabase.insert("matches", null, ContentValues().apply {
            put("clip", clip); put("speaker", speaker)
            r.candidates.firstOrNull()?.let { put("voiceprint_id", it.voiceprintId); put("person_id", it.personId) }
            put("score", r.score); r.margin?.let { put("margin", it) }
            put("decision", r.decision.name.lowercase()); put("created", now())
        })
    }

    /**
     * File a voice nobody could name: join the closest unnamed cluster if it
     * clears CLUSTER_MIN, or start a new one. Attached to nobody with a name,
     * so an automatic reference can never reinforce a wrong label.
     */
    fun ingestUnknown(vec: FloatArray, clip: String, speaker: String, seconds: Double): Long {
        val (existing, _) = Matching.bestCluster(vec, refs(named = false))
        val pid = existing ?: newPerson(null)
        addVoiceprint(pid, vec, seconds, clip, speaker, "auto")
        return pid
    }

    /** The person called [name], made if there's nobody yet (people() leaves out someone with no voiceprint). */
    fun named(name: String): Long = readableDatabase.rawQuery("SELECT id FROM people WHERE name = ?", arrayOf(name)).use { c ->
        if (c.moveToFirst()) c.getLong(0) else null
    } ?: newPerson(name)

    fun newPerson(name: String?): Long = writableDatabase.insertOrThrow("people", null, ContentValues().apply {
        put("name", name); put("created", now())
    })

    fun addVoiceprint(personId: Long, vec: FloatArray, seconds: Double?, clip: String?, speaker: String?, origin: String): Long {
        require(Matching.usable(vec)) { "unusable voiceprint" }
        val v = Matching.unit(vec)
        return writableDatabase.insertOrThrow("voiceprints", null, ContentValues().apply {
            put("person_id", personId); put("vec", pack(v)); put("dim", v.size)
            seconds?.let { put("seconds", it) }; put("clip", clip); put("speaker", speaker)
            put("origin", origin); put("created", now())
        })
    }

    /**
     * Name a cluster, or rename someone. Naming a stranger as somebody already
     * known is a merge -- the good case: their voiceprints join that person's
     * and cover conditions that were missing. Returns the surviving person id.
     */
    fun name(personId: Long, name: String): Long {
        val db = writableDatabase
        val existing = db.rawQuery("SELECT id FROM people WHERE name = ?", arrayOf(name)).use { c ->
            if (c.moveToFirst()) c.getLong(0) else null
        }
        db.beginTransaction()
        try {
            // Remember which cluster each voiceprint came from, once, so the
            // name can be undone for exactly this group later.
            db.execSQL("UPDATE voiceprints SET source_cluster = COALESCE(source_cluster, ?) WHERE person_id = ?", arrayOf<Any>(personId, personId))
            if (existing != null && existing != personId) {
                db.execSQL("UPDATE voiceprints SET person_id = ? WHERE person_id = ?", arrayOf<Any>(existing, personId))
                // Transcripts written earlier still say the old id; this is how they find the new one.
                db.execSQL("INSERT OR REPLACE INTO merges(from_id, into_id) VALUES (?, ?)", arrayOf<Any>(personId, existing))
                db.execSQL("DELETE FROM people WHERE id = ?", arrayOf<Any>(personId))
                db.setTransactionSuccessful()
                return existing
            }
            db.execSQL("UPDATE people SET name = ? WHERE id = ?", arrayOf<Any>(name, personId))
            db.setTransactionSuccessful()
            return personId
        } finally {
            db.endTransaction()
        }
    }

    fun people(): List<Person> = readableDatabase.rawQuery("""
        SELECT p.id, p.name, SUM(v.dim = ${Matching.model.dim}), COALESCE(SUM(CASE WHEN v.dim = ${Matching.model.dim} THEN v.seconds END), 0), p.kind, MAX(v.created) FROM people p
        LEFT JOIN voiceprints v ON v.person_id = p.id GROUP BY p.id HAVING COUNT(v.id) > 0
        ORDER BY p.name IS NULL, p.name, p.id""", null).use { c ->
        buildList {
            while (c.moveToNext()) add(Person(c.getLong(0), c.str(1), c.getInt(2), c.getDouble(3), c.str(4),
                if (c.isNull(5)) null else c.getDouble(5)))
        }
    }

    fun person(id: Long): Person? = people().firstOrNull { it.id == id }

    /** Mark a voice as a person, media (a screen talking) or ignored. A kind never stops a voice being collected. */
    fun setKind(personId: Long, kind: String?) {
        writableDatabase.execSQL("UPDATE people SET kind = ? WHERE id = ?", arrayOf<Any?>(kind, personId))
    }

    /** A person's voiceprints grouped by the cluster they were named from (or by clip, for single sightings). */
    fun groups(personId: Long): List<VoiceGroup> = readableDatabase.rawQuery("""
        SELECT COALESCE(source_cluster, -id), COUNT(*), COALESCE(SUM(seconds), 0), GROUP_CONCAT(clip, '|'), MIN(created)
        FROM voiceprints WHERE person_id = ? AND dim = ${Matching.model.dim} GROUP BY COALESCE(source_cluster, -id) ORDER BY MIN(created) DESC""",
        arrayOf(personId.toString())).use { c ->
        buildList {
            while (c.moveToNext()) add(VoiceGroup(c.getLong(0), c.getInt(1), c.getDouble(2),
                (c.str(3) ?: "").split("|").filter { it.isNotEmpty() }.distinct(), c.getDouble(4)))
        }
    }

    /**
     * "That wasn't them": take one group back off a person into a new unnamed
     * voice. Nothing is deleted, so a mistaken undo is just a rename away.
     */
    fun unnameGroup(personId: Long, groupKey: Long): Long {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val fresh = newPerson(null)
            val where = if (groupKey < 0) "id = ?" else "source_cluster = ?"
            db.execSQL("INSERT OR IGNORE INTO rejections(clip, speaker, person_id) SELECT clip, speaker, person_id FROM voiceprints " +
                "WHERE $where AND person_id = ? AND clip IS NOT NULL AND speaker IS NOT NULL", arrayOf<Any>(if (groupKey < 0) -groupKey else groupKey, personId))
            if (groupKey < 0) db.execSQL("UPDATE voiceprints SET person_id = ? WHERE id = ? AND person_id = ?", arrayOf<Any>(fresh, -groupKey, personId))
            else db.execSQL("UPDATE voiceprints SET person_id = ?, source_cluster = NULL WHERE source_cluster = ? AND person_id = ?", arrayOf<Any>(fresh, groupKey, personId))
            db.setTransactionSuccessful()
            return fresh
        } finally {
            db.endTransaction()
        }
    }

    /**
     * "Not them" for single voiceprints rather than a group (LabelCheck): these
     * of [personId]'s voiceprints go to one new unnamed voice, and their voices
     * are never matched to [personId] again. Nothing is deleted.
     */
    fun unnamePrints(personId: Long, ids: Collection<Long>): Long {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val fresh = newPerson(null)
            for (id in ids) {
                db.execSQL("INSERT OR IGNORE INTO rejections(clip, speaker, person_id) SELECT clip, speaker, person_id FROM voiceprints " +
                    "WHERE id = ? AND person_id = ? AND clip IS NOT NULL AND speaker IS NOT NULL", arrayOf<Any>(id, personId))
                db.execSQL("UPDATE voiceprints SET person_id = ?, source_cluster = NULL WHERE id = ? AND person_id = ?", arrayOf<Any>(fresh, id, personId))
            }
            db.setTransactionSuccessful()
            return fresh
        } finally {
            db.endTransaction()
        }
    }

    /**
     * One recording's voice filed under two people, and it's the other one:
     * [personId]'s copies of it go (the other person keeps theirs, so the
     * recording stays labeled) and it is never matched to [personId] again.
     */
    fun dropVoice(personId: Long, clip: String, speaker: String) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.execSQL("DELETE FROM voiceprints WHERE person_id = ? AND clip = ? AND speaker = ?", arrayOf<Any>(personId, clip, speaker))
            db.execSQL("DELETE FROM assigned WHERE person_id = ? AND clip = ? AND speaker = ?", arrayOf<Any>(personId, clip, speaker))
            reject(clip, speaker, personId)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    private fun android.database.Cursor.str(i: Int): String? = if (isNull(i)) null else getString(i)

    /**
     * Forget what was learned automatically from deleted clips. Voiceprints
     * someone confirmed or named by hand stay: deleting a recording is not a
     * request to stop recognizing a person.
     */
    fun forgetClips(clips: Collection<String>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (c in clips) db.execSQL("DELETE FROM voiceprints WHERE clip = ? AND origin = 'auto'", arrayOf(c))
            for (c in clips) db.execSQL("DELETE FROM boswell_voice WHERE clip = ?", arrayOf(c))
            // Unnamed voices left with nothing are gone entirely.
            db.execSQL("DELETE FROM people WHERE name IS NULL AND id NOT IN (SELECT DISTINCT person_id FROM voiceprints)")
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    // ------------------------------------------------------------- review

    /** Named people's voiceprints: what a voice is matched against. */
    fun namedRefs(): List<Matching.Reference> = refs(named = true)

    data class Member(val id: Long, val clip: String?, val speaker: String?, val vec: FloatArray, val seconds: Double)

    /** Named people's voiceprints with where each came from, for LabelCheck. */
    fun namedPrints(): List<LabelCheck.Print> = whole { db -> db.rawQuery("""
        SELECT v.id, v.person_id, v.clip, v.speaker, v.vec, COALESCE(v.seconds, 0) FROM voiceprints v JOIN people p ON p.id = v.person_id
        WHERE p.name IS NOT NULL AND v.impure = 0 AND v.dim = ${Matching.model.dim} ORDER BY v.id""", null).use { c ->
        buildList { while (c.moveToNext()) add(LabelCheck.Print(c.getLong(0), c.getLong(1), c.str(2), c.str(3), unpack(c.getBlob(4)), c.getDouble(5))) }
    } }

    /** LabelCheck questions answered "that's right" or "they're different": not asked again. */
    fun labelChecked(): Set<String> = readableDatabase.rawQuery("SELECT key FROM label_checks", null).use { c ->
        buildSet { while (c.moveToNext()) add(c.getString(0)) }
    }

    fun markLabelChecked(key: String) {
        writableDatabase.execSQL("INSERT OR REPLACE INTO label_checks(key, created) VALUES (?, ?)", arrayOf<Any>(key, now()))
    }

    /** Unnamed voices that are still open questions (not marked TV or ignored), with their voiceprints. */
    fun unnamedClusters(): Map<Long, List<Member>> = whole { db -> db.rawQuery("""
        SELECT v.person_id, v.id, v.clip, v.speaker, v.vec, COALESCE(v.seconds, 0) FROM voiceprints v JOIN people p ON p.id = v.person_id
        WHERE p.name IS NULL AND p.kind IS NULL AND v.impure = 0 AND v.dim = ${Matching.model.dim} ORDER BY v.person_id, v.id""", null).use { c ->
        val out = LinkedHashMap<Long, MutableList<Member>>()
        while (c.moveToNext()) out.getOrPut(c.getLong(0)) { mutableListOf() }
            .add(Member(c.getLong(1), c.str(2), c.str(3), unpack(c.getBlob(4)), c.getDouble(5)))
        out
    } }

    fun reject(clip: String, speaker: String, personId: Long) {
        writableDatabase.execSQL("INSERT OR IGNORE INTO rejections(clip, speaker, person_id) VALUES (?, ?, ?)", arrayOf<Any>(clip, speaker, personId))
    }

    fun rejected(clip: String, speaker: String): Set<Long> =
        readableDatabase.rawQuery("SELECT person_id FROM rejections WHERE clip = ? AND speaker = ?", arrayOf(clip, speaker)).use { c ->
            buildSet { while (c.moveToNext()) add(resolve(c.getLong(0))) }
        }

    /** Someone decided who this voice is (named it, confirmed it, read a passage): never second-guessed. */
    fun decidedByHand(clip: String, speaker: String): Boolean =
        readableDatabase.rawQuery("SELECT 1 FROM voiceprints WHERE clip = ? AND speaker = ? AND origin IN ('manual', 'confirmed') LIMIT 1",
            arrayOf(clip, speaker)).use { it.moveToFirst() }

    /** A voice now matched to a named person leaves its unnamed cluster; clusters left empty go. */
    fun releaseFromCluster(clip: String, speaker: String) {
        val db = writableDatabase
        db.execSQL("DELETE FROM voiceprints WHERE clip = ? AND speaker = ? AND origin = 'auto' AND person_id IN (SELECT id FROM people WHERE name IS NULL)",
            arrayOf(clip, speaker))
        db.execSQL("DELETE FROM people WHERE name IS NULL AND id NOT IN (SELECT DISTINCT person_id FROM voiceprints)")
    }

    /** Fold one unnamed voice into another; transcripts that recorded the old id follow the merge. */
    fun mergeUnnamed(from: Long, into: Long) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.execSQL("UPDATE voiceprints SET person_id = ? WHERE person_id = ?", arrayOf<Any>(into, from))
            db.execSQL("INSERT OR REPLACE INTO merges(from_id, into_id) VALUES (?, ?)", arrayOf<Any>(from, into))
            db.execSQL("DELETE FROM people WHERE id = ? AND name IS NULL", arrayOf<Any>(from))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    /**
     * A clip transcribed again: what was decided about its voices follows them
     * to their new labels ([voices], CarryOver.speakers) -- voiceprints, names
     * given, "not them" answers, Boswell's learned voice -- as CarryOver.relabel
     * plans. Returns what couldn't follow, in words, for the log.
     */
    fun carryOver(clip: String, voices: Map<String, String?>): List<String> {
        val db = writableDatabase
        val rows = mutableListOf<net.boswell.phone.process.CarryOver.Row>()
        val kinds = mapOf(net.boswell.phone.process.CarryOver.VOICEPRINTS to "SELECT v.rowid, v.speaker, v.origin != 'auto' OR p.name IS NOT NULL OR p.kind IS NOT NULL " +
                "FROM voiceprints v JOIN people p ON p.id = v.person_id WHERE v.clip = ?",
            "assigned" to "SELECT rowid, speaker, 1 FROM assigned WHERE clip = ?",
            "rejections" to "SELECT rowid, speaker, 1 FROM rejections WHERE clip = ?",
            "boswell_voice" to "SELECT rowid, speaker, 0 FROM boswell_voice WHERE clip = ?")
        for ((table, sql) in kinds) db.rawQuery(sql, arrayOf(clip)).use { c ->
            while (c.moveToNext()) rows += net.boswell.phone.process.CarryOver.Row(table, c.getLong(0), c.str(1), c.getInt(2) == 1)
        }
        val plan = net.boswell.phone.process.CarryOver.relabel(rows, voices)
        val notes = mutableListOf<String>()
        db.beginTransaction()
        try {
            for (r in plan.unlink) {
                db.execSQL("UPDATE voiceprints SET speaker = NULL WHERE rowid = ?", arrayOf<Any>(r.id))
                notes += "$clip ${r.label}: voice not heard again; its voiceprint stays with its person"
            }
            for (r in plan.drop) {
                db.execSQL("DELETE FROM ${r.table} WHERE rowid = ?", arrayOf<Any>(r.id))
                when (r.table) {
                    "assigned" -> notes += "$clip ${r.label}: voice not heard again; the name given to it there has nothing to go to"
                    "rejections" -> notes += "$clip ${r.label}: voice not heard again; its \"not them\" answer has nothing to go to"
                }
            }
            // Last, and in two steps, so two voices that swapped labels never meet on one, nor a voice on a gone one's (assigned and rejections are keyed by it).
            for ((r, to) in plan.move) db.execSQL("UPDATE ${r.table} SET speaker = ? WHERE rowid = ?", arrayOf<Any>("~$to", r.id))
            for (t in kinds.keys) db.execSQL("UPDATE $t SET speaker = substr(speaker, 2) WHERE clip = ? AND speaker LIKE '~%'", arrayOf(clip))
            db.execSQL("DELETE FROM people WHERE name IS NULL AND kind IS NULL AND id NOT IN (SELECT DISTINCT person_id FROM voiceprints)")
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return notes
    }

    // ------------------------------------------------------------- Boswell

    /**
     * Boswell's own voice, learned from recordings where what it said lined
     * up with the words heard (BoswellLines), one set per text-to-speech
     * voice: a new voice is learned afresh. Kept apart from `voiceprints`, so
     * it can never be a person, an unnamed voice, or anyone's competition.
     */
    fun addBoswellPrint(voice: String, vec: FloatArray, seconds: Double, clip: String, speaker: String) {
        if (!Matching.usable(vec)) return
        val v = Matching.unit(vec)
        val db = writableDatabase
        db.execSQL("DELETE FROM boswell_voice WHERE clip = ? AND speaker = ?", arrayOf(clip, speaker))
        db.insert("boswell_voice", null, ContentValues().apply {
            put("voice", voice); put("vec", pack(v)); put("dim", v.size); put("seconds", seconds)
            put("clip", clip); put("speaker", speaker); put("created", now())
        })
        pruneBoswell(voice, v.size)
    }

    /** The most recent are enough: the voice doesn't change, only the room. */
    private fun pruneBoswell(voice: String, dim: Int) = writableDatabase.execSQL(
        """DELETE FROM boswell_voice WHERE voice = ? AND dim = ? AND id NOT IN
            (SELECT id FROM boswell_voice WHERE voice = ? AND dim = ? ORDER BY created DESC LIMIT $BOSWELL_PRINTS)""",
        arrayOf<Any>(voice, dim, voice, dim))

    /**
     * People someone named for Boswell's voice before it knew itself
     * (BoswellLines.isBoswellName), never [owner]: whether or not they have
     * a voiceprint under the model in use.
     */
    fun boswellNamed(owner: Long?): List<Long> = readableDatabase.rawQuery("SELECT id, name FROM people WHERE name IS NOT NULL", null).use { c ->
        buildList { while (c.moveToNext()) if (net.boswell.phone.process.BoswellLines.isBoswellName(c.getString(1)) && c.getLong(0) != owner) add(c.getLong(0)) }
    }

    /**
     * A person who was really Boswell's voice stops being a person: their
     * voiceprints long enough to be references (any model's) become Boswell's
     * learned voice for [voice], and everything that made them someone goes --
     * the person, voiceprints, assignments, "not them" answers, and the voices
     * merged into them. Safe to repeat.
     */
    fun retireAsBoswell(personIds: Collection<Long>, voice: String) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (pid in personIds) {
                // Every id that resolves to them: transcripts and assignments may hold an older one.
                val all = mutableSetOf(pid)
                var frontier = listOf(pid)
                while (frontier.isNotEmpty()) {
                    frontier = frontier.flatMap { into ->
                        db.rawQuery("SELECT from_id FROM merges WHERE into_id = ?", arrayOf(into.toString())).use { c ->
                            buildList { while (c.moveToNext()) add(c.getLong(0)) }
                        }
                    }.filter { all.add(it) }
                }
                db.execSQL("""INSERT INTO boswell_voice(voice, vec, dim, seconds, clip, speaker, created)
                    SELECT ?, vec, dim, seconds, clip, speaker, created FROM voiceprints v
                    WHERE person_id = ? AND impure = 0 AND seconds >= ${Matching.MIN_PRINT_SECONDS}
                      AND NOT EXISTS (SELECT 1 FROM boswell_voice b WHERE b.clip IS v.clip AND b.speaker IS v.speaker AND b.dim = v.dim)""",
                    arrayOf<Any>(voice, pid))
                val ids = all.joinToString(",")
                db.execSQL("DELETE FROM voiceprints WHERE person_id = ?", arrayOf<Any>(pid))
                db.execSQL("DELETE FROM assigned WHERE person_id IN ($ids)")
                db.execSQL("DELETE FROM rejections WHERE person_id IN ($ids)")
                db.execSQL("DELETE FROM merges WHERE into_id IN ($ids) OR from_id IN ($ids)")
                db.execSQL("DELETE FROM people WHERE id = ?", arrayOf<Any>(pid))
            }
            db.rawQuery("SELECT DISTINCT dim FROM boswell_voice WHERE voice = ?", arrayOf(voice)).use { c ->
                buildList { while (c.moveToNext()) add(c.getInt(0)) }
            }.forEach { pruneBoswell(voice, it) }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    /** Boswell's voice learned before the phone knew which TTS voice it was: it's the one in use now. */
    fun adoptBoswellPrints(voice: String) =
        writableDatabase.execSQL("UPDATE boswell_voice SET voice = ? WHERE voice = ?", arrayOf<Any>(voice, UNKNOWN_VOICE))

    /** Boswell's voiceprints for one TTS voice, under the model in use. */
    fun boswellPrints(voice: String): List<FloatArray> = readableDatabase.rawQuery(
        "SELECT vec FROM boswell_voice WHERE voice = ? AND dim = ${Matching.model.dim}", arrayOf(voice)).use { c ->
        buildList { while (c.moveToNext()) add(unpack(c.getBlob(0))) }
    }

    /** How alike [vec] is to Boswell's voice (best print), or null with nothing learned yet. */
    fun boswellScore(vec: FloatArray, voice: String?): Double? {
        if (voice == null || !Matching.usable(vec)) return null
        val v = Matching.unit(vec)
        return boswellPrints(voice).maxOfOrNull { Matching.dot(v, it) }
    }

    /**
     * "Not Boswell": these recordings are never labeled Boswell's again (not
     * when transcribed again either), and anything learned from them goes.
     */
    fun notBoswell(clips: Collection<String>) {
        val db = writableDatabase
        for (c in clips) {
            db.execSQL("INSERT OR IGNORE INTO not_boswell(clip) VALUES (?)", arrayOf(c))
            db.execSQL("DELETE FROM boswell_voice WHERE clip = ?", arrayOf(c))
        }
    }

    fun isNotBoswell(clip: String): Boolean =
        readableDatabase.rawQuery("SELECT 1 FROM not_boswell WHERE clip = ?", arrayOf(clip)).use { it.moveToFirst() }

    // ------------------------------------------------------------ contacts

    data class Link(val personId: Long, val name: String?, val contact: String, val mayText: String)

    fun link(personId: Long, contactUri: String?) {
        writableDatabase.execSQL("UPDATE people SET contact = ? WHERE id = ?", arrayOf<Any?>(contactUri, personId))
    }

    fun setMayText(personId: Long, mode: String) {
        writableDatabase.execSQL("UPDATE people SET may_text = ? WHERE id = ?", arrayOf<Any>(mode, personId))
    }

    fun linkOf(personId: Long): Link? = readableDatabase.rawQuery(
        "SELECT id, name, contact, COALESCE(may_text, 'off') FROM people WHERE id = ? AND contact IS NOT NULL", arrayOf(personId.toString())).use { c ->
        if (c.moveToFirst()) Link(c.getLong(0), c.str(1), c.getString(2), c.getString(3)) else null
    }

    fun links(): List<Link> = readableDatabase.rawQuery(
        "SELECT id, name, contact, COALESCE(may_text, 'off') FROM people WHERE contact IS NOT NULL", null).use { c ->
        buildList { while (c.moveToNext()) add(Link(c.getLong(0), c.str(1), c.getString(2), c.getString(3))) }
    }

    fun kindOf(personId: Long): String? = readableDatabase.rawQuery("SELECT kind FROM people WHERE id = ?", arrayOf(personId.toString())).use { c ->
        if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null
    }

    fun nameOf(personId: Long): String? = readableDatabase.rawQuery("SELECT name FROM people WHERE id = ?", arrayOf(personId.toString())).use { c ->
        if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null
    }

    /** Follow merges from an id a transcript recorded to the person it is now. */
    /**
     * [currentPerson] for many voices at once (a conversation rebuild asks it of every voice
     * there is): the same answers from three reads, instead of two or three queries a voice.
     */
    fun currentPeople(): (clip: String, label: String, recorded: Long?) -> Long? = whole { db ->
        val merges = HashMap<Long, Long>()
        db.rawQuery("SELECT from_id, into_id FROM merges", null).use { c -> while (c.moveToNext()) merges[c.getLong(0)] = c.getLong(1) }
        fun follow(id: Long): Long { var x = id; repeat(32) { x = merges[x] ?: return x }; return x }
        val filed = HashMap<Pair<String, String>, Long>()
        db.rawQuery("SELECT clip, speaker, person_id FROM voiceprints WHERE clip IS NOT NULL AND speaker IS NOT NULL ORDER BY id", null).use { c ->
            while (c.moveToNext()) filed.putIfAbsent(c.getString(0) to c.getString(1), c.getLong(2))
        }
        val assigned = HashMap<Pair<String, String>, Long>()
        db.rawQuery("SELECT clip, speaker, person_id FROM assigned", null).use { c ->
            while (c.moveToNext()) assigned[c.getString(0) to c.getString(1)] = c.getLong(2)
        }
        val answer: (String, String, Long?) -> Long? = { clip, label, recorded -> filed[clip to label] ?: assigned[clip to label]?.let(::follow) ?: recorded?.let(::follow) }
        answer
    }

    fun resolve(personId: Long): Long {
        var id = personId
        repeat(32) {
            val next = readableDatabase.rawQuery("SELECT into_id FROM merges WHERE from_id = ?", arrayOf(id.toString())).use { c ->
                if (c.moveToFirst()) c.getLong(0) else null
            } ?: return id
            id = next
        }
        return id
    }

    /**
     * Who a clip's voice is now. A voice filed as a voiceprint answers through
     * that row, which follows naming and undo; a voice matched to a named
     * person answers through the id recorded then, following any merge.
     */
    fun currentPerson(clip: String, label: String, recorded: Long?): Long? =
        readableDatabase.rawQuery("SELECT person_id FROM voiceprints WHERE clip = ? AND speaker = ? LIMIT 1", arrayOf(clip, label)).use { c ->
            if (c.moveToFirst()) c.getLong(0) else null
        } ?: readableDatabase.rawQuery("SELECT person_id FROM assigned WHERE clip = ? AND speaker = ?", arrayOf(clip, label)).use { c ->
            if (c.moveToFirst()) resolve(c.getLong(0)) else null
        } ?: recorded?.let(::resolve)

    /** One clip voice's voiceprint to keep as a sample when it's filed by hand ([refile]). */
    class Sample(val clip: String, val speaker: String, val vec: FloatArray, val seconds: Double, val origin: String)

    /**
     * Someone said who these clip voices ([slots], clip and diarized label) are: [to]. Every
     * answer about them is moved at once, because [currentPerson] reads a voiceprint row
     * before an assignment, so an assignment alone left a voice auto-filed under someone
     * still theirs. Their voiceprints, auto or by hand, go to [to] (nothing is deleted);
     * they're assigned to [to]; and whoever they were before is rejected for them, so
     * neither a recheck nor a Redo matches them back. [from] is who the caller showed them
     * as, for voices only a transcript recorded. [sample] is kept as a voiceprint of [to]
     * (raised to its origin if that voice already has one). Unnamed voices they were
     * taken from and left with nothing go. Returns everyone they were and [to], for
     * regrouping (Archive.sync).
     */
    fun refile(slots: Collection<Pair<String, String>>, to: Long, sample: Sample? = null, from: Long? = null): Set<Long> {
        val db = writableDatabase
        val touched = mutableSetOf(to)
        db.beginTransaction()
        try {
            for ((clip, speaker) in slots.distinct()) {
                val args = arrayOf(clip, speaker)
                val before = buildSet {
                    db.rawQuery("SELECT DISTINCT person_id FROM voiceprints WHERE clip = ? AND speaker = ?", args).use { c -> while (c.moveToNext()) add(c.getLong(0)) }
                    db.rawQuery("SELECT person_id FROM assigned WHERE clip = ? AND speaker = ?", args).use { c -> while (c.moveToNext()) add(resolve(c.getLong(0))) }
                    from?.let { add(resolve(it)) }
                } - to
                db.execSQL("UPDATE voiceprints SET person_id = ?, source_cluster = NULL WHERE clip = ? AND speaker = ?", arrayOf<Any>(to, clip, speaker))
                db.execSQL("INSERT OR REPLACE INTO assigned(clip, speaker, person_id) VALUES (?, ?, ?)", arrayOf<Any>(clip, speaker, to))
                // A "not them" for who it is now no longer holds.
                val stale = db.rawQuery("SELECT person_id FROM rejections WHERE clip = ? AND speaker = ?", args).use { c ->
                    buildList { while (c.moveToNext()) add(c.getLong(0)) } }.filter { resolve(it) == to }
                for (p in stale) db.execSQL("DELETE FROM rejections WHERE clip = ? AND speaker = ? AND person_id = ?", arrayOf<Any>(clip, speaker, p))
                for (p in before) reject(clip, speaker, p)
                touched += before
            }
            sample?.let { s ->
                val has = db.rawQuery("SELECT 1 FROM voiceprints WHERE clip = ? AND speaker = ? LIMIT 1", arrayOf(s.clip, s.speaker)).use { it.moveToFirst() }
                if (!has && Matching.usable(s.vec)) addVoiceprint(to, s.vec, s.seconds, s.clip, s.speaker, s.origin)
                else if (s.origin != "auto") db.execSQL("UPDATE voiceprints SET origin = ? WHERE clip = ? AND speaker = ? AND origin = 'auto'", arrayOf<Any>(s.origin, s.clip, s.speaker))
            }
            // Unnamed voices these were taken from and left with nothing go, as releaseFromCluster tidies;
            // a TV and a voice known only by assignment stay.
            for (p in touched - to) db.execSQL("""DELETE FROM people WHERE id = ? AND name IS NULL AND kind IS NULL
                AND id NOT IN (SELECT DISTINCT person_id FROM voiceprints) AND id NOT IN (SELECT person_id FROM assigned)""", arrayOf<Any>(p))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return touched
    }

    /** Name a voice that has no voiceprint (or add to one that has): this clip voice is this person. */
    fun assign(clip: String, speaker: String, personId: Long) {
        writableDatabase.execSQL("INSERT OR REPLACE INTO assigned(clip, speaker, person_id) VALUES (?, ?, ?)", arrayOf<Any>(clip, speaker, personId))
    }

    companion object {
        private const val MERGES = "CREATE TABLE IF NOT EXISTS merges (from_id INTEGER PRIMARY KEY, into_id INTEGER NOT NULL)"
        /** "Not them" and "No" answers: this voice in this clip is not this person, so never suggest or match it again. */
        /** A voice someone named that has no voiceprint to file (too short to embed): who it is, directly. */
        private const val ASSIGNED = "CREATE TABLE IF NOT EXISTS assigned (clip TEXT NOT NULL, speaker TEXT NOT NULL, person_id INTEGER NOT NULL, PRIMARY KEY (clip, speaker))"
        private const val BOSWELL_VOICE = """CREATE TABLE IF NOT EXISTS boswell_voice (id INTEGER PRIMARY KEY, voice TEXT NOT NULL, vec BLOB NOT NULL,
            dim INTEGER NOT NULL, seconds REAL, clip TEXT, speaker TEXT, created REAL)"""
        private const val NOT_BOSWELL = "CREATE TABLE IF NOT EXISTS not_boswell (clip TEXT PRIMARY KEY)"
        /** LabelCheck questions already answered, by LabelCheck.Item.key. */
        private const val LABEL_CHECKS = "CREATE TABLE IF NOT EXISTS label_checks (key TEXT PRIMARY KEY, created REAL)"
        /** Unnamed voices changed (added or gone) since the cohort was made, as a share of it, before it's made again. */
        const val COHORT_DRIFT = 0.05
        private class Cached(val key: Triple<String, Long?, Int>, val field: Map<Long, Long>, val norm: AsNorm.Norm)
        @Volatile private var cached: Cached? = null
        /** The TTS voice of Boswell prints learned before any answer was spoken (BoswellPerson): whichever speaks first. */
        const val UNKNOWN_VOICE = "?"
        /** Boswell voiceprints kept per TTS voice and model. */
        private const val BOSWELL_PRINTS = 30
        private const val REJECTIONS = "CREATE TABLE IF NOT EXISTS rejections (clip TEXT NOT NULL, speaker TEXT NOT NULL, person_id INTEGER NOT NULL, PRIMARY KEY (clip, speaker, person_id))"

        fun pack(v: FloatArray): ByteArray = ByteBuffer.allocate(v.size * 4).order(ByteOrder.LITTLE_ENDIAN).apply { v.forEach { putFloat(it) } }.array()
        fun unpack(b: ByteArray): FloatArray { val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN); return FloatArray(b.size / 4) { bb.float } }
    }
}
