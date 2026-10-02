package net.boswell.phone

import net.boswell.phone.archive.LineRow
import net.boswell.phone.ui.transcribedBy
import org.junit.Assert.assertEquals
import org.junit.Test

class TranscribedByTest {
    private fun line(clip: String, cloud: Boolean = false, home: Boolean = false) =
        LineRow(0, clip, 0.0, 1.0, 0.0, null, null, "hi", cloud = cloud, home = home)

    @Test fun `one place each`() {
        assertEquals("Transcribed on the phone", transcribedBy(listOf(line("a"), line("b"))))
        assertEquals("Transcribed in the cloud (Parakeet)", transcribedBy(listOf(line("a", cloud = true))))
        assertEquals("Transcribed at home (your computer)", transcribedBy(listOf(line("a", home = true), line("a", home = true))))
    }

    @Test fun `a mix counts recordings, not lines`() = assertEquals("Transcribed 3 at home, 2 on the phone",
        transcribedBy(listOf(line("a", home = true), line("a", home = true), line("b", home = true), line("c", home = true), line("d"), line("e"))))
}
