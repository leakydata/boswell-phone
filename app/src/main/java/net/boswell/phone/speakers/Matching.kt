package net.boswell.phone.speakers

import kotlin.math.sqrt

/**
 * Desktop Boswell's identity rules (web/speaker_store.py), ported unchanged.
 * The thresholds were derived there from 1,642 same-speaker and 2,141
 * different-speaker pairs and a held-out evaluation; the phone's voiceprints
 * are identical to the desktop's, so the numbers carry over as they are.
 */
object Matching {
    const val MATCH_HIGH = 0.75
    const val MATCH_LOW = 0.55
    const val MARGIN_MIN = 0.15
    const val MARGIN_STRONG = 0.25
    /** An unnamed voice joins an unnamed cluster at or above this. */
    const val CLUSTER_MIN = 0.75
    const val MIN_VECTOR_NORM = 1e-6
    /** Voices with less speech than this are not filed into clusters (pipeline.scan_voices). */
    const val MIN_CLUSTER_SECONDS = 3.0

    enum class Decision { MATCHED, UNCERTAIN, NONE }

    /** decide(): one implementation, because the desktop once had two that disagreed. */
    fun decide(score: Double, margin: Double?): Decision {
        if (margin == null) {
            // Nobody to be a runner-up: the floor alone, and the strict one.
            return when {
                score >= MATCH_HIGH -> Decision.MATCHED
                score >= MATCH_LOW -> Decision.UNCERTAIN
                else -> Decision.NONE
            }
        }
        return when {
            score >= MATCH_HIGH && margin >= MARGIN_MIN -> Decision.MATCHED
            score >= MATCH_LOW && margin >= MARGIN_STRONG -> Decision.MATCHED   // clear of the field
            score >= MATCH_LOW -> Decision.UNCERTAIN
            else -> Decision.NONE
        }
    }

    data class Reference(val voiceprintId: Long, val personId: Long, val vec: FloatArray)
    data class Candidate(val personId: Long, val score: Double, val voiceprintId: Long)
    data class Result(val decision: Decision, val candidates: List<Candidate>, val score: Double, val margin: Double?) {
        val personId: Long? get() = if (decision == Decision.NONE) null else candidates.firstOrNull()?.personId
    }

    /**
     * Score a voice against every reference, best row per PERSON, and compare
     * the top two people. The margin must be between people, not rows: a
     * well-covered person owns the top several rows, and a row margin would
     * collapse to zero exactly where coverage is best.
     */
    fun match(vec: FloatArray, refs: List<Reference>, field: List<Reference> = emptyList()): Result {
        if (!usable(vec) || refs.isEmpty()) return Result(Decision.NONE, emptyList(), 0.0, 0.0)
        val v = unit(vec)
        val best = HashMap<Long, Candidate>()
        for (r in refs) {
            val s = dot(v, r.vec)
            val cur = best[r.personId]
            if (cur == null || s > cur.score) best[r.personId] = Candidate(r.personId, s, r.voiceprintId)
        }
        val ranked = best.values.sortedByDescending { it.score }
        val top = ranked.first()
        // The runner-up is the next named person -- or, as part of the field to
        // be clear of, the closest unnamed voice. With one named person (often
        // just the owner) there was no runner-up at all, so only the strict
        // 0.75 applied and most of the owner's own speech stayed "uncertain";
        // counting unnamed voices lets "clear of the field" apply as it does on
        // the desktop. They are only ever competition, never the answer.
        // The field only ever adds a match: a voice that clears the bar among
        // named people stays matched even if some unnamed voice (often another
        // fragment of the same person) is closer -- that once filed the
        // owner's own "Hey Boswell" (0.86 like them) under an unnamed voice.
        val namedMargin = ranked.getOrNull(1)?.let { top.score - it.score }
        val alone = decide(top.score, namedMargin)
        if (alone == Decision.MATCHED || field.isEmpty()) return Result(alone, ranked.take(3), top.score, namedMargin)
        val fieldBest = field.maxOf { dot(v, it.vec) }
        val margin = top.score - maxOf(ranked.getOrNull(1)?.score ?: -1.0, fieldBest)
        val withField = decide(top.score, margin)
        return if (withField == Decision.MATCHED) Result(withField, ranked.take(3), top.score, margin)
            else Result(alone, ranked.take(3), top.score, namedMargin)
    }

    /** The unnamed cluster this voice most resembles, if it clears CLUSTER_MIN. */
    fun bestCluster(vec: FloatArray, clusterRefs: List<Reference>): Pair<Long?, Double> {
        if (clusterRefs.isEmpty()) return null to 0.0
        val v = unit(vec)
        var bestPid = -1L
        var bestScore = -2.0
        for (r in clusterRefs) {
            val s = dot(v, r.vec)
            if (s > bestScore) { bestScore = s; bestPid = r.personId }
        }
        return (if (bestScore >= CLUSTER_MIN) bestPid else null) to bestScore
    }

    fun usable(v: FloatArray): Boolean = v.isNotEmpty() && v.all { it.isFinite() } && norm(v) > MIN_VECTOR_NORM

    fun norm(v: FloatArray): Double { var s = 0.0; for (x in v) s += x * x; return sqrt(s) }

    fun unit(v: FloatArray): FloatArray { val n = norm(v); return if (n > 0) FloatArray(v.size) { (v[it] / n).toFloat() } else v }

    fun dot(a: FloatArray, b: FloatArray): Double { var s = 0.0; for (i in a.indices) s += a[i] * b[i]; return s }
}
