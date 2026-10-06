package net.boswell.phone.speakers

import android.content.Context

/**
 * "Check the labels": the few voiceprints worth a second look, because a
 * wrong one pulls every later match the wrong way. A voice filed under the
 * wrong person is a reference for that person from then on; an unnamed voice
 * that is really someone known is competition for them ([Matching]'s field).
 *
 * Every question is asked of the voiceprints alone, with the matcher's own
 * rules and the current model's thresholds:
 *  - [Twice]: one recording's voice is filed under two named people.
 *  - [Overlap]: two named people whose recordings keep matching each other.
 *  - [Outlier]: a person's voiceprint that, matched afresh against everyone
 *    else's, goes to someone else -- or is unlike all their others.
 *  - [Unnamed]: an unnamed voice that is likely someone known, clear of the rest.
 */
object LabelCheck {
    /** A voiceprint with where it came from. [personId] is its person or unnamed voice. */
    data class Print(val id: Long, val personId: Long, val clip: String?, val speaker: String?, val vec: FloatArray, val seconds: Double) {
        /** The recording's voice it came from; one with none is a voice of its own. */
        val voice: String get() = if (clip != null && speaker != null) "$clip|$speaker" else "#$id"
    }

    sealed interface Item {
        /** Stable across loads: what an answer is remembered by. */
        val key: String
        /** A recording of it to hear. */
        val sample: Print
    }

    /** One recording's voice filed under each of [people]: it can only be one of them. */
    data class Twice(val people: List<Long>, override val sample: Print) : Item {
        override val key get() = "t${sample.voice}"
    }

    /** [crossings] of [a]'s and [b]'s recordings match the other better: the same person, or labels mixed up. */
    data class Overlap(val a: Long, val b: Long, val crossings: Int, override val sample: Print, val sampleB: Print) : Item {
        override val key get() = pairKey(a, b)
    }

    /**
     * One of [sample]'s person's voiceprints. [like] is who it matches instead
     * (at [likeScore]), or null when it is simply unlike their others; [own] is
     * its best score against them (null: they have no other).
     */
    data class Outlier(override val sample: Print, val own: Double?, val like: Long?, val likeScore: Double) : Item {
        override val key get() = "p${sample.id}"
    }

    /** Unnamed voice [clusterId] sounds like [personId] ([score]); [runnerUp] is the next named person. */
    data class Unnamed(val clusterId: Long, val personId: Long, val score: Double, val runnerUp: Long?, val runnerUpScore: Double?,
                       val recordings: Int, val seconds: Double, override val sample: Print) : Item {
        override val key get() = "c$clusterId"
    }

    fun pairKey(a: Long, b: Long) = "o${minOf(a, b)}-${maxOf(a, b)}"

    /**
     * Voices of one pair of people matching each other this many times make
     * one question about the pair: two is still chance for a well-recorded
     * person (it happened between unrelated people on the owner's data), and
     * the pair's single voiceprints are asked about one by one anyway.
     */
    const val OVERLAP_MIN = 3

    /**
     * A person needs this many other voices before one can be "unlike the
     * rest": with one or two, a single odd recording would be most of them.
     */
    const val MIN_OTHER_VOICES = 3

    /** A short list gets answered; the next ones show up as these are. */
    const val MAX_ITEMS = 20

