package net.boswell.phone

import net.boswell.phone.asr.Vocabulary
import net.boswell.phone.asr.Word
import org.junit.Assert.assertEquals
import org.junit.Test

class VocabularyTest {
    private val terms = listOf("Omi", "Boswell", "Nathan Jones", "Nathan")
    private fun fix(s: String): String =
        Vocabulary.apply(s.split(" ").mapIndexed { i, w -> Word(w, i.toDouble(), i + 0.5) }, terms).joinToString(" ") { it.text }

    @Test fun `case is fixed`() = assertEquals("reposition the Omi.", fix("reposition the omi."))
    @Test fun `a split made only of common words is left alone`() = assertEquals("the boss well I mean", fix("the boss well I mean"))
    @Test fun `near spellings of a split are joined`() = assertEquals("ask Boswell about it", fix("ask bos well about it"))
    @Test fun `long names one letter off are fixed`() = assertEquals("Nathan Jones said so", fix("Nathen Jones said so"))
    @Test fun `common words are never replaced`() {
        assertEquals("I went home today", fix("I went home today"))
        assertEquals("oh me oh my", fix("oh me oh my"))
        assertEquals("the army moved", fix("the army moved"))
    }
    @Test fun `already right is untouched`() = assertEquals("Omi and Boswell", fix("Omi and Boswell"))
    @Test fun `times span the replaced words`() {
        val w = Vocabulary.apply(listOf(Word("bos", 1.0, 1.4), Word("well,", 1.4, 1.9), Word("hi", 2.0, 2.2)), listOf("Boswell"))
        assertEquals(listOf("Boswell,", "hi"), w.map { it.text })
        assertEquals(1.0, w[0].start, 0.0); assertEquals(1.9, w[0].end, 0.0)
    }
}

/** Local only: every change the vocabulary would make to real transcripts. -Ddiar.vocabdir=<transcripts> */
class VocabularyRealTest {
    @Test fun listChanges() {
        val dir = System.getProperty("diar.vocabdir")?.let { java.io.File(it) }
        org.junit.Assume.assumeTrue(dir != null)
        val terms = listOf("Omi", "Boswell", "Nathan Jones", "Nathan")
        var n = 0
        for (f in dir!!.listFiles()!!.filter { it.name.endsWith(".json") }.sortedBy { it.name }) {
            val t = runCatching { net.boswell.phone.process.TranscriptJson.json.decodeFromString(net.boswell.phone.process.Transcript.serializer(), f.readText()) }.getOrNull() ?: continue
            for (seg in t.segments) {
                val text = seg.text
                val ws = text.split(" ").filter { it.isNotBlank() }
                val fixed = Vocabulary.apply(ws.mapIndexed { k, w -> Word(w, k.toDouble(), k + 0.5) }, terms).joinToString(" ") { it.text }
                if (fixed != ws.joinToString(" ")) { n++; println("CHANGE ${f.name}: \"$text\" -> \"$fixed\"") }
            }
        }
        println("CHANGES $n")
    }
}
