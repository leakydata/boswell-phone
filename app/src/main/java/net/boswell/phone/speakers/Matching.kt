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
    fun match(vec: FloatArray, refs: List<Reference>, field: List<Reference> = emptyList(), seconds: Double? = null): Result {
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
        if (alone == Decision.MATCHED) return Result(alone, ranked.take(3), top.score, namedMargin)
        val margin = if (field.isEmpty()) namedMargin
            else top.score - maxOf(ranked.getOrNull(1)?.score ?: -1.0, field.maxOf { dot(v, it.vec) })
        if (field.isNotEmpty() && decide(top.score, margin) == Decision.MATCHED) return Result(Decision.MATCHED, ranked.take(3), top.score, margin)
        // Without the field (suggestions) the owner's rule can't be checked: left uncertain, and asked.
        if (field.isNotEmpty() && isOwner(top, namedMargin, margin, seconds)) return Result(Decision.MATCHED, ranked.take(3), top.score, margin)
        return Result(alone, ranked.take(3), top.score, namedMargin)
    }

    /** The owner's person id (AssistantPrefs.owner), for [isOwner]. Set when a SpeakerStore opens. */
    @Volatile var owner: Long? = null
    /** How far the owner must be clear of the unnamed voices too, under [isOwner]. */
    const val OWNER_FIELD_MARGIN = 0.05
    /** Under [isOwner], speech shorter than this ([OWNER_SHORT_SECONDS]) passes at [OWNER_SHORT_SCORE] with an [OWNER_SHORT_MARGIN] lead. */
    const val OWNER_SHORT_SECONDS = 3.0
    const val OWNER_SHORT_SCORE = 0.66
    const val OWNER_SHORT_MARGIN = 0.12

    /**
     * The owner speaks in nearly every recording, mostly in short bits, and
     * short bits score low: replaying these rules over the owner's 136
     * hand-labeled and named voices, 0.73 with a clear lead over everyone
     * else was common and still "uncertain", so the owner kept labeling
     * themselves. Owner on top, at least "likely", MARGIN_MIN ahead of the
     * next named person and OWNER_FIELD_MARGIN ahead of every unnamed voice
     * is the owner: 75 of the 136 matched instead of 69 (and 190 of 366
     * 1-3 s excerpts of them instead of 179), while of 1,626 voices and
     * excerpts of other people -- also scored as strangers nobody had named
     * -- not one more was taken for the owner (measured 2026-10-04, with
     * voiceprints from the same ten minutes left out). Looser lines (0.70,
     * or no field margin) took TV voices for the owner, so not those.
     *
     * Under 3 s the owner also passes at 0.66 with 0.12 over the next named
     * person (no field margin): of the 74 short voices of the owner, 39
     * matched instead of 26 (27 with the line above alone), and of 317 short
     * excerpts 200 instead of 147 -- at the price of one more voice taken
     * for the owner, a 1.5 s excerpt of a TV voice at 0.728 (also when
     * scored as a stranger). Together: 87 of the 136 instead of 69. The
     * length must be known; a voice of unknown length gets the line above.
     * Measured with ReDimNet2 only, so only with it.
     */
    private fun isOwner(top: Candidate, namedMargin: Double?, margin: Double?, seconds: Double?): Boolean {
        if (model != net.boswell.phone.diarize.VoiceModel.SPEAKER_ID || top.personId != owner || namedMargin == null) return false
        if (top.score >= model.likely && namedMargin >= MARGIN_MIN && margin != null && margin >= OWNER_FIELD_MARGIN) return true
        return seconds != null && seconds < OWNER_SHORT_SECONDS && top.score >= OWNER_SHORT_SCORE && namedMargin >= OWNER_SHORT_MARGIN
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
