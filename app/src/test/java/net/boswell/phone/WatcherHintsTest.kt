package net.boswell.phone

import net.boswell.phone.assistant.Hints
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WatcherHintsTest {
    private val nietzsche = "Nietzsche's eternal recurrence: Nietzsche asked whether you could will your life to repeat forever, exactly as it was."

    @Test fun the_same_idea_again_is_a_repeat() {
        assertTrue(Hints.repeats("Eternal recurrence, Nietzsche: would you will your life to repeat forever as it was?", listOf(nietzsche)))
    }

    @Test fun something_new_on_the_same_topic_is_not() {
        assertFalse(Hints.repeats("Nietzsche's Übermensch: the person who creates their own values after the death of God.", listOf(nietzsche)))
    }

    @Test fun an_unrelated_hint_is_not() {
        assertFalse(Hints.repeats("Call the dentist: you said you'd book the cleaning before Friday.", listOf(nietzsche)))
    }

    @Test fun nothing_earlier_means_no_repeat() {
        assertFalse(Hints.repeats(nietzsche, emptyList()))
    }
}
