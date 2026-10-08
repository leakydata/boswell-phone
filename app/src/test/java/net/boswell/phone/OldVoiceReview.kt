package net.boswell.phone

import android.content.Context
import net.boswell.phone.audio.writeAtomically
import net.boswell.phone.process.Candidate
import net.boswell.phone.process.ProcessingWorker
import net.boswell.phone.process.Transcript
import net.boswell.phone.process.TranscriptJson
import net.boswell.phone.speakers.AsNorm
import net.boswell.phone.speakers.Matching
import net.boswell.phone.speakers.Pooling
import net.boswell.phone.speakers.SpeakerStore
import net.boswell.phone.speakers.Suggestion
import net.boswell.phone.speakers.VoiceReview
import java.io.File

/**
 * VoiceReview's recheck and suggestions as they were before they read the
 * archive's index (0.9.1, commit 556a3fb), word for word: every transcript
 * read, every voice matched. The answer key for RecheckFromIndexTest.
 */
class OldVoiceReview(private val context: Context) {


    fun recheck(): VoiceReview.Recheck {
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
            return VoiceReview.Recheck(matched, tidy(store))
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
                    if ("${t.clip}|$label" in filed || sp.seconds < VoiceReview.SINGLE_MIN_SECONDS || net.boswell.phone.process.BoswellLines.isBoswell(sp)) continue
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
            return out.sortedWith(compareByDescending<Suggestion> { it.score >= VoiceReview.LIKELY }.thenByDescending { it.recordings }.thenByDescending { it.score })
                .take(limit)
        } finally { store.close() }
    }
}
