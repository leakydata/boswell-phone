package net.boswell.phone.speakers

/**
 * Adaptive score normalization (AS-norm), so a score means the same for
 * every voice and every voiceprint. A raw cosine depends on the pair: a
 * voiceprint from a noisy room is a little like everyone, and the owner's
 * short bits are a little like everyone else's short bits. Each print is
 * scored against a cohort of voices that are certainly somebody else -- the
 * unnamed voices whose closest named person is not the owner -- and the mean
 * and spread of its [TOP] best cohort scores say how much "alike" is just
 * the room. A score becomes
 *
 *     s' = ((s - mean_voice) / sd_voice + (s - mean_print) / sd_print) / 2
 *
 * and is mapped back onto the cosine scale ([toCosine]) so every threshold
 * in [Matching] keeps its meaning: at any threshold the normalized scores
 * take other people for someone exactly as often as the raw cosines did.
 *
 * A voiceprint's own cohort scores leave out the cohort voices from its own
 * recording; a new voice's leave out its recording and everything within
 * [WINDOW] of it (the same conversation, maybe the same person unnamed).
 * A score against a named person also leaves out their self-clusters
 * ([SELF_CLUSTER_PRINTS], [SELF_CLUSTER_SCORE]), on both sides.
 * Measured with ReDimNet2 only (VoiceModel.SPEAKER_ID), so only with it.
 */
object AsNorm {
    /** Cohort scores per print that make its statistics. */
    const val TOP = 20
    /** Seconds around a new voice's recording whose cohort voices it leaves out. */
    const val WINDOW = 600.0

    /**
     * An unnamed cluster of at least [SELF_CLUSTER_PRINTS] prints that is
     * closest to a named person other than the owner, and resembles them at
     * [SELF_CLUSTER_SCORE] or more (the mean over its prints of the best
     * cosine to theirs), is that person unnamed: it leaves the cohort for
     * scores against them, and only them. Left in, its near-copies made every
     * score against the person look ordinary -- u#1482, 117 recordings 0.84
     * like Bijan Bowen, normalized to a median 0.47 and never matched. Leaving
     * out every unnamed print closest to the person instead put 3 times as
     * many voices to the wrong person (185 -> 623). Measured 2026-10-09: 3
     * clusters, 119 prints (all Bijan Bowen's); the truth set as before (one
     * more voice right, nothing else moved); 58 more voices matched, all of
     * u#1482, at 0.84-0.91 like him and at most 0.54 like the owner.
     */
    const val SELF_CLUSTER_PRINTS = 5
    const val SELF_CLUSTER_SCORE = 0.80

    data class Stat(val mean: Double, val sd: Double)

    /**
     * The cohort: unnamed voices' prints (unit length), the recording each came
     * from, its time (NaN: unknown), and the named person whose self-cluster
     * it is in ([who]; -1: nobody's).
     */
    class Cohort(val vecs: List<FloatArray>, val clips: List<String?>, val times: DoubleArray,
                 val who: LongArray = LongArray(vecs.size) { -1L }) {
        val size get() = vecs.size
    }

    /**
     * The unnamed voices of [field] whose best named person in [named] (their
     * own recording's prints left out) is not [owner]: impostors for everyone,
     * the owner included. An unnamed voice that is closest to the owner is
     * often the owner unnamed, and would make the owner's own scores look
     * ordinary. Each keeps whose self-cluster it is in, if anyone's
     * ([SELF_CLUSTER_PRINTS]); [field]'s person ids are the clusters.
     */
    fun cohort(named: List<Matching.Reference>, field: List<Matching.Reference>, owner: Long?): Cohort {
        val people = named.mapTo(LinkedHashSet()) { it.personId }.toList()
        val best = field.map { u ->
            val b = HashMap<Long, Double>()
            for (r in named) {
                if (u.clip != null && r.clip == u.clip) continue
                val s = Matching.dot(u.vec, r.vec)
                if (s > (b[r.personId] ?: -9.0)) b[r.personId] = s
            }
            b
        }
        // Each cluster's resemblance to each person: the mean of its prints' best.
        val self = HashMap<Long, Long>()
        for ((cluster, idx) in field.indices.groupBy { field[it].personId }) {
            if (idx.size < SELF_CLUSTER_PRINTS || people.isEmpty()) continue
            val mean = people.map { p -> p to idx.sumOf { best[it][p] ?: -9.0 } / idx.size }
            val (p, score) = mean.maxBy { it.second }
            if (p != owner && score >= SELF_CLUSTER_SCORE) self[cluster] = p
        }
        val keep = field.indices.filter { owner == null || best[it].maxByOrNull { e -> e.value }?.key != owner }
        return Cohort(keep.map { field[it].vec }, keep.map { field[it].clip },
            DoubleArray(keep.size) { field[keep[it]].clip?.let(Pooling::clipTime) ?: Double.NaN },
            LongArray(keep.size) { self[field[keep[it]].personId] ?: -1L })
    }