    /**
     * What is worth a look, most certain first. [named] are named people's
     * voiceprints, [clusters] the open unnamed voices'; [rejected] is who a
     * recording's voice was already said not to be; [answered] are keys
     * answered "right" or "different". With [norm], voiceprints are matched
     * on the normalized scores new voices are (AsNorm).
     */
    fun find(named: List<Print>, clusters: Map<Long, List<Print>>, rejected: (Print) -> Set<Long> = { emptySet() },
             answered: Set<String> = emptySet(), norm: AsNorm.Norm? = null): List<Item> {
        @Suppress("NAME_SHADOWING") val named = named.filter { Matching.usable(it.vec) }.map { it.copy(vec = Matching.unit(it.vec)) }

        // One recording's voice under two people.
        val twice = named.groupBy { it.voice }.values.filter { ps -> ps.map { it.personId }.distinct().size > 1 }
            .map { ps -> Twice(ps.map { it.personId }.distinct(), ps.maxBy { it.seconds }) }
        val inTwice = twice.map { it.sample.voice }.toSet()

        // Each person's voices, matched afresh against every other voice's prints
        // (not the ones under two people: they vouch for neither until answered).
        data class Look(val p: Print, val own: Double?, val top: Matching.Candidate?, val decision: Matching.Decision)
        val sure = named.filter { it.voice !in inTwice }
        val looks = sure.groupBy { it.personId to it.voice }.values.map { copies ->
            val p = copies.maxBy { it.seconds }
            val refs = sure.filter { it.voice != p.voice }.map { Matching.Reference(it.id, it.personId, it.vec, it.clip) }
            val scorer = norm?.forVoice(p.vec, p.clip, p.clip?.let(Pooling::clipTime))
            val own = refs.filter { it.personId == p.personId }.maxOfOrNull { Matching.dot(p.vec, it.vec).let { s -> scorer?.score(s, it) ?: s } }
            val r = Matching.match(p.vec, refs, norm = scorer)
            Look(p, own, r.candidates.firstOrNull(), r.decision)
        }
        var outliers = mutableListOf<Outlier>()
        for ((pid, mine) in looks.groupBy { it.p.personId }) {
            // A person whose recordings don't agree with one another has no odd one out.
            val typical = mine.mapNotNull { it.own }.sorted().let { if (it.isEmpty()) null else it[it.size / 2] }
            for (l in mine) {
                if (l.top != null && l.top.personId != pid && l.decision != Matching.Decision.NONE)
                    outliers += Outlier(l.p, l.own, l.top.personId, l.top.score)
                else if (mine.size - 1 >= MIN_OTHER_VOICES && typical != null && typical >= Matching.MATCH_LOW && (l.own ?: -1.0) < Matching.MATCH_LOW)
                    outliers += Outlier(l.p, l.own, null, l.own ?: 0.0)
            }
        }

        outliers = outliers.filterTo(mutableListOf()) { it.key !in answered }

        // Pairs of people whose recordings keep crossing over.
        val crossings = HashMap<String, Int>()
        for (o in outliers) o.like?.let { crossings.merge(pairKey(o.sample.personId, it), 1) { a, b -> a + b } }
        for (t in twice) for (i in t.people.indices) for (j in i + 1 until t.people.size) crossings.merge(pairKey(t.people[i], t.people[j]), 1) { a, b -> a + b }
        val overlaps = crossings.filter { it.value >= OVERLAP_MIN && it.key !in answered }.map { (k, n) ->
            val (a, b) = k.drop(1).split("-").map { it.toLong() }
            fun sampleOf(from: Long, to: Long) = outliers.firstOrNull { it.sample.personId == from && it.like == to }?.sample
                ?: named.filter { it.personId == from }.maxBy { it.seconds }
            Overlap(a, b, n, sampleOf(a, b), sampleOf(b, a))
        }
        val pairsAsked = overlaps.map { it.key }.toSet()

        // Unnamed voices that are likely someone known, scored as VoiceReview.suggestions does.
        val unnamed = clusters.mapNotNull { (cid, members) ->
            val ms = members.filter { Matching.usable(it.vec) }
            if (ms.isEmpty()) return@mapNotNull null
            val sums = HashMap<Long, Pair<Double, Int>>()
            for (m in ms) {
                val no = rejected(m)
                val v = Matching.unit(m.vec)
                val best = HashMap<Long, Double>()
                for (r in named) if (r.personId !in no) {
                    val s = Matching.dot(v, r.vec)
                    if (s > (best[r.personId] ?: -2.0)) best[r.personId] = s
                }
                for ((p, s) in best) sums[p] = (sums[p]?.let { it.first + s to it.second + 1 }) ?: (s to 1)
            }
            // A person every recording of it was said not to be is out entirely.
            val ranked = sums.filter { it.value.second == ms.size }.map { it.key to it.value.first / it.value.second }.sortedByDescending { it.second }
            val (pid, score) = ranked.firstOrNull() ?: return@mapNotNull null
            val second = ranked.getOrNull(1)
            if (score < Matching.model.likely || (second != null && score - second.second < Matching.MARGIN_MIN)) return@mapNotNull null
            Unnamed(cid, pid, score, second?.first, second?.second, ms.size, ms.sumOf { it.seconds }, ms.maxBy { it.seconds })
        }

        // Most certain first: a voice under two people is wrong for sure; a
        // pair is one answer for many voices; then single voiceprints that go
        // to someone else, unnamed voices, and odd ones out. A pair's own
        // voiceprints wait until the pair is answered.
        return (twice +
            overlaps.sortedByDescending { it.crossings } +
            outliers.filter { it.like != null && pairKey(it.sample.personId, it.like) !in pairsAsked }.sortedByDescending { it.likeScore - (it.own ?: 0.0) } +
            unnamed.sortedByDescending { it.score } +
            outliers.filter { it.like == null }.sortedBy { it.own ?: 0.0 })
            .filter { it.key !in answered }
            .take(MAX_ITEMS)
    }
}

