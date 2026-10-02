package net.boswell.phone

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import net.boswell.phone.home.HomeServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HotwordsTest {
    private fun decode(h: String) = Json.parseToJsonElement(h).jsonArray.map { it.jsonPrimitive.content }

    @Test fun `plain names are a JSON array`() =
        assertEquals("""["Omi","Boswell","Morgan Ellis"]""", HomeServer.hotwordsHeader(listOf("Omi", "Boswell", "Morgan Ellis")))

    @Test fun `non-ASCII is escaped and still decodes`() {
        val h = HomeServer.hotwordsHeader(listOf("José", "Zoë \"Z\"", "北京", "😀"))
        assertTrue(h.all { it.code in 0x20..0x7e })
        assertTrue(h.contains("Jos\\u00e9"))
        assertEquals(listOf("José", "Zoë \"Z\"", "北京", "😀"), decode(h))
    }

    @Test fun `blanks and repeats are dropped`() = assertEquals(listOf("Omi"), decode(HomeServer.hotwordsHeader(listOf("Omi", " ", "Omi "))))

    @Test fun `at most 300 terms`() = assertEquals(300, decode(HomeServer.hotwordsHeader((1..500).map { "word$it" })).size)

    @Test fun `at most about 6000 characters`() {
        val h = HomeServer.hotwordsHeader((1..300).map { "é".repeat(10) + it })
        assertTrue(h.length <= 6000)
        assertTrue(decode(h).isNotEmpty())
    }
}
