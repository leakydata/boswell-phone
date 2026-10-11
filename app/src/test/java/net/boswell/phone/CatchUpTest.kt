package net.boswell.phone

import android.content.Context
import net.boswell.phone.ArchiveFixture.Companion.randomUnit
import net.boswell.phone.ArchiveFixture.Companion.transcriptOf
import net.boswell.phone.archive.Archive
import net.boswell.phone.process.CatchUp
import net.boswell.phone.process.Segment
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
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import kotlin.random.Random

/** What catching up at home redoes: everything not done there, however old, but what was corrected by hand. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CatchUpTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val fx = ArchiveFixture(context)
    private val rnd = Random(5)
    private lateinit var store: SpeakerStore
    private lateinit var archive: Archive

    @Before fun open() { fx.wipe(); store = SpeakerStore(context); archive = Archive(context) }
    @After fun close() { archive.close(); store.close() }

    private fun clip(name: String, ended: Long, engine: String, edited: Boolean = false) {
        fx.sidecar(name, ended - 10.0, ended.toDouble())
        File(fx.clips, name).writeBytes(ByteArray(64))   // its sound is still here
        val sp = SpeakerId(null, 0.2, "none", null, emptyList(), null, 6.0, 20.0)
        val extra = if (edited) listOf(Segment(5.0, 6.0, "SPEAKER_00", "fixed by hand", original = "fixd bye hand", edited = true)) else emptyList()
        fx.transcript(transcriptOf(name, mapOf("SPEAKER_00" to (sp to randomUnit(rnd, Matching.model.dim))), extra).copy(engine = engine))
    }

    @Test fun everything_not_done_at_home_is_redone_but_what_was_corrected_by_hand() {
        val old = 1_700_000_000L   // years ago: no age limit any more
        clip("omi_$old.wav", old, "moonshine (phone)")
        clip("omi_${old + 100}.wav", old + 100, "parakeet-tdt-0.6b-v3 (cloud)")
        clip("omi_${old + 200}.wav", old + 200, "parakeet-tdt-0.6b-v3 (home)")
        clip("omi_${old + 300}.wav", old + 300, "moonshine (phone)", edited = true)
        clip("omi_${old + 400}.wav", old + 400, "moonshine (phone)")
        val me = store.newPerson("Me")
        store.addVoiceprint(me, randomUnit(rnd, Matching.model.dim), 6.0, "omi_${old + 400}.wav", "SPEAKER_00", "manual")
        archive.sync(store, force = true)

        val notHome = CatchUp.notHome(archive)
        assertEquals(listOf(old + 400, old + 300, old + 100, old).map { "omi_$it.wav" }, notHome)   // newest first, home's left out
        val hand = CatchUp.byHand(archive, store)
        assertEquals(setOf("omi_${old + 300}.wav", "omi_${old + 400}.wav"), hand)

        val auto = CatchUp.pick(notHome, emptySet(), emptySet(), automatic = true, leave = hand)
        assertEquals(listOf("omi_${old + 100}.wav", "omi_$old.wav"), auto)
        // "Redo them at home" asks for all of them, corrected ones too.
        assertEquals(notHome, CatchUp.pick(notHome, notHome.toSet(), emptySet(), automatic = true, leave = hand))
        // Off: only what was asked for; one that failed at home isn't tried again until asked.
        assertEquals(emptyList<String>(), CatchUp.pick(notHome, emptySet(), emptySet(), automatic = false, leave = hand))
        assertFalse("omi_$old.wav" in CatchUp.pick(notHome, emptySet(), setOf("omi_$old.wav"), automatic = true, leave = hand))
    }

    @Test fun a_tv_the_phone_marked_itself_is_not_a_correction() {
        clip("omi_1700000000.wav", 1_700_000_000L, "moonshine (phone)")
        val tv = store.newPerson(null)
        store.setKind(tv, "media")
        store.addVoiceprint(tv, randomUnit(rnd, Matching.model.dim), 6.0, "omi_1700000000.wav", "SPEAKER_00", "auto")
        archive.sync(store, force = true)
        assertTrue(CatchUp.byHand(archive, store).isEmpty())
    }
}
