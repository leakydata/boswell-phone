package net.boswell.phone.diarize

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** A stretch of one speaker's speech, in seconds. */
data class Turn(val speaker: Int, val start: Double, val end: Double)

/** One speaker in one piece of audio: their turns and a voiceprint pooled over all of them. */
data class DiarizedSpeaker(val index: Int, val turns: List<Turn>, val seconds: Double, val voiceprint: FloatArray?)

data class Diarization(val speakers: List<DiarizedSpeaker>) {
    val turns: List<Turn> get() = speakers.flatMap { it.turns }.sortedBy { it.start }
}

/**
 * Who spoke when, on the phone.
 *
 * pyannote's recipe, smaller: segment overlapping 10 s windows with
 * segmentation-3.0 (up to 3 local speakers each, 2 at once), embed each
 * local speaker's clean speech with the voiceprint model, cluster those
 * embeddings into global speakers, and rebuild a timeline by letting every
 * window vote for its frames.
 *
 * Not sherpa-onnx's diarizer: that one embeds with its own feature pipeline,
 * whose vectors do not match desktop Boswell's (cosine ~0). Here the same
 * voiceprint model does the clustering and the naming, and its output is
 * identical to the desktop's.
 *
 * The final voiceprint per speaker is pooled the desktop's way -- every turn
 * concatenated and embedded once (web/embedder.py) -- so it is directly
 * comparable with a desktop voiceprint of the same speech.
 */