/**
 * [LabelCheck] over what the phone has, and its fixes. Each fix is one the
 * app already makes elsewhere -- naming, "Not them", "Yes"/"No" in review --
 * so nothing here is a new way to change who someone is.
 */
class LabelChecks(private val context: Context) {

    fun find(): List<LabelCheck.Item> {
        val store = SpeakerStore(context)
        try {
            val clusters = store.unnamedClusters().mapValues { (cid, ms) -> ms.map { LabelCheck.Print(it.id, cid, it.clip, it.speaker, it.vec, it.seconds) } }
            return LabelCheck.find(store.namedPrints(), clusters,
                rejected = { p -> if (p.clip != null && p.speaker != null) store.rejected(p.clip, p.speaker) else emptySet() },
                answered = store.labelChecked(), norm = store.norm())
        } finally { store.close() }
    }

    /** "Right" or "Different": never asked again. */
    fun keep(item: LabelCheck.Item) { SpeakerStore(context).use { it.markLabelChecked(item.key) } }

    /** One recording's voice under two people is [personId]'s: the others' copies of it go. */
    fun only(item: LabelCheck.Twice, personId: Long) {
        val clip = item.sample.clip ?: return
        val speaker = item.sample.speaker ?: return
        SpeakerStore(context).use { store -> for (other in item.people) if (other != personId) store.dropVoice(other, clip, speaker) }
    }

    /**
     * Not [item]'s person: every copy of that voiceprint's voice comes off
     * them ("Not them"), to [to] if given (a name, an existing one merges) or
     * otherwise to a new unnamed voice.
     */
    fun takeOff(item: LabelCheck.Outlier, to: String?): Unit = SpeakerStore(context).use { store ->
        val p = item.sample
        val ids = store.namedPrints().filter { it.personId == p.personId && it.voice == p.voice }.map { it.id }.ifEmpty { listOf(p.id) }
        val fresh = store.unnamePrints(p.personId, ids)
        if (to != null) store.name(fresh, to)
    }

    /** Two people are one: [from] joins [into], the way naming someone with a name already in use does. */
    fun merge(from: Long, into: Long) {
        SpeakerStore(context).use { store -> store.nameOf(into)?.let { store.name(from, it) } }
    }

    /** An unnamed voice as a review suggestion, so "It's them" and "Someone else" are review's Yes and No. */
    fun suggestion(item: LabelCheck.Unnamed): Suggestion? {
        val clip = item.sample.clip ?: return null
        val speaker = item.sample.speaker ?: return null
        val name = SpeakerStore(context).use { it.nameOf(item.personId) } ?: return null
        return Suggestion(item.personId, name, item.score, item.clusterId, item.recordings, item.seconds, clip, speaker)
    }
}
