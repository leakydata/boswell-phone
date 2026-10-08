package net.boswell.phone

import android.content.Context
import net.boswell.phone.ArchiveFixture.Companion.noisy
import net.boswell.phone.ArchiveFixture.Companion.randomUnit
import net.boswell.phone.ArchiveFixture.Companion.transcriptOf
import net.boswell.phone.diarize.VoiceModel
import net.boswell.phone.process.Candidate
import net.boswell.phone.process.Segment
import net.boswell.phone.process.SpeakerId
import net.boswell.phone.speakers.Matching
import net.boswell.phone.speakers.SpeakerStore
import net.boswell.phone.speakers.VoiceReview
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.random.Random

/**
 * A recheck worked out from the archive's index makes the same decisions,
 * writes the same transcripts and changes the speaker store the same way as
 * the one that read every transcript (OldVoiceReview), on a made-up archive
 * with everything a recheck has to respect: the owner, named people, unnamed
 * voices and a merge, voices decided by hand, "not them" answers, names given
 * to short voices, Boswell's own voice, voices without a print, clean voices
 * to pool and lines nobody was attributed to. Review suggestions too.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RecheckFromIndexTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val fx = ArchiveFixture(context)
    private val model = Matching.model
    private val owner = Matching.owner

    @After fun restore() { Matching.model = model; Matching.owner = owner }

    private class Voice(val clip: String, val label: String, val who: String, val secs: Double)

    /** The archive and speaker store, the same every time. Returns every clip voice made, by whose voice it is. */
    private fun build(): List<Voice> {
        val rnd = Random(7)
        val prefs = context.getSharedPreferences("boswell", Context.MODE_PRIVATE)
        prefs.edit().putString("voice_model", VoiceModel.SPEAKER_ID.id).commit()
        val store = SpeakerStore(context)
        try {
            val dim = Matching.model.dim
            val center = listOf("me", "bea", "cal", "u1", "u2", "m", "s1", "s2").associateWith { randomUnit(rnd, dim) }
            val me = store.newPerson("Me")
            prefs.edit().putLong("owner_person", me).commit()
            val id = mapOf("me" to me, "bea" to store.newPerson("Bea"), "cal" to store.newPerson("Cal"),
                "u1" to store.newPerson(null), "u2" to store.newPerson(null), "m" to store.newPerson(null))
            for (p in listOf("me", "bea", "cal")) repeat(5) { store.addVoiceprint(id.getValue(p), noisy(rnd, center.getValue(p), 0.5), 6.0, null, null, "manual") }

            val voices = mutableListOf<Voice>()
            val whoPick = listOf("me", "me", "me", "me", "me", "me", "me", "bea", "bea", "bea", "cal", "cal", "u1", "u1", "u2", "u2", "m", "s1", "s2", "s2")
            var ended = 1_791_000_000L
            repeat(140) { i ->
                ended += 8 + rnd.nextInt(8)
                val clip = "omi_$ended.wav"
                fx.sidecar(clip, ended - 10.0, ended.toDouble())
                val made = LinkedHashMap<String, Pair<SpeakerId, FloatArray?>>()
                repeat(1 + rnd.nextInt(3)) { k ->
                    val label = "SPEAKER_0$k"
                    val who = whoPick[rnd.nextInt(whoPick.size)]
                    val emb = noisy(rnd, center.getValue(who), 0.3 + rnd.nextDouble() * 1.3)
                    val secs = 0.5 + rnd.nextDouble() * 9.5
                    val snr = if (rnd.nextDouble() < 0.25) null else 5 + rnd.nextDouble() * 25
                    val roll = rnd.nextDouble()
                    val sp = when {
                        roll < 0.05 -> SpeakerId("Boswell", 1.0, "boswell", null, emptyList(), null, secs)
                        roll < 0.45 -> SpeakerId(null, 0.0, "none", null, emptyList(), null, secs, snr)
                        else -> {
                            // Matched before, to whoever they were then (a merged-away voice included).
                            val pid = id[who] ?: id.getValue("bea")
                            val score = 0.5 + rnd.nextDouble() * 0.4
                            SpeakerId(null, score, if (roll < 0.75) "matched" else "uncertain", 0.1, listOf(Candidate(pid, null, score, 1)),
                                pid.takeIf { roll < 0.75 }, secs, snr)
                        }
                    }
                    made[label] = sp to emb.takeIf { rnd.nextDouble() > 0.05 }
                    voices += Voice(clip, label, who, secs)
                    // Unnamed voices filed as processing files them.
                    if (who in setOf("u1", "u2", "m") && secs >= 3 && rnd.nextDouble() < 0.6 && made[label]!!.second != null && sp.decision != "boswell")
                        store.addVoiceprint(id.getValue(who), emb, secs, clip, label, "auto")
                }
                // Now and then a line nobody was attributed to, near the first voice's.
                val orphan = if (i % 9 == 0) listOf(Segment(1.6, 1.9, null, "an orphan in $clip")) else emptyList()
                fx.transcript(transcriptOf(clip, made, orphan))
            }
            // One unnamed voice found to be another (merges), voices decided by hand, "not them", and short voices named.
            store.mergeUnnamed(id.getValue("m"), id.getValue("u1"))
            for (v in voices.filter { it.who == "bea" && it.secs >= 3 }.take(3)) {
                val emb = fx.read(v.clip).embeddings[v.label] ?: continue
                store.addVoiceprint(id.getValue("bea"), emb.toFloatArray(), v.secs, v.clip, v.label, "confirmed")
            }
            // Decided by hand and still unnamed (a group taken back off someone keeps its confirmed prints).
            for (v in voices.filter { it.who == "u2" && it.secs >= 3 }.takeLast(2)) {
                val emb = fx.read(v.clip).embeddings[v.label] ?: continue
                store.addVoiceprint(id.getValue("u2"), emb.toFloatArray(), v.secs, v.clip, v.label, "confirmed")
            }
            for (v in voices.filter { it.who == "me" }.takeLast(6)) store.reject(v.clip, v.label, me)
            for (v in voices.filter { it.who == "m" }.take(2)) store.reject(v.clip, v.label, id.getValue("bea"))
            for (v in voices.filter { it.who == "cal" && it.secs < 3 }.take(3)) store.assign(v.clip, v.label, id.getValue("cal"))
            return voices
        } finally { store.close() }
    }

    private fun dump(): String = SpeakerStore(context).use { s ->
        val db = s.readableDatabase
        buildString {
            for (q in listOf("SELECT id, name, kind FROM people ORDER BY id", "SELECT id, person_id, clip, speaker, origin FROM voiceprints ORDER BY id",
                "SELECT * FROM merges ORDER BY from_id", "SELECT * FROM rejections ORDER BY clip, speaker, person_id", "SELECT * FROM assigned ORDER BY clip, speaker"))
                db.rawQuery(q, null).use { c -> while (c.moveToNext()) appendLine((0 until c.columnCount).joinToString(" ") { c.getString(it) ?: "null" }) }
        }
    }

    /** The AS-norm cohort is kept between rechecks, app-wide: each run starts without one, as a fresh app would. */
    private fun forgetCohort() {
        SpeakerStore::class.java.getDeclaredField("cached").apply { isAccessible = true }.set(null, null)
    }

    private data class Run(val result: String, val rewritten: List<Int>, val matched: List<Int>, val read: List<Int>)

    private fun run(fromIndex: Boolean): Run {
        fx.wipe()
        forgetCohort()
        val voices = build()
        fun recheck(): VoiceReview.Recheck = if (fromIndex) VoiceReview(context).recheck() else OldVoiceReview(context).recheck()
        val out = StringBuilder()
        val rewritten = mutableListOf<Int>()
        val matched = mutableListOf<Int>()
        val read = mutableListOf<Int>()
        fun look(what: String) {
            val before = fx.transcriptTexts()
            val r = recheck()
            val after = fx.transcriptTexts()
            rewritten += after.count { (k, v) -> before[k] != v }
            matched += r.matched
            read += r.read
            out.appendLine("== $what: matched ${r.matched}, merged ${r.merged}")
            for ((k, v) in after) out.appendLine("$k $v")
            out.append(dump())
            val suggestions = if (fromIndex) VoiceReview(context).suggestions() else OldVoiceReview(context).suggestions()
            for (s in suggestions) out.appendLine(s.toString())
        }
        look("first look")
        look("again, nothing new")

        // Transcripts that say a little less than a recheck finds: a score off by rounding (not a change),
        // a score off by more, and another top candidate. Only the last two are written again.
        val open = voices.filter { v ->
            val sp = fx.read(v.clip).speakers[v.label]
            sp != null && sp.decision == "uncertain" && sp.candidates.size > 1 && fx.read(v.clip).embeddings.containsKey(v.label)
        }.distinctBy { it.clip }.take(3)
        assertEquals(3, open.size)
        for ((i, v) in open.withIndex()) {
            val t = fx.read(v.clip)
            val sp = t.speakers.getValue(v.label)
            val nudged = when (i) {
                0 -> sp.copy(score = sp.score + 5e-5)
                1 -> sp.copy(score = sp.score + 5e-3)
                else -> sp.copy(candidates = sp.candidates.reversed())
            }
            fx.transcript(t.copy(speakers = t.speakers + (v.label to nudged)))
        }
        look("after small differences")

        // An open voice's top candidate is "not them".
        SpeakerStore(context).use { s -> open[1].let { v -> s.reject(v.clip, v.label, fx.read(v.clip).speakers.getValue(v.label).candidates.first().personId) } }
        look("after a \"not them\"")

        // Someone new is named: another voice to match, and the cohort made again.
        SpeakerStore(context).use { s ->
            val dee = s.newPerson("Dee")
            for (v in voices.filter { it.who == "s1" && it.secs >= 3 }.take(4))
                fx.read(v.clip).embeddings[v.label]?.let { s.addVoiceprint(dee, it.toFloatArray(), v.secs, null, null, "manual") }
        }
        look("after someone new")
        return Run(out.toString(), rewritten, matched, read)
    }

    @Test fun `the index makes the same decisions as reading every transcript`() {
        val old = run(fromIndex = false)
        val new = run(fromIndex = true)
        assertEquals(old.result, new.result)
        // And the made-up archive asks enough of it: voices matched at first, nothing new the second time,
        // two of three small differences written, one "not them" written, and someone new matched.
        assertTrue("matched ${new.matched}", new.matched[0] > 5 && new.matched[4] > 0)
        assertEquals(listOf(0, 2, 1), new.rewritten.subList(1, 4))
        // Reading only what changes: none the second time, then just the transcripts written (not the one off by rounding).
        assertEquals(listOf(0, 2, 1), new.read.subList(1, 4))
    }
}
