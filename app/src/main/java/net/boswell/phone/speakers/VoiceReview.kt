package net.boswell.phone.speakers

import android.content.Context
import net.boswell.phone.archive.Archive
import net.boswell.phone.audio.writeAtomically
import net.boswell.phone.capture.logged
import net.boswell.phone.process.Candidate
import net.boswell.phone.process.ProcessingWorker
import net.boswell.phone.process.Transcript
import net.boswell.phone.process.TranscriptJson
import java.io.File

/**
 * A voice that might be someone Boswell knows: an unnamed voice (every
 * recording of it at once) or a single voice too short to have been filed
 * as one. [score] is how alike they are, on the matching scale.
 */
data class Suggestion(
    val personId: Long,
    val personName: String,
    val score: Double,
    /** The unnamed voice this is, or null for a single voice in one clip. */
    val clusterId: Long?,
    val recordings: Int,
    val seconds: Double,
    /** A recording of it to hear or read. */
    val clip: String,
    val label: String,
) {
    val key: String get() = clusterId?.let { "c$it" } ?: "s$clip|$label"
}

/**
 * Taking another look at who spoke, after Boswell has learned more.
 *
 * Voices are matched once, when a clip is transcribed, against whoever was
 * known then. Every transcript keeps each voice's voiceprint, so looking
 * again needs no audio: [recheck] matches the voices nobody is attached to
 * against everyone known now, with the same rules as a fresh clip, and
 * records a confident match in that transcript as if it had just been made.
 * What people decided by hand is never touched, and a voice taken off
 * someone ("Not them", "No") is never matched to them again.
 *
 * What is close but not sure is left for a person to answer ([suggestions]):
 * one answer about an unnamed voice covers every recording of it, and each
 * "yes" teaches Boswell another condition the person sounds like, so the
 * next [recheck] finds more by itself.
 */
class VoiceReview(private val context: Context) {

    /**
     * [people]: the unnamed voices folded together, both sides of each (Archive.sync's people).
     * [read]: transcripts read to do it.
     */
    data class Recheck(val matched: Int, val merged: Int, val people: Set<Long> = emptySet(), val read: Int = 0)

    /**
     * Which voices to look at comes from the archive's index, which keeps
     * every voice's print, speech, loudness and what its transcript said: a
     * transcript is read and written again only when one of its voices
     * would come out differently (matched, another top candidate, another
     * score), so a recheck no longer reads thousands of transcripts to
     * change a few. Those are rechecked whole, as before, from the file.
     */
    fun recheck(): Recheck {
        val store = SpeakerStore(context)
        try {
            var matched = 0
            var read = 0
            val refs = store.namedRefs()
            field = store.unnamedField()
            norm = store.norm(refs, field)
            if (refs.isNotEmpty()) {
                val dir = ProcessingWorker.transcriptsDir(context)
                for (clip in due(store, refs)) { matched += recheckFile(File(dir, clip.removeSuffix(".wav") + ".json"), store, refs); read++ }
            }
            val merges = tidy(store)
            return Recheck(matched, merges.size, merges.flatMapTo(HashSet()) { listOf(it.first, it.second) }, read)
        } finally { store.close() }
    }

    /**
     * The clips [recheckFile] would change, from the index: each voice that
     * [recheckFile] would look at, matched the same way from the same
     * inputs, and kept if it comes out matched or [differs]. Clips with a
     * line nobody was attributed to too, as the file may still give it one.
     * Brings the index up to date first, so it says what the files say.
     */
    private fun due(store: SpeakerStore, refs: List<Matching.Reference>): Set<String> = Archive(context).use { archive ->
        archive.sync(store)
        val known = Known(store)
        val voices = mutableListOf<Archive.IndexedVoice>()
        val pool = mutableListOf<Pooling.Voice>()
        archive.eachVoice { v ->
            // Boswell's own voice is nobody to match, nor to pool with.
            if (v.decision == net.boswell.phone.process.BoswellLines.DECISION) return@eachVoice
            Pooling.clipTime(v.clip)?.let { time ->
                if (v.snrDb != null && v.snrDb >= Pooling.MIN_SNR && Matching.usable(v.emb!!)) pool += Pooling.Voice(v.clip, time, Matching.unit(v.emb), v.seconds, v.snrDb)
            }
            if (known.candidate(v.clip, v.label, v.personId)) voices += v
        }
        earlier = Pooling.Known(pool)
        val due = LinkedHashSet(archive.clipsWithOrphans())
        for (v in voices) {
            if (v.clip in due) continue
            val no = known.rejected(v.clip, v.label)
            val own = known.own(v.clip, v.label)
            val r = matchVoice(v.emb!!, v.clip, v.seconds, v.snrDb, if (no.isEmpty()) refs else refs.filter { it.personId !in no },
                if (own.isEmpty()) field else field.filter { it.voiceprintId !in own })
            if (r.decision == Matching.Decision.MATCHED || differs(r, v.top, v.score)) due += v.clip
        }
        due
    }

