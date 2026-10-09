package net.boswell.phone

import android.content.Context
import net.boswell.phone.ArchiveFixture.Companion.noisy
import net.boswell.phone.ArchiveFixture.Companion.randomUnit
import net.boswell.phone.ArchiveFixture.Companion.transcriptOf
import net.boswell.phone.archive.Archive
import net.boswell.phone.diarize.VoiceModel
import net.boswell.phone.process.Candidate
import net.boswell.phone.process.ClipActions
import net.boswell.phone.process.ProcessingWorker
import net.boswell.phone.process.SpeakerId
import net.boswell.phone.speakers.Matching
import net.boswell.phone.speakers.SpeakerStore
import net.boswell.phone.speakers.VoiceReview
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.random.Random

/**
 * A voice relabeled by hand stays relabeled: "It's a TV" on a voice auto-filed
 * under someone (the owner too), and a voice split in two, hold in the speaker
 * store, the transcripts and the conversation's voices, and through a recheck
 * and a clip done again.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RelabelTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val fx = ArchiveFixture(context)
    private val model = Matching.model
    private val owner = Matching.owner
    private val rnd = Random(3)
    private lateinit var store: SpeakerStore
    private lateinit var archive: Archive

    @Before fun open() {
        fx.wipe()
        val prefs = context.getSharedPreferences("boswell", Context.MODE_PRIVATE)
        prefs.edit().putString("voice_model", VoiceModel.SPEAKER_ID.id).remove("owner_person").commit()
        store = SpeakerStore(context)
        archive = Archive(context)
    }

    @After fun close() {
        archive.close(); store.close()
        Matching.model = model; Matching.owner = owner
    }

    private val dim get() = Matching.model.dim

    /** Someone named, with references made by hand around [center]. */
    private fun person(name: String, center: FloatArray): Long = store.newPerson(name).also { id ->
        repeat(5) { store.addVoiceprint(id, noisy(rnd, center, 0.3), 6.0, null, null, "manual") }
    }

    private class Slot(val clip: String, val label: String, val emb: FloatArray)

    /**
     * One conversation of [n] clips, 12 s apart, each with one voice like [center]:
     * recorded as matched to [recorded], and filed as an auto voiceprint of [filed] if given.
     */
    private fun conversation(n: Int, center: FloatArray, recorded: Long?, filed: Long? = null): List<Slot> = List(n) { i ->
        val ended = 1_791_000_000L + 12 * (i + 1)
        val clip = "omi_$ended.wav"
        val emb = noisy(rnd, center, 0.2)
        fx.sidecar(clip, ended - 10.0, ended.toDouble())
        val sp = if (recorded == null) SpeakerId(null, 0.2, "none", null, emptyList(), null, 6.0, 20.0)
            else SpeakerId(store.nameOf(recorded), 0.8, "matched", 0.3, listOf(Candidate(recorded, store.nameOf(recorded), 0.8, 1)), recorded, 6.0, 20.0)
        fx.transcript(transcriptOf(clip, mapOf("SPEAKER_00" to (sp to emb))))
        if (filed != null) store.addVoiceprint(filed, emb, 6.0, clip, "SPEAKER_00", "auto")
        Slot(clip, "SPEAKER_00", emb)
    }

    private fun onlyConversation(): Long = archive.readableDatabase.rawQuery("SELECT id FROM conversations", null).use { c ->
        assertTrue(c.moveToFirst()); c.getLong(0).also { assertTrue("one conversation", !c.moveToNext()) }
    }

    private fun keyOf(s: Slot): String? = archive.speakersInClip(s.clip).first { it.first == s.label }.second

    /** Who each slot is, every way it's asked: the store one at a time and at once, the transcript, and the conversation's key. */
    private fun assertAll(slots: List<Slot>, who: Long) {
        val all = store.currentPeople()
        for (s in slots) {
            val recorded = fx.read(s.clip).speakers.getValue(s.label).personId
            assertEquals(who, recorded)
            assertEquals(who, store.currentPerson(s.clip, s.label, recorded))
            assertEquals(who, all(s.clip, s.label, recorded))
            assertEquals("p$who", keyOf(s))
        }
    }

    private fun markedTv(filedUnder: Long, center: FloatArray) {
        val slots = conversation(3, center, recorded = filedUnder, filed = filedUnder)
        archive.sync(store, force = true)
        val conv = onlyConversation()
        assertEquals("p$filedUnder", keyOf(slots[0]))

        val touched = ClipActions.markMedia(context, store, archive, conv, "p$filedUnder", filedUnder, named = true)
        archive.sync(store, people = touched)
        val tv = store.currentPerson(slots[0].clip, slots[0].label, null)!!
        assertTrue(filedUnder in touched && tv in touched)
        assertNotEquals(filedUnder, tv)
        assertEquals("media", store.kindOf(tv))
        assertNull(store.nameOf(tv))
        assertNull("the person is not a TV", store.kindOf(filedUnder))
        assertAll(slots, tv)
        for (s in slots) {
            assertTrue(filedUnder in store.rejected(s.clip, s.label))
            // Not offered back as a guess either.
            assertTrue(fx.read(s.clip).speakers.getValue(s.label).candidates.none { it.personId == filedUnder })
        }
        assertEquals(listOf("p$tv"), archive.conversation(conv)!!.speakers)

        // Looked at again, it stays a TV, however much it sounds like them.
        VoiceReview(context).recheck()
        archive.sync(store, force = true)
        assertAll(slots, tv)
        // And done again (ProcessingWorker's "again"), it stays one too.
        for (s in slots) assertEquals(tv, ProcessingWorker.identify(store, s.clip, s.label, s.emb, 6.0, again = true, snr = 20.0).personId)
    }

    @Test fun `a voice auto-filed under someone, marked a TV, stays a TV`() {
        person("Me", randomUnit(rnd, dim))
        val bea = randomUnit(rnd, dim)
        markedTv(person("Bea", bea), bea)
    }

    @Test fun `a voice auto-filed under the owner, marked a TV, stays a TV`() {
        val center = randomUnit(rnd, dim)
        val me = person("Me", center)
        context.getSharedPreferences("boswell", Context.MODE_PRIVATE).edit().putLong("owner_person", me).commit()
        Matching.owner = me
        person("Bea", randomUnit(rnd, dim))
        markedTv(me, center)
    }

    @Test fun `regrouping only the conversation it was labeled in is the same as regrouping everything`() {
        // What the screens do now (ArchiveViewModel.actOn): no people, just this conversation's span.
        val center = randomUnit(rnd, dim)
        val me = person("Me", center)
        context.getSharedPreferences("boswell", Context.MODE_PRIVATE).edit().putLong("owner_person", me).commit()
        Matching.owner = me
        val bea = person("Bea", randomUnit(rnd, dim))
        val slots = conversation(4, center, recorded = me, filed = me)
        archive.sync(store, force = true)
        val conv = onlyConversation()
        val span = archive.spanOf(conv)!!

        val moving = slots.filterIndexed { i, _ -> i == 0 || i == 2 }
        ClipActions.split(context, store, archive, conv, "p$me", me, moving.map { it.clip to it.label }.toSet(), bea)
        archive.sync(store, span = span)
        assertAll(moving, bea)
        assertAll(slots - moving.toSet(), me)
        val parts = ArchiveFixture.snapshot(archive)
        archive.sync(store, force = true)
        assertEquals(parts, ArchiveFixture.snapshot(archive))

        ClipActions.markMedia(context, store, archive, conv, "p$me", me, named = true)
        archive.sync(store, span = span)
        val tv = store.currentPerson(slots[1].clip, slots[1].label, null)!!
        assertEquals("media", store.kindOf(tv))
        assertAll(slots - moving.toSet(), tv)
        val again = ArchiveFixture.snapshot(archive)
        archive.sync(store, force = true)
        assertEquals(again, ArchiveFixture.snapshot(archive))
    }

    @Test fun `splitting two of four parts moves exactly those`() {
        val center = randomUnit(rnd, dim)
        val me = person("Me", center)
        val bea = person("Bea", randomUnit(rnd, dim))
        val slots = conversation(4, center, recorded = me, filed = me)
        archive.sync(store, force = true)
        val conv = onlyConversation()
        for (s in slots) assertEquals("p$me", keyOf(s))

        val moving = slots.filterIndexed { i, _ -> i == 1 || i == 3 }
        val touched = ClipActions.split(context, store, archive, conv, "p$me", me, moving.map { it.clip to it.label }.toSet(), bea)
        assertEquals(setOf(me, bea), touched)
        archive.sync(store, people = touched)
        assertAll(moving, bea)
        val staying = slots - moving.toSet()
        assertAll(staying, me)
        assertEquals(setOf("p$me", "p$bea"), archive.conversation(conv)!!.speakers.toSet())
        // The same as regrouping everything.
        val parts = ArchiveFixture.snapshot(archive)
        archive.sync(store, force = true)
        assertEquals(parts, ArchiveFixture.snapshot(archive))

        VoiceReview(context).recheck()
        archive.sync(store, force = true)
        assertAll(moving, bea)
        assertAll(staying, me)
    }

    @Test fun `a voice never filed, split into a new unnamed voice, stays two`() {
        person("Me", randomUnit(rnd, dim))
        val slots = conversation(4, randomUnit(rnd, dim), recorded = null)
        archive.sync(store, force = true)
        val conv = onlyConversation()
        val key = keyOf(slots[0])!!
        assertTrue(key.startsWith("v") && slots.all { keyOf(it) == key })

        val moving = slots.take(2)
        val fresh = store.newPerson(null)
        val touched = ClipActions.split(context, store, archive, conv, key, null, moving.map { it.clip to it.label }.toSet(), fresh, "auto")
        archive.sync(store, people = touched)
        val stays = store.currentPerson(slots[3].clip, slots[3].label, null)!!
        assertNotEquals(fresh, stays)
        assertAll(moving, fresh)
        assertAll(slots.drop(2), stays)

        // Alike enough to be folded into one unnamed voice, but they were said to be two.
        val r = VoiceReview(context).recheck()
        assertEquals(0, r.merged)
        archive.sync(store, force = true)
        assertAll(moving, fresh)
        assertAll(slots.drop(2), stays)
    }
}
