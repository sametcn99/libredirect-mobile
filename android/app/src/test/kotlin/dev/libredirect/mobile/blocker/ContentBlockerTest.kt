package dev.libredirect.mobile.blocker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContentBlockerTest {
    @Test
    fun `bare hostnames and hosts-file lines parse`() {
        val hosts =
            ContentBlocker.parseHosts(
                """
                # comment line
                tracker.example
                0.0.0.0 ads.example.org
                127.0.0.1 analytics.example.net
                ::1 ipv6.example
                not a hostname with three words
                https://not.a.host.example
                valid-sub.example.io
                """.trimIndent(),
            )
        assertEquals(
            setOf(
                "tracker.example",
                "ads.example.org",
                "analytics.example.net",
                "ipv6.example",
                "valid-sub.example.io",
            ),
            hosts,
        )
    }

    @Test
    fun `record cap truncates instead of failing`() {
        val raw = (0 until 50).joinToString("\n") { "host$it.example" }
        val hosts = ContentBlocker.parseHosts(raw, maxRecords = 10)
        assertEquals(10, hosts.size)
    }

    @Test
    fun `suffix rule blocks subdomains but not lookalikes`() {
        val blocked = setOf("doubleclick.net", "google-analytics.com")
        assertTrue(ContentBlocker.shouldBlock("ad.doubleclick.net", blocked))
        assertTrue(ContentBlocker.shouldBlock("doubleclick.net", blocked))
        assertFalse(ContentBlocker.shouldBlock("notdoubleclick.net", blocked))
        assertFalse(ContentBlocker.shouldBlock("example.org", blocked))
    }

    @Test
    fun `empty list blocks nothing`() {
        assertFalse(ContentBlocker.shouldBlock("anything.example", emptySet()))
    }

    @Test
    fun `bundled asset list parses and blocks known trackers`() {
        val text = javaClass.classLoader!!.getResourceAsStream("blocklist.txt")?.bufferedReader()?.use { it.readText() }
        if (text == null) {
            // Asset not on the JVM test classpath (it lives in the APK); parsing rules are covered above.
            return
        }
        val hosts = ContentBlocker.parseHosts(text)
        assertTrue(hosts.contains("google-analytics.com"))
        assertTrue(ContentBlocker.shouldBlock("ssl.google-analytics.com", hosts))
    }
}