    /**
     * What the speaker store says about every clip voice, read at once (a
     * recheck asks it of thousands): the same answers as decidedByHand,
     * currentPerson, nameOf, rejected and [ownRows], one voice at a time.
     */
    private class Known(store: SpeakerStore) {
        private val who = store.currentPeople()
        private val hand = HashSet<Pair<String, String>>()
        private val rows = HashMap<Pair<String, String>, MutableSet<Long>>()
        private val named = HashSet<Long>()
        private val no = HashMap<Pair<String, String>, MutableSet<Long>>()

        init {
            val db = store.readableDatabase
            db.rawQuery("SELECT id, clip, speaker, origin FROM voiceprints WHERE clip IS NOT NULL AND speaker IS NOT NULL", null).use { c ->
                while (c.moveToNext()) {
                    val k = c.getString(1) to c.getString(2)
                    rows.getOrPut(k) { HashSet() } += c.getLong(0)
                    if (c.getString(3) in HAND) hand += k
                }
            }
            db.rawQuery("SELECT id FROM people WHERE name IS NOT NULL", null).use { c -> while (c.moveToNext()) named += c.getLong(0) }
            val merges = HashMap<Long, Long>()
            db.rawQuery("SELECT from_id, into_id FROM merges", null).use { c -> while (c.moveToNext()) merges[c.getLong(0)] = c.getLong(1) }
            fun resolve(id: Long): Long { var x = id; repeat(32) { x = merges[x] ?: return x }; return x }
            db.rawQuery("SELECT clip, speaker, person_id FROM rejections", null).use { c ->
                while (c.moveToNext()) no.getOrPut(c.getString(0) to c.getString(1)) { HashSet() } += resolve(c.getLong(2))
            }
        }

        /** Not decided by hand, and not someone named now: a voice a recheck looks at. */
        fun candidate(clip: String, label: String, recorded: Long?): Boolean {
            if ((clip to label) in hand) return false
            val now = who(clip, label, recorded)
            return now == null || now !in named
        }

        /** Not decided by hand and nobody at all now (neither named nor an unnamed voice): a single voice to suggest. */
        fun single(clip: String, label: String, recorded: Long?): Boolean = (clip to label) !in hand && who(clip, label, recorded) == null

        fun rejected(clip: String, label: String): Set<Long> = no[clip to label].orEmpty()
        fun own(clip: String, label: String): Set<Long> = rows[clip to label].orEmpty()

        companion object { val HAND = setOf("manual", "confirmed") }
    }

    /** This clip voice's own filed voiceprints, which must not compete with it. */
    private fun ownRows(store: SpeakerStore, clip: String, label: String): Set<Long> =
        store.readableDatabase.rawQuery("SELECT id FROM voiceprints WHERE clip = ? AND speaker = ?", arrayOf(clip, label)).use { c ->
            buildSet { while (c.moveToNext()) add(c.getLong(0)) }
        }

    private var field: List<Matching.Reference> = emptyList()
    private var norm: AsNorm.Norm? = null
    private var earlier: Pooling.Earlier? = null

    /**
     * A transcript's voice matched as a new one would be: pooled with the
     * clean voices just before it and normalized (Pooling, AsNorm). A voice
     * of a transcript made before the loudness was kept is neither pooled
     * nor judged by it; normalized all the same.
     */
    private fun matchVoice(emb: FloatArray, clip: String, seconds: Double, snrDb: Double?, refs: List<Matching.Reference>,
                           field: List<Matching.Reference>): Matching.Result {
        val near = snrDb?.takeIf { it >= Pooling.MIN_SNR }?.let { earlier?.before(clip) }.orEmpty()
        val vec = Pooling.pooled(emb, seconds, snrDb, clip, near)
        return Matching.match(vec, refs, field, seconds.takeIf { it > 0 }, snrDb, norm?.forVoice(vec, clip, Pooling.clipTime(clip)))
    }

    /** A voice left unmatched still counts as changed with another top candidate or a score moved by more than rounding. */
    private fun differs(r: Matching.Result, top: Long?, score: Double): Boolean =
        r.candidates.firstOrNull()?.personId != top || kotlin.math.abs(r.score - score) > 1e-4