    /** The [TOP] highest scores offered, kept in ascending order. */
    internal class Top {
        val top = DoubleArray(TOP)
        var n = 0
        fun add(s: Double) {
            if (n < TOP) { top[n++] = s; var j = n - 1; while (j > 0 && top[j - 1] > top[j]) { val t = top[j]; top[j] = top[j - 1]; top[j - 1] = t; j-- } }
            else if (s > top[0]) {
                // The smallest goes.
                var j = 0
                while (j + 1 < TOP && top[j + 1] < s) { top[j] = top[j + 1]; j++ }
                top[j] = s
            }
        }
        fun stat(): Stat? {
            if (n == 0) return null
            var mean = 0.0
            for (i in 0 until n) mean += top[i]
            mean /= n
            var sq = 0.0
            for (i in 0 until n) sq += (top[i] - mean) * (top[i] - mean)
            return Stat(mean, kotlin.math.sqrt(sq / n) + 1e-6)
        }
    }

    /** Mean and spread of [v]'s [TOP] best cohort scores, without the cohort voices [skip] says; null with none left. */
    fun stat(v: FloatArray, c: Cohort, skip: (Int) -> Boolean): Stat? {
        val top = Top()
        for (i in 0 until c.size) if (!skip(i)) top.add(Matching.dot(v, c.vecs[i]))
        return top.stat()
    }

    /** A voiceprint's statistics: the cohort voices of its own recording, and [person]'s self-clusters, left out. */
    fun printStat(v: FloatArray, c: Cohort, clip: String?, person: Long? = null): Stat? =
        stat(v, c) { (c.clips[it] ?: "") == (clip ?: "") || (person != null && c.who[it] == person) }

    /** A new voice's statistics: its recording, the cohort voices within [WINDOW] of [time], and [person]'s self-clusters left out. */
    fun voiceStat(v: FloatArray, c: Cohort, clip: String?, time: Double?, person: Long? = null): Stat? =
        stat(v, c) { (clip != null && c.clips[it] == clip) || (time != null && kotlin.math.abs(c.times[it] - time) <= WINDOW) ||
            (person != null && c.who[it] == person) }

    /** [s] between a voice and a print, normalized and back on the cosine scale. */
    fun normalized(s: Double, voice: Stat, print: Stat): Double =
        toCosine(0.5 * ((s - voice.mean) / voice.sd + (s - print.mean) / print.sd))

    /**
     * Normalized scores back onto the cosine scale: the raw cosine that other
     * people's voiceprints reach as often as they reach [n] normalized
     * (piecewise linear, flat past either end).
     */
    fun toCosine(n: Double): Double {
        if (n <= QX[0]) return QY[0]
        if (n >= QX[QX.size - 1]) return QY[QY.size - 1]
        var lo = 0
        var hi = QX.size - 1
        while (hi - lo > 1) { val m = (lo + hi) / 2; if (QX[m] <= n) lo = m else hi = m }
        return QY[lo] + (QY[hi] - QY[lo]) * (n - QX[lo]) / (QX[hi] - QX[lo])
    }

    /**
     * The scores of a cohort and the voiceprints, kept while they stay the
     * same: each voiceprint's statistics are worked out the first time it is
     * scored. [forVoice] gives one voice's scoring.
     */
    class Norm(val cohort: Cohort) {
        private val prints = java.util.concurrent.ConcurrentHashMap<Pair<Long, Long>, Stat>()
        private val none = Stat(Double.NaN, Double.NaN)

        fun print(r: Matching.Reference): Stat? =
            prints.getOrPut(r.voiceprintId to r.personId) { printStat(r.vec, cohort, r.clip, r.personId) ?: none }.takeIf { it !== none }

