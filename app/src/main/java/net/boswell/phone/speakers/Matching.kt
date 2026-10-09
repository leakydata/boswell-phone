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

    /** A voiceprint to match against; [clip] is the recording it came from, if known (AsNorm leaves a print's own recording out). */
    data class Reference(val voiceprintId: Long, val personId: Long, val vec: FloatArray, val clip: String? = null)
    data class Candidate(val personId: Long, val score: Double, val voiceprintId: Long)
    data class Result(val decision: Decision, val candidates: List<Candidate>, val score: Double, val margin: Double?) {
        val personId: Long? get() = if (decision == Decision.NONE) null else candidates.firstOrNull()?.personId
    }

    /**
     * Score a voice against every reference, best row per PERSON, and compare
     * the top two people. The margin must be between people, not rows: a
     * well-covered person owns the top several rows, and a row margin would
     * collapse to zero exactly where coverage is best.
     *
     * [norm] scores on the normalized scale (AsNorm) instead of raw cosines;
     * [snr] is how far the voice stands above its room (Snr), null if unknown.
     */
    fun match(vec: FloatArray, refs: List<Reference>, field: List<Reference> = emptyList(), seconds: Double? = null,
              snr: Double? = null, norm: AsNorm.Scorer? = null): Result {
        @Suppress("NAME_SHADOWING") val refs = refs.filter { it.vec.size == vec.size }
        @Suppress("NAME_SHADOWING") val field = field.filter { it.vec.size == vec.size }
        if (!usable(vec) || refs.isEmpty()) return Result(Decision.NONE, emptyList(), 0.0, 0.0)
        val v = unit(vec)
        fun score(r: Reference): Double { val s = dot(v, r.vec); return norm?.score(s, r) ?: s }
        val best = HashMap<Long, Candidate>()
        for (r in refs) {
            val s = score(r)
            val cur = best[r.personId]
            if (cur == null || s > cur.score) best[r.personId] = Candidate(r.personId, s, r.voiceprintId)
        }
        val ranked = best.values.sortedByDescending { it.score }
        val top = ranked.first()
        val namedMargin = ranked.getOrNull(1)?.let { top.score - it.score }
        val fieldBest = field.maxOfOrNull(::score)
        // The owner is decided by the owner's rule alone.
        if (model == net.boswell.phone.diarize.VoiceModel.SPEAKER_ID && owner != null && top.personId == owner) {
            val runnerUp = ranked.getOrNull(1)?.score ?: -1.0
            val fieldMargin = top.score - maxOf(runnerUp, fieldBest ?: -9.0)
            val margin = if (field.isEmpty()) namedMargin else fieldMargin
            return if (isOwner(top.score, top.score - runnerUp, fieldMargin, seconds, snr)) Result(Decision.MATCHED, ranked.take(3), top.score, margin)
                else Result(if (top.score >= MATCH_LOW) Decision.UNCERTAIN else Decision.NONE, ranked.take(3), top.score, namedMargin)
        }
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
        val alone = decide(top.score, namedMargin)
        if (alone == Decision.MATCHED) return Result(alone, ranked.take(3), top.score, namedMargin)
        val margin = if (fieldBest == null) namedMargin else top.score - maxOf(ranked.getOrNull(1)?.score ?: -1.0, fieldBest)
        if (fieldBest != null && decide(top.score, margin) == Decision.MATCHED) return Result(Decision.MATCHED, ranked.take(3), top.score, margin)
        return Result(alone, ranked.take(3), top.score, namedMargin)
    }

    /** The owner's person id (AssistantPrefs.owner), for [isOwner]. Set when a SpeakerStore opens. */
    @Volatile var owner: Long? = null
    /** Under [isOwner]: this score, this far ahead of the next named person and of every unnamed voice. */
    const val OWNER_SCORE = 0.71
    const val OWNER_MARGIN = 0.08
    const val OWNER_FIELD_MARGIN = 0.08
    /** Under [isOwner], speech shorter than [OWNER_SHORT_SECONDS] passes at [OWNER_SHORT_SCORE] with an [OWNER_SHORT_MARGIN] lead. */
    const val OWNER_SHORT_SECONDS = 3.0
    const val OWNER_SHORT_SCORE = 0.56
    const val OWNER_SHORT_MARGIN = 0.14
    /** Under [isOwner], a voice [OWNER_NEAR_SNR] dB above its room passes at [OWNER_NEAR_SCORE] with an [OWNER_NEAR_MARGIN] lead. */
    const val OWNER_NEAR_SNR = 23.0
    const val OWNER_NEAR_SCORE = 0.40
    const val OWNER_NEAR_MARGIN = 0.04
    /** A voice less than this many dB above its room is never the owner, who wears the microphone. */
    const val OWNER_FAR_SNR = 8.0
    /**
     * Under [isOwner], a score this high needs only the [OWNER_MARGIN] lead over named people,
     * whatever unnamed voices score: those were the owner's own unfiled voices, each refused
     * voice filed among them refusing the next (measured 2026-10-09: 103 -> 139 of 178 owner
     * voices, no more others taken for the owner; a TV excerpt at 0.848 is the nearest miss).
     */
    const val OWNER_HIGH_SCORE = 0.85

    /**
     * The owner speaks in nearly every recording, mostly in short bits, and
     * short bits score low. Owner on top, on normalized scores (AsNorm) with
     * a clean voice pooled with the ones just before it (Pooling), is the
     * owner when any of:
     *  - OWNER_SCORE, OWNER_MARGIN ahead of the next named person and
     *    OWNER_FIELD_MARGIN ahead of every unnamed voice;
     *  - OWNER_HIGH_SCORE with OWNER_MARGIN over the next named person, unnamed voices aside;
     *  - under 3 s of speech, OWNER_SHORT_SCORE with OWNER_SHORT_MARGIN over
     *    the next named person (the length must be known);
     *  - OWNER_NEAR_SNR dB or more above the room (the near voice),
     *    OWNER_NEAR_SCORE with OWNER_NEAR_MARGIN over the next named person;
     * and never under OWNER_FAR_SNR dB. The general rule ([decide]) no longer
     * decides the owner. Replayed over the owner's 136 hand-labeled and named
     * voices, with voiceprints from the same ten minutes left out: 120
     * matched instead of 87 (and 339 of 366 1-3 s excerpts instead of 235),
     * with as many voices and excerpts of other people taken for the owner
     * as before (13 of 1,626; 19 scored as strangers nobody had named), and
     * other people put to the wrong person 119 times instead of 154. Tuned
     * on one half of the timeline and tested on the other: 121, with 17 and
     * 22 (measured 2026-10-05). Without the loudness (transcripts made
     * before it was kept) the loudness lines simply don't apply. Measured
     * with ReDimNet2 only, so only with it.
     */
    private fun isOwner(score: Double, namedMargin: Double, fieldMargin: Double, seconds: Double?, snr: Double?): Boolean {
        if (snr != null && snr < OWNER_FAR_SNR) return false
        if (score >= OWNER_SCORE && namedMargin >= OWNER_MARGIN && fieldMargin >= OWNER_FIELD_MARGIN) return true
        if (score >= OWNER_HIGH_SCORE && namedMargin >= OWNER_MARGIN) return true
        if (seconds != null && seconds > 0 && seconds < OWNER_SHORT_SECONDS && score >= OWNER_SHORT_SCORE && namedMargin >= OWNER_SHORT_MARGIN) return true
        return snr != null && snr >= OWNER_NEAR_SNR && score >= OWNER_NEAR_SCORE && namedMargin >= OWNER_NEAR_MARGIN
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
