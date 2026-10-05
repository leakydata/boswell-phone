package net.boswell.phone

import net.boswell.phone.assistant.Claims
import net.boswell.phone.assistant.FactCheckRow
import net.boswell.phone.assistant.FactLimit
import net.boswell.phone.assistant.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FactCheckTest {
    // ------------------------------------------------------------ spotting

    @Test fun claims_are_read_from_the_reply() {
        val raw = """Here you go:
            ```json
            {"claims": [{"line": "L42", "time": "3:05", "speaker": "Ana", "words": "the Eiffel Tower is 500 meters tall",
                         "claim": "The Eiffel Tower is 500 meters tall."},
                        {"line": 43, "words": "", "claim": "Napoleon was short."},
                        {"line": "L44", "words": "no claim here"}]}
            ```"""
        val c = Claims.parse(raw)!!
        assertEquals(2, c.size)
        assertEquals(42L, c[0].line)
        assertEquals("Ana", c[0].speaker)
        assertEquals("the Eiffel Tower is 500 meters tall", c[0].words)
        assertEquals(43L, c[1].line)
        // No words given: the claim stands in.
        assertEquals("Napoleon was short.", c[1].words)
    }

    @Test fun nothing_worth_checking_is_an_empty_list_not_a_failure() {
        assertEquals(emptyList<Claims.Spotted>(), Claims.parse("""{"claims": []}"""))
    }

    @Test fun a_reply_that_isnt_json_is_a_failure() {
        assertNull(Claims.parse("Nothing to check here."))
        assertNull(Claims.parse("""{"notify": false}"""))
    }

    private fun line(id: Long, text: String) = Claims.Line(id, 100.0 + id, 103.0 + id, "c1", 0.0, "p1", "Ana", text)

    @Test fun a_claim_is_found_on_its_line_or_by_its_words() {
        val lines = listOf(line(1, "So anyway, the Eiffel Tower is 500 meters tall, right?"), line(2, "No way."))
        assertEquals(1L, Claims.locate(Claims.Spotted(1, null, null, "x", "x"), lines)?.id)
        // A wrong id (or none): its words find it.
        assertEquals(1L, Claims.locate(Claims.Spotted(99, null, null, "the Eiffel Tower is 500 meters tall", "x"), lines)?.id)
        // Not on any new line (from the context, or made up): not checked.
        assertNull(Claims.locate(Claims.Spotted(99, null, null, "the moon is made of cheese", "x"), lines))
    }

    // ---------------------------------------------------------- duplicates

    @Test fun the_same_claim_put_differently_is_a_repeat() {
        assertTrue(Claims.repeats("The Eiffel Tower was built in 1889.", listOf("The Eiffel Tower was completed in 1889.")))
        assertTrue(Claims.repeats("Eiffel Tower is 500 meters tall", listOf("The Eiffel Tower is 500 meters tall.")))
    }

    @Test fun another_claim_about_the_same_thing_is_not() {
        assertFalse(Claims.repeats("The Eiffel Tower was built in 1889.", listOf("The Eiffel Tower is in Paris.")))
        assertFalse(Claims.repeats("The Eiffel Tower was built in 1889.", listOf("The Statue of Liberty was dedicated in 1886.")))
        assertFalse(Claims.repeats("The Eiffel Tower was built in 1889.", emptyList()))
    }

    // ---------------------------------------------------------- the limit

    @Test fun the_hourly_limit_counts_only_the_last_hour() {
        val now = 10_000.0
        assertEquals(6, FactLimit.room(now, emptyList(), 6))
        assertEquals(1, FactLimit.room(now, listOf(now - 100, now - 200, now - 300, now - 400, now - 3599), 6))
        assertEquals(0, FactLimit.room(now, List(6) { now - it * 60 }, 6))
        // Older than an hour: room again.
        assertEquals(6, FactLimit.room(now, List(6) { now - 3700 - it }, 6))
    }

    // ------------------------------------------------------------- verdicts

    @Test fun false_and_misleading_are_told_true_only_when_asked_and_the_rest_never() {
        assertTrue(Verdict.FALSE.notifies(alsoTrue = false))
        assertTrue(Verdict.MISLEADING.notifies(alsoTrue = false))
        assertFalse(Verdict.TRUE.notifies(alsoTrue = false))
        assertTrue(Verdict.TRUE.notifies(alsoTrue = true))
        for (v in listOf(Verdict.UNCLEAR, Verdict.MISHEARD)) { assertFalse(v.notifies(false)); assertFalse(v.notifies(true)) }
    }

    @Test fun the_verdict_comes_after_the_reasoning() {
        val raw = """The tower is 330 m including antennas (it was 300 m at {opening}).
            {"verdict": "FALSE", "explanation": "The Eiffel Tower is about 330 meters tall, not 500.", "source": "https://en.wikipedia.org/wiki/Eiffel_Tower"}"""
        val v = Claims.verdict(raw)!!
        assertEquals(Verdict.FALSE, v.verdict)
        assertEquals("https://en.wikipedia.org/wiki/Eiffel_Tower", v.source)
        assertEquals("en.wikipedia.org", Claims.site(v.source!!))
        assertEquals(Verdict.FALSE, Verdict.ofAnswer(v.answer(42)))
        assertTrue(v.answer(42).endsWith("[L42]"))
    }

    @Test fun a_missing_source_falls_back_to_a_citation_and_odd_verdicts_are_read() {
        val v = Claims.verdict("""{"verdict": "Partly true", "explanation": "Close, but it was 1887 when building began."}""", listOf("https://www.toureiffel.paris/en"))!!
        assertEquals(Verdict.MISLEADING, v.verdict)
        assertEquals("https://www.toureiffel.paris/en", v.source)
        assertEquals(Verdict.MISHEARD, Verdict.parse("misheard"))
        assertNull(Claims.verdict("""{"verdict": "who knows", "explanation": "x"}"""))
    }

    @Test fun a_check_belongs_to_the_line_it_overlaps() {
        val f = FactCheckRow(1, 0.0, "c", "w", "Ana", "clip1", 5.0, 105.0, 108.0, null, "FALSE", "e", null, 0.01, null)
        assertTrue(f.on("clip1", 104.0, 106.0))
        assertFalse(f.on("clip1", 110.0, 112.0))
        assertFalse(f.on("clip2", 104.0, 106.0))
    }
}
