package net.boswell.phone.speakers

import android.content.Context
import net.boswell.phone.audio.writeAtomically
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

    data class Recheck(val matched: Int, val merged: Int)

    fun recheck(): Recheck {
        val store = SpeakerStore(context)
        try {
            var matched = 0
            val refs = store.namedRefs()
            field = store.unnamedField()
            norm = store.norm(refs, field)
            if (refs.isNotEmpty()) {
                val dir = ProcessingWorker.transcriptsDir(context)
                earlier = Pooling.Index(dir)
                for (f in dir.listFiles { x -> x.extension == "json" }.orEmpty()) {
                    matched += recheckFile(f, store, refs)
                }
            }
            return Recheck(matched, tidy(store))
        } finally { store.close() }
    }

    /** This clip voice's own filed voiceprints, which must not compete with it. */
    private fun ownRows(store: SpeakerStore, clip: String, label: String): Set<Long> =
        store.readableDatabase.rawQuery("SELECT id FROM voiceprints WHERE clip = ? AND speaker = ?", arrayOf(clip, label)).use { c ->
            buildSet { while (c.moveToNext()) add(c.getLong(0)) }
        }

    private var field: List<Matching.Reference> = emptyList()
    private var norm: AsNorm.Norm? = null
    private var earlier: Pooling.Index? = null

    /**
     * A transcript's voice matched as a new one would be: pooled with the
     * clean voices just before it and normalized (Pooling, AsNorm). A voice
     * of a transcript made before the loudness was kept is neither pooled
     * nor judged by it; normalized all the same.
     */
    private fun matchVoice(emb: FloatArray, clip: String, sp: net.boswell.phone.process.SpeakerId, refs: List<Matching.Reference>,
                           field: List<Matching.Reference>): Matching.Result {
        val near = sp.snrDb?.takeIf { it >= Pooling.MIN_SNR }?.let { earlier?.before(clip) }.orEmpty()
        val vec = Pooling.pooled(emb, sp.seconds, sp.snrDb, clip, near)
        return Matching.match(vec, refs, field, sp.seconds.takeIf { it > 0 }, sp.snrDb, norm?.forVoice(vec, clip, Pooling.clipTime(clip)))
    }

    private fun recheckFile(f: File, store: SpeakerStore, refs: List<Matching.Reference>): Int {
        val t = runCatching { TranscriptJson.json.decodeFromString(Transcript.serializer(), f.readText()) }.getOrNull() ?: return 0
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
            val r = matchVoice(emb, t.clip, sp, if (no.isEmpty()) refs else refs.filter { it.personId !in no }, field.filter { it.voiceprintId !in own })
            val candidates = r.candidates.map { Candidate(it.personId, store.nameOf(it.personId), it.score, it.voiceprintId) }
            if (r.decision == Matching.Decision.MATCHED) {
                val pid = r.personId!!
                store.releaseFromCluster(t.clip, label)
                matched++; changed = true
                sp.copy(name = store.nameOf(pid), score = r.score, decision = "matched", margin = r.margin, candidates = candidates, personId = pid)
            } else {
                val top = candidates.firstOrNull()?.personId
                if (top != sp.candidates.firstOrNull()?.personId || kotlin.math.abs(r.score - sp.score) > 1e-4) changed = true
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
     * into the owner's voice, so the average it is.
     */
    private fun tidy(store: SpeakerStore): Int {
        val groups = store.unnamedClusters().mapValues { (_, m) -> m.map { Matching.unit(it.vec) } }.toMutableMap()
        var merged = 0
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
            merged++
        }
        return merged
    }

    /**
     * Voices that might be someone known, closest first. An unnamed voice is
     * scored by how alike its recordings are to the person on average; a
     * single voice by the ordinary match. Anything the matcher would have
     * called uncertain qualifies (MATCH_LOW and up).
     */
    fun suggestions(limit: Int = 60): List<Suggestion> {
        val store = SpeakerStore(context)
        try {
            val refs = store.namedRefs()
            if (refs.isEmpty()) return emptyList()
            norm = store.norm(refs)
            earlier = Pooling.Index(ProcessingWorker.transcriptsDir(context))
            val out = mutableListOf<Suggestion>()
            val filed = HashSet<String>()

            for ((cid, members) in store.unnamedClusters()) {
                val sums = HashMap<Long, Pair<Double, Int>>()
                for (m in members) {
                    if (m.clip != null && m.speaker != null) filed += "${m.clip}|${m.speaker}"
                    val no = if (m.clip != null && m.speaker != null) store.rejected(m.clip, m.speaker) else emptySet()
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

            for (f in ProcessingWorker.transcriptsDir(context).listFiles { x -> x.extension == "json" }.orEmpty()) {
                val t = runCatching { TranscriptJson.json.decodeFromString(Transcript.serializer(), f.readText()) }.getOrNull() ?: continue
                for ((label, sp) in t.speakers) {
                    if ("${t.clip}|$label" in filed || sp.seconds < SINGLE_MIN_SECONDS || net.boswell.phone.process.BoswellLines.isBoswell(sp)) continue
                    val emb = t.embeddings[label]?.toFloatArray() ?: continue
                    if (store.decidedByHand(t.clip, label)) continue
                    val now = store.currentPerson(t.clip, label, sp.personId)
                    if (now != null) continue          // named, or filed in an unnamed voice (covered above)
                    val no = store.rejected(t.clip, label)
                    val r = matchVoice(emb, t.clip, sp, if (no.isEmpty()) refs else refs.filter { it.personId !in no }, emptyList())
                    if (r.decision != Matching.Decision.UNCERTAIN) continue
                    val pid = r.candidates.first().personId
                    out += Suggestion(pid, store.nameOf(pid) ?: continue, r.score, null, 1, sp.seconds, t.clip, label)
                }
            }
            // Likely ones first, and among them the voices with the most
            // recordings: one answer there labels the most.
            return out.sortedWith(compareByDescending<Suggestion> { it.score >= LIKELY }.thenByDescending { it.recordings }.thenByDescending { it.score })
                .take(limit)
        } finally { store.close() }
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
        return runCatching { TranscriptJson.json.decodeFromString(Transcript.serializer(), f.readText()) }.getOrNull()
    }

    companion object {
        /** Single voices shorter than this are too thin to put to anyone. */
        const val SINGLE_MIN_SECONDS = 1.5
        /** Where "possible" becomes "likely" in the review's wording and order; matching never uses it. */
        val LIKELY get() = Matching.model.likely
    }
}
