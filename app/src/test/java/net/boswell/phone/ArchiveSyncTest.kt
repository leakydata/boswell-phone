package net.boswell.phone

import net.boswell.phone.ArchiveFixture.Companion.noisy
import net.boswell.phone.ArchiveFixture.Companion.randomUnit
import net.boswell.phone.ArchiveFixture.Companion.snapshot
import net.boswell.phone.ArchiveFixture.Companion.transcriptOf
import net.boswell.phone.archive.Archive
import net.boswell.phone.archive.ArchiveChanges
import net.boswell.phone.process.SpeakerId
import net.boswell.phone.speakers.Matching
import net.boswell.phone.speakers.SpeakerStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.random.Random

/**
 * The archive's index kept up to date by parts -- conversations regrouped
 * around changed clips, around a changed person, and no look at the files
 * when none changed -- always ends up as rebuilding it whole would.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ArchiveSyncTest {
    private val context: android.content.Context = org.robolectric.RuntimeEnvironment.getApplication()
    private val fx = ArchiveFixture(context)
    private lateinit var archive: Archive
    private lateinit var store: SpeakerStore
    private val rnd = Random(1)

    @Before fun open() { store = SpeakerStore(context); archive = Archive(context) }
    @After fun close() { archive.close(); store.close() }

    private val dim get() = Matching.model.dim
    /** A few voices to hear again and again, so conversation keys link across clips. */
    private val voices by lazy { List(4) { randomUnit(rnd, dim) } }

    private fun speech(name: String, talk: Boolean, people: List<Long?> = listOf(null)) {
        val n = 1 + rnd.nextInt(2)
        val v = (0 until n).associate { i ->
            val pid = people[rnd.nextInt(people.size)]
            "SPEAKER_0$i" to (SpeakerId(null, 0.3, if (pid == null) "none" else "matched", null, emptyList(), pid, 2.0 + rnd.nextDouble() * 8) to
                noisy(rnd, voices[rnd.nextInt(voices.size)], 0.5))
        }
        val t = transcriptOf(name, v)
        fx.transcript(if (talk) t else t.copy(segments = emptyList()))
    }

    private fun addClip(name: String, started: Double, ended: Double, talk: Boolean, people: List<Long?> = listOf(null)) {
        fx.sidecar(name, started, ended)
        speech(name, talk, people)
    }

    private data class Clip(val started: Double, val ended: Double, var talk: Boolean)

    /** Port of the desktop check (inc/sim.py): 200 rounds of 1-3 random changes, each round's regroup against a whole one. */
    @Test fun `regrouping around changed clips equals regrouping everything`() {
        val clips = LinkedHashMap<String, Clip>()
        var t = 1_791_000_000.0
        repeat(120) { i ->
            t += 5 + rnd.nextDouble() * if (rnd.nextDouble() < 0.2) 400 else 60
            val c = Clip(t, t + 5 + rnd.nextDouble() * 25, rnd.nextDouble() < 0.7)
            clips["omi_${c.ended.toLong()}_$i.wav"] = c
            t = c.ended
        }
        for ((n, c) in clips) addClip(n, c.started, c.ended, c.talk)
        archive.sync(store, force = true)
        val base = clips.toMap()
        repeat(200) { step ->
            repeat(1 + rnd.nextInt(3)) { k ->
                val op = rnd.nextDouble()
                val name = base.keys.random(rnd)
                val c = clips[name]
                when {
                    op < 0.4 && c != null -> { c.talk = !c.talk; speech(name, c.talk) }        // transcript changed: speech flips
                    op < 0.6 && c != null -> { clips.remove(name); fx.delete(name) }            // clip deleted
                    else -> {                                                                   // a new clip near an existing one
                        val b = base.getValue(name)
                        val s = b.ended + rnd.nextDouble() * 140 - 20
                        val nc = Clip(s, s + 5 + rnd.nextDouble() * 35, rnd.nextDouble() < 0.8)
                        val nn = "omi_${nc.ended.toLong()}_new${step}_$k.wav"
                        clips[nn] = nc; addClip(nn, nc.started, nc.ended, nc.talk)
                    }
                }
            }
            val r = archive.sync(store)
            assertTrue(r.scanned)
            val incremental = snapshot(archive)
            archive.sync(store, force = true)
            val full = snapshot(archive)
            assertEquals("round $step", full, incremental)
        }
    }

    /**
     * Changes of who someone is (named into someone known, a group taken
     * back off, a voice named by hand, a recording's voice under two people
     * settled), each followed by regrouping only that person's conversations,
     * give what regrouping everything gives.
     */
    @Test fun `regrouping one person's conversations equals regrouping everything`() {
        val ann = store.newPerson("Ann")
        val bob = store.newPerson("Bob")
        val u1 = store.newPerson(null)
        val u2 = store.newPerson(null)
        val people = List<Long?>(20) { null } + listOf(ann, bob, u1, u2)
        var t = 1_791_000_000.0
        val names = mutableListOf<String>()
        repeat(150) { i ->
            t += if (rnd.nextBoolean()) 100 + rnd.nextDouble() * 300 else 10 + rnd.nextDouble() * 40
            val e = t + 10 + rnd.nextDouble() * 20
            val n = "omi_${e.toLong()}_$i.wav"
            addClip(n, t, e, rnd.nextDouble() < 0.85, people)
            names += n
            t = e
        }
        // Voices filed under the unnamed ones, as processing files them.
        for (n in names.shuffled(rnd).take(16)) {
            val label = "SPEAKER_00"
            val emb = fx.read(n).embeddings[label]?.toFloatArray() ?: continue
            store.addVoiceprint(if (rnd.nextBoolean()) u1 else u2, emb, 4.0, n, label, "auto")
        }
        archive.sync(store, force = true)
        val all = archive.readableDatabase.rawQuery("SELECT COUNT(*) FROM conversations", null).use { it.moveToFirst(); it.getInt(0) }

        fun check(what: String, people: Set<Long>) {
            val r = archive.sync(store, people = people)
            val scoped = snapshot(archive)
            archive.sync(store, force = true)
            assertEquals(what, snapshot(archive), scoped)
            assertTrue("$what: regrouped ${r.regrouped} of $all", r.regrouped < all)
        }
        // An unnamed voice named as someone already known: a merge.
        check("named into Bob", setOf(u1, store.name(u1, "Bob")))
        // Some of Bob's voices taken back off him into a new unnamed voice.
        val group = store.groups(bob).first().key
        check("group taken off Bob", setOf(bob, store.unnameGroup(bob, group)))
        // A voice named by hand where nobody was.
        val loose = names.first { n -> fx.read(n).speakers.values.any { it.personId == null } }
        val label = fx.read(loose).speakers.entries.first { it.value.personId == null }.key
        store.assign(loose, label, ann)
        check("a voice named Ann", setOf(ann))
        // A recording's voice under two people: it's Ann's, not u2's.
        val twice = names.first { n -> fx.read(n).embeddings.containsKey("SPEAKER_00") }
        store.addVoiceprint(u2, fx.read(twice).embeddings.getValue("SPEAKER_00").toFloatArray(), 4.0, twice, "SPEAKER_00", "auto")
        archive.sync(store, force = true)
        store.dropVoice(u2, twice, "SPEAKER_00")
        check("one voice, Ann's only", setOf(u2, ann))
    }

    @Test fun `a sync with nothing changed looks at no file`() {
        var t = 1_791_000_000.0
        repeat(30) { i -> addClip("omi_${(t + 20).toLong()}_$i.wav", t, t + 20, true); t += 30 }
        fx.settle()
        assertTrue(archive.sync(store).scanned)
        assertFalse("nothing changed", archive.sync(store).scanned)
        // Another copy of the archive in the same process knows it too.
        Archive(context).use { assertFalse(it.sync(store).scanned) }

        // A clip written (the count goes up): looked at, and found.
        addClip("omi_${(t + 20).toLong()}_new.wav", t, t + 20, true)
        fx.settle()
        val r = archive.sync(store)
        assertTrue(r.scanned)
        assertEquals(1, r.reindexed)
        assertFalse(archive.sync(store).scanned)

        // A change this process didn't count (another process, a restore): the folder's time says so.
        val before = ArchiveChanges.generation
        File(fx.transcripts, "omi_${(t + 20).toLong()}_new.json").delete()
        fx.transcripts.setLastModified(System.currentTimeMillis() - 30_000)
        assertEquals(before, ArchiveChanges.generation)
        assertTrue(archive.sync(store).scanned)

        // A folder that changed a moment ago may change again unseen within its clock's tick: looked at again.
        fx.transcripts.setLastModified(System.currentTimeMillis())
        assertTrue(archive.sync(store).scanned)
        assertTrue(archive.sync(store).scanned)
        fx.settle()
        assertTrue(archive.sync(store).scanned)
        assertFalse(archive.sync(store).scanned)

        // Forced, or with people to regroup: no look at the files, but the regrouping is done.
        val forced = archive.sync(store, force = true)
        assertFalse(forced.scanned)
        assertTrue(forced.regrouped > 0)
    }
}
