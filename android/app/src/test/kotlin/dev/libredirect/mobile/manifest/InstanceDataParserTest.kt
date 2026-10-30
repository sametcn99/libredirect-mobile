package dev.libredirect.mobile.manifest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InstanceDataParserTest {
    @Test
    fun `parses camelCase keys to kebab-case frontend ids`() {
        val raw =
            """
            {
              "invidious": { "clearnet": ["https://inv.nadeko.net/", "https://yewtu.be"], "tor": ["http://onion.onion"] },
              "pipedMaterial": { "clearnet": ["https://piped-material.example"] }
            }
            """.trimIndent().toByteArray()

        val parsed = InstanceDataParser.parse(raw)

        assertEquals(
            mapOf(
                "invidious" to listOf("https://inv.nadeko.net", "https://yewtu.be"),
                "piped-material" to listOf("https://piped-material.example"),
            ),
            parsed,
        )
    }

    @Test
    fun `skips frontends without a clearnet array`() {
        val raw =
            """
            {
              "phantom": { "tor": ["http://onion.onion"] },
              "redlib": { "clearnet": ["https://redlib.example"] }
            }
            """.trimIndent().toByteArray()

        val parsed = InstanceDataParser.parse(raw)

        assertEquals(mapOf("redlib" to listOf("https://redlib.example")), parsed)
    }

    @Test
    fun `drops non-https and non-origin urls`() {
        val raw =
            """
            {
              "rimgo": {
                "clearnet": [
                  "http://rimgo.example",
                  "https://rimgo.example/some/path",
                  "https://Rimgo.Example",
                  "https://rimgo.example:8080",
                  "ftp://rimgo.example"
                ]
              }
            }
            """.trimIndent().toByteArray()

        val parsed = InstanceDataParser.parse(raw)

        assertEquals(mapOf("rimgo" to listOf("https://rimgo.example:8080")), parsed)
    }

    @Test
    fun `caps instances at 64 per frontend`() {
        val urls = (1..70).joinToString(",") { "\"https://host$it.example\"" }
        val raw = """{ "invidious": { "clearnet": [$urls] } }""".trimIndent().toByteArray()

        val parsed = InstanceDataParser.parse(raw)!!

        assertEquals(64, parsed["invidious"]!!.size)
        assertTrue(parsed["invidious"]!!.all { it.startsWith("https://host") })
    }

    @Test
    fun `returns null for malformed payloads`() {
        assertNull(InstanceDataParser.parse("not json".toByteArray()))
        assertNull(InstanceDataParser.parse("""["array"]""".toByteArray()))
        assertNull(InstanceDataParser.parse("""{ "invidious": "not-an-object" }""".toByteArray()))
        assertNull(InstanceDataParser.parse("""{ "invidious": { "clearnet": "not-an-array" } }""".toByteArray()))
        assertNull(InstanceDataParser.parse("""{ "invidious": { "clearnet": [42] } }""".toByteArray()))
    }

    @Test
    fun `returns empty map when nothing is usable`() {
        val parsed = InstanceDataParser.parse("""{ "invidious": { "clearnet": [] } }""".toByteArray())

        assertTrue(parsed!!.isEmpty())
    }
}