    private fun recheckFile(f: File, store: SpeakerStore, refs: List<Matching.Reference>): Int {
        val t = runCatching { TranscriptJson.json.decodeFromString(Transcript.serializer(), f.readText()) }.logged("recheck: reading ${f.name}").getOrNull() ?: return 0
        if (t.speakers.isEmpty()) return 0
        var matched = 0
        // Lines left without a speaker by an older transcript get the nearest one's.
        val orphansFixed = net.boswell.phone.process.Lines.attributeOrphans(t.segments)
        var changed = orphansFixed != null
        val updated = t.speakers.mapValues { (label, sp) ->
            // Boswell's own voice is nobody to match.
            if (net.boswell.phone.process.BoswellLines.isBoswell(sp)) return@mapValues sp
            val emb = t.embeddings[label]?.toFloatArray() ?: return@mapValues sp
            if (store.decidedByHand(t.clip, label)) return@mapValues sp
            val now = store.currentPerson(t.clip, label, sp.personId)
            if (now != null && store.nameOf(now) != null) return@mapValues sp
            val no = store.rejected(t.clip, label)
            val own = ownRows(store, t.clip, label)
            val r = matchVoice(emb, t.clip, sp.seconds, sp.snrDb, if (no.isEmpty()) refs else refs.filter { it.personId !in no }, field.filter { it.voiceprintId !in own })
            val candidates = r.candidates.map { Candidate(it.personId, store.nameOf(it.personId), it.score, it.voiceprintId) }
            if (r.decision == Matching.Decision.MATCHED) {
                val pid = r.personId!!
                store.releaseFromCluster(t.clip, label)
                matched++; changed = true
                sp.copy(name = store.nameOf(pid), score = r.score, decision = "matched", margin = r.margin, candidates = candidates, personId = pid)
            } else {
                if (differs(r, sp.candidates.firstOrNull()?.personId, sp.score)) changed = true
                sp.copy(score = r.score, decision = r.decision.name.lowercase(), margin = r.margin, candidates = candidates)
            }
        }
        if (changed) writeAtomically(f, TranscriptJson.json.encodeToString(Transcript.serializer(),
            t.copy(speakers = updated, segments = orphansFixed ?: t.segments)).toByteArray())
        return matched
    }

    /**
     * Fold together unnamed voices that are clearly one person: on average
     * as alike as CLUSTER_MIN, the bar a voice clears to join one. Measured
     * on real data, the looser "any one pair alike" rule chained a stranger
     * into the owner's voice, so the average it is. Returns each fold, (from, into).
     */
    private fun tidy(store: SpeakerStore): List<Pair<Long, Long>> {
        val groups = store.unnamedClusters().mapValues { (_, m) -> m.map { Matching.unit(it.vec) } }.toMutableMap()
        val merged = mutableListOf<Pair<Long, Long>>()
        while (groups.size > 1) {
            var best = -2.0; var a = -1L; var b = -1L
            val ids = groups.keys.toList()
            for (i in ids.indices) for (j in i + 1 until ids.size) {
                val x = groups.getValue(ids[i]); val y = groups.getValue(ids[j])
                var s = 0.0
                for (u in x) for (v in y) s += Matching.dot(u, v)
                s /= (x.size * y.size)
                if (s > best) { best = s; a = ids[i]; b = ids[j] }
            }
            if (best < Matching.CLUSTER_MIN) break
            // The smaller joins the larger.
            val (from, into) = if (groups.getValue(a).size < groups.getValue(b).size) a to b else b to a
            store.mergeUnnamed(from, into)
            groups[into] = groups.getValue(into) + groups.getValue(from)
            groups.remove(from)
            merged += from to into
        }
        return merged
    }

