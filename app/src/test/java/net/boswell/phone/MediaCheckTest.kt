package net.boswell.phone

import android.content.Context
import net.boswell.phone.archive.Conversation
import net.boswell.phone.archive.LineRow
import net.boswell.phone.speakers.MediaCheck
import net.boswell.phone.speakers.SpeakerStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MediaCheckTest {
    private val conv = Conversation(7, 1_000.0, 2_000.0, 3, 300.0, listOf("p1", "p5", "v1", "p2"), "", listOf("Speech", "Television"))
    private val words = (1..50).joinToString(" ") { "word$it" }
    private var n = 0L
    private fun line(key: String?, text: String, clip: String = "omi_1.wav", label: String = "SPEAKER_0${key?.last() ?: '9'}") =
        LineRow(++n, clip, n.toDouble(), n + 1.0, 0.0, key, key?.takeIf { it.startsWith("p") }?.drop(1)?.toLong(), text, label = label)

    /** The owner is #1, #2 is named, #5 is an unnamed voice, v1 was never filed. */
    private fun open(key: String, personId: Long?) = key.startsWith("v") || personId == 5L

    private val lines = listOf(
        line("p1", "Did you feed the dog"), line("p2", "Yes I did"),
        line("p5", words), line("v1", words, clip = "omi_2.wav", label = "SPEAKER_03"),
        line("p1", words), line("p2", words), line("p5", "short"))

    @Test fun only_unlabeled_voices_with_enough_words_are_asked_about() {
        val vs = MediaCheck.candidates(conv, lines, ::open, emptySet())
        assertEquals(listOf("p5", "somi_2.wav|SPEAKER_03"), vs.map { it.id })
        // Not the owner or a named person, however much they said.
        assertTrue(vs.none { it.key == "p1" || it.key == "p2" })
    }

    @Test fun jev_sees_the_conversation_by_letter_from_just_before_the_voice() {
        val tv = MediaCheck.candidates(conv, lines, ::open, emptySet()).first { it.key == "v1" }
        // Letters go by who speaks first: p1 A, p2 B, p5 C, v1 D.
        assertEquals("D", tv.target)
        assertEquals(listOf("A", "B", "C", "D", "A", "B", "C"), tv.lines.map { it.first })
        assertEquals(listOf("Speech", "Television"), tv.sounds)
    }

    @Test fun what_was_asked_is_not_asked_again() {
        val vs = MediaCheck.candidates(conv, lines, ::open, setOf("p5"))
        assertEquals(listOf("v1"), vs.map { it.key })
    }

    @Test fun the_window_stops_at_its_word_limit() {
        val long = List(100) { line("v1", words) }
        val v = MediaCheck.candidates(conv, long, ::open, emptySet()).single()
        assertTrue(v.lines.sumOf { it.second.split(" ").size } <= MediaCheck.MAX_WORDS + 50)
    }

    @Test fun sure_answers_are_marked_likely_ones_offered_the_rest_left() {
        assertEquals("marked", MediaCheck.outcome(0.97))
        assertEquals("marked", MediaCheck.outcome(MediaCheck.MARK))
        assertEquals("suggested", MediaCheck.outcome(0.6))
        assertEquals("live", MediaCheck.outcome(0.49))
    }

    @Test fun answers_are_kept_and_a_dismissed_suggestion_is_not_offered_again() {
        val c: Context = RuntimeEnvironment.getApplication()
        c.deleteDatabase("speakers.db")
        val store = SpeakerStore(c)
        try {
            store.setMediaAnswer("p5", 0.7, "radio_podcast", "suggested")
            assertEquals(0.7, MediaCheck.suggestion(store, "p5", lines)!!, 1e-9)
            assertEquals(setOf("p5"), store.mediaAnswers().keys)
            MediaCheck.dismiss(store, "p5", lines)
            assertNull(MediaCheck.suggestion(store, "p5", lines))
            assertEquals("dismissed", store.mediaAnswer("p5")!!.outcome)
        } finally { store.close() }
    }
}
