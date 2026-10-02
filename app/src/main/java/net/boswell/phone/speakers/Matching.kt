package net.boswell.phone.speakers

import kotlin.math.sqrt

/**
 * Desktop Boswell's identity rules (web/speaker_store.py), ported unchanged.
 * The thresholds were derived there from 1,642 same-speaker and 2,141
 * different-speaker pairs and a held-out evaluation; the phone's voiceprints
 * are identical to the desktop's, so the numbers carry over as they are.
 */
object Matching {
    /** The voiceprint model in use; every threshold below is its own (VoiceModel). Set when a SpeakerStore opens. */
    @Volatile var model: net.boswell.phone.diarize.VoiceModel = net.boswell.phone.diarize.VoiceModel.WESPEAKER
    val MATCH_HIGH get() = model.matchHigh
    val MATCH_LOW get() = model.matchLow
    val MARGIN_MIN get() = model.marginMin
    val MARGIN_STRONG get() = model.marginStrong
    /** An unnamed voice joins an unnamed cluster at or above this. */
    val CLUSTER_MIN get() = model.clusterMin
    const val MIN_VECTOR_NORM = 1e-6
    /** Voices with less speech than this are not filed into clusters (pipeline.scan_voices). */
    const val MIN_CLUSTER_SECONDS = 3.0
    /**
     * Shortest speech kept as a voiceprint, however it arrives (named by hand,
     * confirmed in review, marked as a TV). A shorter voice is still matched
     * against the voiceprints, and can still be labeled -- it just never
     * becomes a reference, because a second or two of "yeah" makes an
     * unreliable one that pulls other voices toward that person.
     */
    const val MIN_PRINT_SECONDS = 3.0
    fun printable(seconds: Double?) = seconds != null && seconds >= MIN_PRINT_SECONDS

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
        @Suppress("NAME_SHADOWING") val refs = refs.filter { it.vec.size == vec.size }
        @Suppress("NAME_SHADOWING") val field = field.filter { it.vec.size == vec.size }
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

    /**
     * Cosine of two unit vectors. Vectors from different models (different
     * sizes) are not comparable: they score -1, so they never match -- an old
     * transcript's voiceprint meeting the new model's used to crash a re-check.
     */
    fun dot(a: FloatArray, b: FloatArray): Double {
        if (a.size != b.size) return -1.0
        var s = 0.0; for (i in a.indices) s += a[i] * b[i]; return s
    }
}
