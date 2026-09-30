package net.boswell.phone

import net.boswell.phone.assistant.Trigger
import net.boswell.phone.assistant.Triggers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TriggersTest {
    private val remind = Triggers.DEFAULTS.first { it.id == 1L }

    @Test fun `phrases match regardless of case and punctuation`() {
        assertEquals("remind me", Triggers.match(remind, "Okay, REMIND me to call Sam tomorrow."))
        assertEquals("don't let me forget", Triggers.match(remind, "Don’t let me forget the keys"))
    }

    @Test fun `phrases match whole words only`() {
        assertNull(Triggers.match(Trigger(9, listOf("note"), Trigger.Action.TODO), "that's notable"))
        assertNull(Triggers.match(remind, "he reminds me of someone"))
    }

    @Test fun `a phrase can be the whole line`() {
        assertEquals("hey boswell", Triggers.match(Triggers.DEFAULTS.first { it.id == 5L }, "Hey Boswell"))
    }
}
