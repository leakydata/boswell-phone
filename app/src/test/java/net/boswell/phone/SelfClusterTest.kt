package net.boswell.phone

import android.content.Context
import net.boswell.phone.diarize.VoiceModel
import net.boswell.phone.speakers.Matching
import net.boswell.phone.speakers.SpeakerStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * A named person's self-cluster (AsNorm.SELF_CLUSTER_PRINTS) is found from the
 * speaker store, and the store's cohort is made again when a cluster's
 * voiceprints move, since a self-cluster may come or go with them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SelfClusterTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val fx = ArchiveFixture(context)
    private val model = Matching.model
    private val owner = Matching.owner
    private lateinit var store: SpeakerStore

    @Before fun open() {
        fx.wipe()
        context.getSharedPreferences("boswell", Context.MODE_PRIVATE).edit().putString("voice_model", VoiceModel.SPEAKER_ID.id).remove("owner_person").commit()
        store = SpeakerStore(context)
    }

    @After fun close() {
        store.close()
        Matching.model = model; Matching.owner = owner
    }

    private fun vec(vararg at: Pair<Int, Float>) = FloatArray(Matching.model.dim).also { a -> at.forEach { (i, x) -> a[i] = x } }

    @Test fun `a person's self-cluster is theirs until its voices move`() {
        Matching.model = VoiceModel.SPEAKER_ID
        val me = store.newPerson("Me"); val them = store.newPerson("Them")
        Matching.owner = me
        store.addVoiceprint(me, vec(1 to 1f), 10.0, "omi_1791000000.wav", "SPEAKER_00", "manual")
        store.addVoiceprint(them, vec(0 to 1f), 10.0, "omi_1791000100.wav", "SPEAKER_00", "manual")
        // Six unnamed voices 0.9 like them, in one cluster; one stranger.
        val cluster = store.newPerson(null)
        val prints = List(6) { store.addVoiceprint(cluster, vec(0 to 0.9f, 10 + it to 0.436f), 10.0, "omi_${1791001000 + 100 * it}.wav", "SPEAKER_01", "auto") }
        store.addVoiceprint(store.newPerson(null), vec(2 to 1f), 10.0, "omi_1791009000.wav", "SPEAKER_01", "auto")

        val first = store.norm()!!
        assertEquals(6, first.cohort.who.count { it == them })
        assertEquals(0, first.cohort.who.count { it == me })
        assertSame(first, store.norm())                    // nothing changed: kept

        // Two of them moved to a cluster of their own: four left, no self-cluster.
        val other = store.newPerson(null)
        for (id in prints.take(2)) store.writableDatabase.execSQL("UPDATE voiceprints SET person_id = ? WHERE id = ?", arrayOf<Any>(other, id))
        val second = store.norm()!!
        assertNotSame(first, second)
        assertEquals(0, second.cohort.who.count { it != -1L })
    }
}