class Diarizer(
    /** 160,000 samples in; 589 frames x 7 powerset log-probabilities out. */
    private val segment: (FloatArray) -> Array<FloatArray>,
    /** Any length of 16 kHz audio in; a 256-d embedding out, or null. */
    private val embed: (FloatArray) -> FloatArray?,
    private val stepSeconds: Double = 2.0,
    /** Average-linkage cosine similarity above which two clusters are one speaker. */
    private val mergeAt: Double = 0.60,
) {
    fun run(audio: FloatArray): Diarization {
        if (audio.size < MIN_AUDIO) return Diarization(emptyList())

        // 1. Windows, the last one flush with the end so nothing is missed.
        val n = audio.size
        val step = (stepSeconds * SR).toInt()
        val starts = mutableListOf<Int>()
        var s = 0
        while (s + WINDOW < n) { starts += s; s += step }
        starts += max(0, n - WINDOW)
        val windows = starts.distinct().map { st ->
            val w = FloatArray(WINDOW)
            audio.copyInto(w, 0, st, min(n, st + WINDOW))
            st to activity(segment(w))
        }

        // 2. An embedding per (window, local speaker) with enough clean speech.
        data class Local(val w: Int, val k: Int, val emb: FloatArray?, val active: Int)
        val locals = mutableListOf<Local>()
        for ((wi, pair) in windows.withIndex()) {
            val (st, act) = pair
            for (k in 0 until LOCAL) {
                val active = act.count { it[k] }
                if (active == 0) continue
                val clean = act.indices.filter { f -> act[f][k] && act[f].count { it } == 1 }
                val emb = if (clean.size * FRAME_S >= MIN_EMBED_S) embed(gather(audio, st, clean))?.let(::unit) else null
                locals += Local(wi, k, emb, active)
            }
        }

        // 3. Cluster the embedded locals.
        val embedded = locals.filter { it.emb != null }
        val labels = cluster(embedded.map { it.emb!! })
        val clusterOf = HashMap<Pair<Int, Int>, Int>()
        embedded.forEachIndexed { i, l -> clusterOf[l.w to l.k] = labels[i] }
        val nClusters = (labels.maxOrNull() ?: -1) + 1
        if (nClusters == 0) return Diarization(emptyList())
        val centroids = (0 until nClusters).map { c ->
            unit(meanOf(embedded.indices.filter { labels[it] == c }.map { embedded[it].emb!! }))
        }
        // Locals too short to embed cleanly: embed whatever they have and
        // attach them to the nearest speaker if they resemble one at all.
        for (l in locals) {
            if (l.emb != null || l.active * FRAME_S < MIN_ATTACH_S) continue
            val (st, act) = windows[l.w]
            val e = embed(gather(audio, st, act.indices.filter { act[it][l.k] }))?.let(::unit) ?: continue
            val best = centroids.indices.maxByOrNull { dot(e, centroids[it]) } ?: continue
            if (dot(e, centroids[best]) >= ATTACH_AT) clusterOf[l.w to l.k] = best
        }

        // 4. Rebuild the timeline: each window votes for its frames.
        val totalFrames = ((n - RF_SIZE).coerceAtLeast(0) / RF_SHIFT) + 1
        val score = Array(totalFrames) { FloatArray(nClusters) }
        val count = FloatArray(totalFrames)
        val cover = IntArray(totalFrames)
        for ((wi, pair) in windows.withIndex()) {
            val (st, act) = pair
            val offset = st / RF_SHIFT
            for (f in act.indices) {
                val g = offset + f
                if (g >= totalFrames) break
                cover[g]++
                count[g] += act[f].count { it }.toFloat()
                for (k in 0 until LOCAL) {
                    if (!act[f][k]) continue
                    val c = clusterOf[wi to k] ?: continue
                    score[g][c] += 1f
                }
            }
        }
        val on = Array(nClusters) { BooleanArray(totalFrames) }
        for (g in 0 until totalFrames) {
            if (cover[g] == 0) continue
            val speakers = (count[g] / cover[g]).roundToInt()
            if (speakers == 0) continue
            val ranked = (0 until nClusters).filter { score[g][it] > 0 }.sortedByDescending { score[g][it] }
            for (c in ranked.take(speakers)) on[c][g] = true
        }

        // 5. Turns, then a voiceprint per speaker pooled over all of their turns.
        val speakers = (0 until nClusters).mapNotNull { c ->
            val turns = toTurns(c, on[c])
            if (turns.isEmpty()) return@mapNotNull null
            c to turns
        }.sortedBy { it.second.first().start }
            .mapIndexed { i, (_, turns) ->
                val t = turns.map { it.copy(speaker = i) }
                val secs = t.sumOf { it.end - it.start }
                val vp = if (secs >= MIN_VOICEPRINT_S) embed(concat(audio, t)) else null
                DiarizedSpeaker(i, t, secs, vp)
            }
        return Diarization(speakers)
    }

    /** Powerset argmax per frame -> which of the 3 local speakers are active. */
    private fun activity(logits: Array<FloatArray>): Array<BooleanArray> = Array(logits.size) { f ->
        val row = logits[f]
        var best = 0
        for (i in 1 until row.size) if (row[i] > row[best]) best = i
        POWERSET[best]
    }

    private fun toTurns(speaker: Int, on: BooleanArray): List<Turn> {
        val out = mutableListOf<Turn>()
        var f = 0
        while (f < on.size) {
            if (!on[f]) { f++; continue }
            val a = f
            while (f < on.size && on[f]) f++
            out += Turn(speaker, frameStart(a), frameStart(f - 1) + FRAME_S)
        }
        // Close small gaps, then drop what is still too short to be speech.
        val merged = mutableListOf<Turn>()
        for (t in out) {
            val last = merged.lastOrNull()
            if (last != null && t.start - last.end <= JOIN_GAP_S) merged[merged.lastIndex] = last.copy(end = t.end)
            else merged += t
        }
        return merged.filter { it.end - it.start >= MIN_TURN_S }
    }

    /** Average-linkage agglomerative clustering on cosine similarity. */
    internal fun cluster(vs: List<FloatArray>): IntArray {
        val groups = vs.indices.map { mutableListOf(it) }.toMutableList()
        val sim = Array(vs.size) { i -> DoubleArray(vs.size) { j -> dot(vs[i], vs[j]) } }
        while (groups.size > 1) {
            var bi = -1; var bj = -1; var best = -2.0
            for (i in groups.indices) for (j in i + 1 until groups.size) {
                var sum = 0.0
                for (a in groups[i]) for (b in groups[j]) sum += sim[a][b]
                val avg = sum / (groups[i].size * groups[j].size)
                if (avg > best) { best = avg; bi = i; bj = j }
            }
            if (best < mergeAt) break
            groups[bi].addAll(groups[bj])
            groups.removeAt(bj)
        }
        val labels = IntArray(vs.size)
        groups.forEachIndexed { g, members -> members.forEach { labels[it] = g } }
        return labels
    }

    private fun gather(audio: FloatArray, windowStart: Int, frames: List<Int>): FloatArray {
        val out = ArrayList<Float>(frames.size * RF_SHIFT)
        var lastEnd = -1
        for (f in frames) {
            val a = max(windowStart + f * RF_SHIFT + RF_OFFSET, lastEnd)
            val b = min(windowStart + f * RF_SHIFT + RF_OFFSET + RF_SHIFT, audio.size)
            for (i in a until b) out += audio[i]
            lastEnd = max(lastEnd, b)
        }
        return out.toFloatArray()
    }

    private fun concat(audio: FloatArray, turns: List<Turn>): FloatArray {
        val parts = turns.map { t ->
            audio.copyOfRange((t.start * SR).toInt().coerceIn(0, audio.size), (t.end * SR).toInt().coerceIn(0, audio.size))
        }
        val out = FloatArray(parts.sumOf { it.size })
        var o = 0
        for (p in parts) { p.copyInto(out, o); o += p.size }
        return out
    }

    companion object {
        const val SR = 16_000
        const val WINDOW = 160_000
        const val LOCAL = 3
        /** segmentation-3.0's receptive field, from the model's own metadata. */
        const val RF_SHIFT = 270
        const val RF_SIZE = 991
        /** Frames are centered in their receptive field; this is where a frame's hop starts. */
        const val RF_OFFSET = (RF_SIZE - RF_SHIFT) / 2
        const val FRAME_S = RF_SHIFT / SR.toDouble()

        const val MIN_AUDIO = SR / 2
        const val MIN_EMBED_S = 1.0
        const val MIN_ATTACH_S = 0.5
        const val ATTACH_AT = 0.35
        const val JOIN_GAP_S = 0.25
        const val MIN_TURN_S = 0.25
        /** web/embedder.py MIN_SECONDS: below this a voiceprint matches everybody. */
        const val MIN_VOICEPRINT_S = 0.8

        /** Powerset classes for 3 speakers, at most 2 at once: {}, {0}, {1}, {2}, {0,1}, {0,2}, {1,2}. */
        val POWERSET = arrayOf(
            booleanArrayOf(false, false, false),
            booleanArrayOf(true, false, false),
            booleanArrayOf(false, true, false),
            booleanArrayOf(false, false, true),
            booleanArrayOf(true, true, false),
            booleanArrayOf(true, false, true),
            booleanArrayOf(false, true, true),
        )

        fun frameStart(f: Int) = (f * RF_SHIFT + RF_OFFSET) / SR.toDouble()

        fun unit(v: FloatArray): FloatArray {
            var s = 0.0
            for (x in v) s += x * x
            val n = sqrt(s)
            return if (n > 0) FloatArray(v.size) { (v[it] / n).toFloat() } else v
        }

        fun dot(a: FloatArray, b: FloatArray): Double {
            var s = 0.0
            for (i in a.indices) s += a[i] * b[i]
            return s
        }

        fun meanOf(vs: List<FloatArray>): FloatArray {
            val out = FloatArray(vs.first().size)
            for (v in vs) for (i in v.indices) out[i] += v[i]
            for (i in out.indices) out[i] /= vs.size
            return out
        }
    }
}
