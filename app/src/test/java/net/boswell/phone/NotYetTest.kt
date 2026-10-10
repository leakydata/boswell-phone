package net.boswell.phone

import net.boswell.phone.assistant.Assistant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotYetTest {
    @Test fun nothing_missing_just_says_how_far_it_reaches() {
        assertEquals("The transcript reaches Fri Oct 10, 3:42 PM.",
            Assistant.notYet("Fri Oct 10, 3:42 PM", 0, 0, 0, false, null))
    }

    @Test fun nothing_at_all_says_nothing() {
        assertNull(Assistant.notYet(null, 0, 0, 0, false, null))
    }

    @Test fun audio_still_on_the_omi_is_named() {
        val s = Assistant.notYet("3:42 PM", 0, 0, 7, false, null)!!
        assertTrue(s, s.startsWith("The transcript reaches 3:42 PM; not in it yet: about 7 min still stored on the Omi"))
        assertTrue(s, s.contains("the Omi next syncs"))
    }

    @Test fun several_gaps_in_one_list() {
        val s = Assistant.notYet("3:42 PM", 1, 12, 3, true, null)!!
        assertTrue(s, s.contains("1 recording waiting to be transcribed, 12 downloaded clips waiting for the phone's charger, about 3 min still stored on the Omi, not downloaded yet and whatever is said while the Omi is out of range"))
    }

    @Test fun sync_mode_names_the_last_visit() {
        val s = Assistant.notYet(null, 0, 0, 0, false, "2:10 PM")!!
        assertTrue(s, s.startsWith("Not in the transcript yet: everything said since the last sync at 2:10 PM"))
    }
}