    /**
     * Voices that might be someone known, closest first. An unnamed voice is
     * scored by how alike its recordings are to the person on average; a
     * single voice by the ordinary match. Anything the matcher would have
     * called uncertain qualifies (MATCH_LOW and up). Single voices come
     * from the archive's index, not from reading every transcript.
     */
    fun suggestions(limit: Int = 60): List<Suggestion> {
        val store = SpeakerStore(context)
        val archive = Archive(context)
        try {
            val refs = store.namedRefs()
            if (refs.isEmpty()) return emptyList()
            norm = store.norm(refs)
            archive.sync(store)
            val known = Known(store)
            val out = mutableListOf<Suggestion>()
            val filed = HashSet<String>()

            for ((cid, members) in store.unnamedClusters()) {
                val sums = HashMap<Long, Pair<Double, Int>>()
                for (m in members) {
                    if (m.clip != null && m.speaker != null) filed += "${m.clip}|${m.speaker}"
                    val no = if (m.clip != null && m.speaker != null) known.rejected(m.clip, m.speaker) else emptySet()
                    val v = Matching.unit(m.vec)
                    val best = HashMap<Long, Double>()
                    for (r in refs) if (r.personId !in no) {
                        val s = Matching.dot(v, r.vec)
                        if (s > (best[r.personId] ?: -2.0)) best[r.personId] = s
                    }
                    for ((p, s) in best) sums[p] = (sums[p]?.let { it.first + s to it.second + 1 }) ?: (s to 1)
                }
                // A person every recording of this voice was said not to be is out entirely.
                val (pid, sc) = sums.filter { it.value.second == members.size }.mapValues { it.value.first / it.value.second }
                    .maxByOrNull { it.value } ?: continue
                if (sc < Matching.MATCH_LOW) continue
                val sample = members.filter { it.clip != null && it.speaker != null }.maxByOrNull { it.seconds } ?: continue
                out += Suggestion(pid, store.nameOf(pid) ?: continue, sc, cid, members.size, members.sumOf { it.seconds }, sample.clip!!, sample.speaker!!)
            }

            val singles = mutableListOf<Archive.IndexedVoice>()
            val pool = mutableListOf<Pooling.Voice>()
            archive.eachVoice { v ->
                if (v.decision == net.boswell.phone.process.BoswellLines.DECISION) return@eachVoice
                Pooling.clipTime(v.clip)?.let { time ->
                    if (v.snrDb != null && v.snrDb >= Pooling.MIN_SNR && Matching.usable(v.emb!!)) pool += Pooling.Voice(v.clip, time, Matching.unit(v.emb), v.seconds, v.snrDb)
                }
                // Not named, nor filed in an unnamed voice (covered above).
                if ("${v.clip}|${v.label}" !in filed && v.seconds >= SINGLE_MIN_SECONDS && known.single(v.clip, v.label, v.personId)) singles += v
            }
            earlier = Pooling.Known(pool)
            for (v in singles) {
                val no = known.rejected(v.clip, v.label)
                val r = matchVoice(v.emb!!, v.clip, v.seconds, v.snrDb, if (no.isEmpty()) refs else refs.filter { it.personId !in no }, emptyList())
                if (r.decision != Matching.Decision.UNCERTAIN) continue
                val pid = r.candidates.first().personId
                out += Suggestion(pid, store.nameOf(pid) ?: continue, r.score, null, 1, v.seconds, v.clip, v.label)
            }
            // Likely ones first, and among them the voices with the most
            // recordings: one answer there labels the most.
            return out.sortedWith(compareByDescending<Suggestion> { it.score >= LIKELY }.thenByDescending { it.recordings }.thenByDescending { it.score })
                .take(limit)
        } finally { archive.close(); store.close() }
    }

    /** "Yes": an unnamed voice takes the person's name (undoable per group); a single voice becomes a confirmed sample. */
    fun confirm(s: Suggestion) {
        val store = SpeakerStore(context)
        try {
            if (s.clusterId != null) store.name(s.clusterId, s.personName)
            else {
                val t = transcript(s.clip) ?: return
                val emb = t.embeddings[s.label]?.toFloatArray()
                val secs = t.speakers[s.label]?.seconds
                // Long enough: a confirmed reference. Shorter: labeled, never a reference.
                if (emb != null && Matching.printable(secs)) store.addVoiceprint(s.personId, emb, secs, s.clip, s.label, "confirmed")
                else store.assign(s.clip, s.label, s.personId)
            }
        } finally { store.close() }
    }

    /** "No": never suggested or matched to them again. For an unnamed voice, that holds for each of its recordings. */
    fun reject(s: Suggestion) {
        val store = SpeakerStore(context)
        try {
            if (s.clusterId != null) {
                for (m in store.unnamedClusters()[s.clusterId].orEmpty())
                    if (m.clip != null && m.speaker != null) store.reject(m.clip, m.speaker, s.personId)
            } else store.reject(s.clip, s.label, s.personId)
        } finally { store.close() }
    }

    private fun transcript(clip: String): Transcript? {
        val f = File(ProcessingWorker.transcriptsDir(context), clip.removeSuffix(".wav") + ".json")
        return runCatching { TranscriptJson.json.decodeFromString(Transcript.serializer(), f.readText()) }.logged("review: reading ${f.name}").getOrNull()
    }

    companion object {
        /** Single voices shorter than this are too thin to put to anyone. */
        const val SINGLE_MIN_SECONDS = 1.5
        /** Where "possible" becomes "likely" in the review's wording and order; matching never uses it. */
        val LIKELY get() = Matching.model.likely
    }
}
