package net.boswell.phone

import net.boswell.phone.asr.CloudAsr
import org.junit.Assert.assertEquals
import org.junit.Test

class CloudAsrTest {
    @Test fun `punctuation and case are not differences`() {
        val (a, b) = CloudAsr.shared(CloudAsr.words("let's see what they think"), CloudAsr.words("Let's see, what they think."))
        assert(a.all { it } && b.all { it })
    }

    @Test fun `differing words are marked on both sides`() {
        val (a, b) = CloudAsr.shared(CloudAsr.words("axe body s right"), CloudAsr.words("Axe Body Spray, right"))
        assertEquals(listOf(true, true, false, true), a.toList())
        assertEquals(listOf(true, true, false, true), b.toList())
    }

    @Test fun `edits count word substitutions insertions and deletions`() {
        assertEquals(1 to 4, CloudAsr.edits(CloudAsr.words("a good boy you"), CloudAsr.words("a good boy")))
        assertEquals(0 to 3, CloudAsr.edits(CloudAsr.words("Sit. Good boy!"), CloudAsr.words("sit good boy")))
    }
}