        /**
         * Scoring for a voice ([vec], of recording [clip] made at [time]), or null
         * when no cohort is left for it. The voice meets the cohort once,
         * keeping the [TOP] best scores of each self-cluster owner's share and
         * of the rest; a person's statistics come from the shares not theirs.
         */
        fun forVoice(vec: FloatArray, clip: String?, time: Double?): Scorer? {
            val v = Matching.unit(vec)
            val c = cohort
            val tops = HashMap<Long, Top>()
            for (i in 0 until c.size) {
                if ((clip != null && c.clips[i] == clip) || (time != null && kotlin.math.abs(c.times[i] - time) <= WINDOW)) continue
                tops.getOrPut(c.who[i]) { Top() }.add(Matching.dot(v, c.vecs[i]))
            }
            return if (tops.isEmpty()) null else Scorer(this, tops)
        }
    }

    /** One voice's scores against the voiceprints, normalized. */
    class Scorer internal constructor(private val norm: Norm, private val tops: Map<Long, Top>) {
        private val voice = HashMap<Long, Stat?>()

        /** The voice's statistics when scored against [person]: their self-clusters left out. */
        fun voice(person: Long): Stat? = synchronized(voice) {
            val key = if (person in tops && person != -1L) person else -1L
            voice.getOrPut(key) {
                val all = Top()
                for ((who, t) in tops) if (who == -1L || who != key) for (i in 0 until t.n) all.add(t.top[i])
                all.stat()
            }
        }

        fun score(raw: Double, r: Matching.Reference): Double {
            val y = norm.print(r) ?: return raw
            val x = voice(r.personId) ?: return raw
            return normalized(raw, x, y)
        }
    }

