package net.boswell.phone

import net.boswell.phone.assistant.Texting
import org.junit.Assert.assertEquals
import org.junit.Test

class TextingTest {
    @Test fun `numbers compare by their last ten digits`() {
        assertEquals("5555550123", Texting.digits("+1 (555) 555-0123"))
        assertEquals(Texting.digits("555-555-0123"), Texting.digits("15555550123"))
    }

    @Test fun `only a real yes sends`() {
        for (w in listOf("yes", "Yes, send it", "yeah go ahead", "send it", "Okay send it to him", "confirm"))
            assert(Texting.isConfirmation(w)) { w }
        for (w in listOf("no", "don't send it", "wait, change it to 3pm", "send it later? no, not yet", "never mind", "what did he say",
                         "can you send a text to Dan", null, ""))
            assert(!Texting.isConfirmation(w)) { w.toString() }
    }
}
