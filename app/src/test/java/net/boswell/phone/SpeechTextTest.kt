package net.boswell.phone

import net.boswell.phone.assistant.SpeechText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SpeechTextTest {
    @Test fun `bold, labels and links are not read out`() {
        assertEquals("You mean The Wave (2019) with Justin Long. Sam said Friday.",
            SpeechText.clean("You mean **The Wave (2019)** with Justin Long. Sam said Friday [L3706]."))
        assertEquals("Details are at a link.", SpeechText.clean("Details are at https://example.com/a?b=c."))
        assertEquals("See the schedule.", SpeechText.clean("See [the schedule](https://x.org/s)."))
    }

    @Test fun `lists and headings become sentences`() {
        assertEquals("Today. Lunch with Sam at noon. Call the plumber.",
            SpeechText.clean("## Today\n- Lunch with **Sam** at noon\n- Call the plumber"))
        assertEquals("Decisions: ship Friday. Bob and Ann to review.",
            SpeechText.clean("Decisions:\n1. ship Friday\n2) Bob & Ann → review"))
    }

    @Test fun `no symbols survive`() {
        val s = SpeechText.clean("*Note:* `code` ~about~ > quoted | col | 🙂 #tag _under_")
        for (ch in "*`~>|#_🙂") assertFalse("found $ch in: $s", s.contains(ch))
    }

    @Test fun `numbers, times and money stay as they are`() {
        assertEquals("It's 3:05 PM, \$42.50, 307,632 people, 1.5%.", SpeechText.clean("It's 3:05 PM, \$42.50, 307,632 people, 1.5%."))
    }
}