    // asnorm.qmap on the owner's truth set (2,128 voices and excerpts x other people's
    // voiceprints, pooled and normalized as here), measured 2026-10-05; thinned to within 1e-4.
    private val QX = doubleArrayOf(
        -33.10047, -20.21780, -18.83444, -18.09871, -17.61892, -17.26428, -16.93344, -16.67026, -16.45100, -16.24249,
        -15.90065, -15.74841, -15.62122, -15.48192, -15.36753, -15.24896, -15.13338, -14.93671, -14.84749, -14.68436,
        -14.30253, -14.17158, -14.05098, -13.88211, -13.77280, -13.72722, -13.67918, -13.50182, -13.34340, -13.22753,
        -13.12238, -12.92103, -12.71280, -12.59362, -12.49043, -12.42005, -12.32958, -12.15523, -12.08543, -11.95954,
        -11.67520, -11.57488, -11.45958, -11.36703, -11.17205, -10.79281, -10.56870, -10.40042, -10.07994, -9.799776,
        -9.560775, -9.449685, -9.288654, -9.085654, -8.859839, -8.574846, -8.299524, -8.128803, -7.553194, -7.229876,
        -6.995653, -6.646896, -6.426943, -6.330015, -6.118097, -5.820366, -5.673293, -5.493792, -5.40018, -5.19546,
        -5.075143, -4.915616, -4.742054, -4.605843, -4.353794, -4.103672, -4.023557, -3.909975, -3.80647, -3.496736,
        -3.30026, -3.155648, -3.068399, -2.974863, -2.808051, -2.675966, -2.61678, -2.457477, -2.332769, -2.223529,
        -2.113962, -2.0682, -1.949051, -1.777692, -1.650555, -1.385313, -1.315002, -1.232101, -1.094669, -1.003461,
        -0.965839, -0.824163, -0.741055, -0.650931, -0.601823, -0.578003, -0.554369, -0.459598, -0.38762, -0.358793,
        -0.302466, -0.155689, -0.023412, 0.143233, 0.326557, 0.476685, 0.513639, 0.583109, 0.702212, 0.791885,
        0.832486, 0.963422, 1.003129, 1.048512, 1.171495, 1.264386, 1.356094, 1.531246, 1.75282, 1.842946,
        1.890286, 1.94429, 2.037638, 2.08104, 2.128024, 2.167522, 2.252117, 2.332577, 2.380363, 2.499853,
        2.545459, 2.584612, 2.668739, 2.706366, 2.752409, 2.790682, 2.875296, 2.944743, 2.979801, 3.056571,
        3.087798, 3.127007, 3.201885, 3.408369, 3.483974, 3.547287, 3.615798, 3.648651, 3.684408, 3.715984,
        3.781806, 3.851717, 3.952391, 3.983852, 4.020099, 4.091193, 4.123525, 4.15403, 4.252922, 4.289752,
        4.324086, 4.394621, 4.427216, 4.461464, 4.496004, 4.599474, 4.634113, 4.671912, 4.701949, 4.772072,
        4.808379, 4.849655, 5.003036, 5.082333, 5.158345, 5.197031, 5.23919, 5.323233, 5.372449, 5.41143,
        5.455497, 5.505057, 5.706456, 5.762276, 5.863591, 5.965812, 6.029551, 6.155992, 6.217133, 6.283384,
        6.355182, 6.428065, 6.575445, 6.758273, 6.860033, 7.051124, 7.166197, 7.294806, 7.442426, 7.747283,
        7.913258, 8.088637, 8.286169, 8.486115, 8.965354, 9.270161, 9.628277, 9.931581, 10.33980, 10.75819,
        11.32618, 12.05001, 13.08744, 14.53361, 24.39928
    )
    private val QY = doubleArrayOf(
        -0.31523, -0.216657, -0.198148, -0.186849, -0.178454, -0.17145, -0.16587, -0.160718, -0.155983, -0.152038,
        -0.144604, -0.141172, -0.1378, -0.134708, -0.131973, -0.129505, -0.127377, -0.122576, -0.120559, -0.116509,
        -0.1083, -0.105261, -0.102115, -0.098112, -0.095658, -0.094354, -0.0934, -0.088989, -0.084903, -0.082074,
        -0.079336, -0.073916, -0.068499, -0.065491, -0.062781, -0.060687, -0.058218, -0.053102, -0.051447, -0.047998,
        -0.04012, -0.037203, -0.033921, -0.031044, -0.025624, -0.014521, -0.007853, -0.002867, 0.006902, 0.01549,
        0.022924, 0.026442, 0.031538, 0.038035, 0.045404, 0.054538, 0.063712, 0.069575, 0.089393, 0.100933,
        0.109585, 0.12217, 0.130151, 0.13369, 0.14157, 0.15247, 0.157828, 0.164325, 0.167756, 0.175243,
        0.179592, 0.185495, 0.192012, 0.196976, 0.206554, 0.216128, 0.219206, 0.223521, 0.227583, 0.239661,
        0.247626, 0.25358, 0.257249, 0.261124, 0.268007, 0.273587, 0.27603, 0.282896, 0.288261, 0.292973,
        0.297613, 0.299689, 0.304574, 0.311794, 0.31724, 0.328383, 0.331475, 0.334651, 0.339886, 0.343489,
        0.344859, 0.350423, 0.353805, 0.357233, 0.359061, 0.360101, 0.360894, 0.364676, 0.36765, 0.36847,
        0.370699, 0.376018, 0.380592, 0.38673, 0.392995, 0.398452, 0.399654, 0.402585, 0.406912, 0.409858,
        0.411529, 0.416137, 0.417774, 0.419278, 0.424383, 0.427722, 0.431247, 0.438696, 0.4473, 0.451264,
        0.45312, 0.455006, 0.459188, 0.461182, 0.463123, 0.465184, 0.469096, 0.473494, 0.475604, 0.481682,
        0.483671, 0.48582, 0.489908, 0.492128, 0.494321, 0.496613, 0.500489, 0.504493, 0.506676, 0.510477,
        0.512612, 0.514578, 0.51886, 0.531757, 0.53601, 0.53995, 0.543939, 0.546081, 0.54811, 0.550333,
        0.554499, 0.55839, 0.564524, 0.566585, 0.568438, 0.572678, 0.574804, 0.57702, 0.583487, 0.585541,
        0.587812, 0.59201, 0.594158, 0.596096, 0.598451, 0.604612, 0.607001, 0.609176, 0.6113, 0.615639,
        0.617688, 0.619799, 0.62846, 0.632892, 0.637649, 0.639759, 0.642432, 0.647421, 0.649734, 0.652102,
        0.65456, 0.656671, 0.666394, 0.66889, 0.674319, 0.679152, 0.681683, 0.687212, 0.690401, 0.693541,
        0.696703, 0.699503, 0.705864, 0.712489, 0.715936, 0.72316, 0.726989, 0.730937, 0.734783, 0.743551,
        0.748028, 0.75304, 0.757661, 0.762654, 0.772714, 0.778086, 0.784102, 0.790801, 0.797768, 0.806783,
        0.817528, 0.829739, 0.844602, 0.870172, 1.0
    )
}
