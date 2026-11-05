package dev.libredirect.mobile.core.upstream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ported from the former tools/src/adapters/libredirect-config.test.ts —
 * the hand-verified subset of upstream target regexes this app converts,
 * and the ones it must refuse rather than approximate.
 */
class TargetHostExtractorTest {
    private val extractor = TargetHostExtractor

    @Test
    fun `simple optional www prefix`() {
        val hosts = extractor.extractHosts("^https?:\\/{2}(www\\.)?reuters\\.com\\/")
        assertTrue(hosts!!.containsAll(listOf("reuters.com", "www.reuters.com")))
    }

    @Test
    fun `optional prefix alternation with multiple choices`() {
        val hosts =
            extractor.extractHosts(
                "^https?:\\/{2}(www\\.|m\\.)?youtube.com(\\/|\$)(?!iframe_api\\/|redirect\\/)",
            )
        assertEquals(3, hosts!!.size)
        assertTrue(hosts.containsAll(listOf("youtube.com", "www.youtube.com", "m.youtube.com")))
    }

    @Test
    fun `required non-optional subdomain alternation`() {
        val hosts = extractor.extractHosts("^https?:\\/{2}(i|s)\\.ytimg.com\\/vi\\/.*\\/..*")
        assertEquals(listOf("i.ytimg.com", "s.ytimg.com"), hosts)
    }

    @Test
    fun `alternation in the base domain itself`() {
        val hosts =
            extractor.extractHosts(
                "^https?:\\/{2}(www\\.)?(youtube|youtube-nocookie)\\.com\\/embed\\/..*",
            )
        assertTrue(
            hosts!!.containsAll(
                listOf("youtube.com", "www.youtube.com", "youtube-nocookie.com", "www.youtube-nocookie.com"),
            ),
        )
    }

    @Test
    fun `wildcard subdomain regex character class is unsupported not approximated`() {
        assertNull(extractor.extractHosts("^https?:\\/{2}([a-zA-Z0-9-]+\\.)*quora\\.com\\/"))
    }

    @Test
    fun `negative lookahead path exclusion is unsupported not approximated`() {
        assertNull(
            extractor.extractHosts(
                "^https?:\\/{2}((?!(api|data|blog)\\.)[a-zA-Z0-9-]+\\.(meta\\.)?)?stackexchange\\.com\\/",
            ),
        )
    }

    @Test
    fun `quantified TLD suffix must not be silently truncated into a wrong host`() {
        // Real bug: this used to extract "translate.google" (not a real domain)
        // by dropping the (\.[a-z]{2,3}){1,2} TLD group instead of recognizing
        // it can't be resolved to a finite set.
        assertNull(extractor.extractHosts("^https?:\\/{2}translate\\.google(\\.[a-z]{2,3}){1,2}\\/"))
    }

    @Test
    fun `onion-hash label mixed with a clearnet TLD by cross-product is filtered out`() {
        // Real bug: (reddit|reddittorjg...ooad)\.(com|onion) naively cross-products
        // into nonsense hosts like "reddittorjg...ooad.com". Only the genuine
        // clearnet combinations should survive.
        val onionHash = "reddittorjg6rue252oqsxryoxengawnmo46qy4kyii5wtqnwfj4ooad"
        val hosts =
            extractor.extractHosts(
                "^https?:\\/{2}(www\\.|old\\.)?(reddit|$onionHash)\\.(com|onion)(?=\\/r\\/|\\/?\$)",
            )
        assertTrue(hosts!!.containsAll(listOf("reddit.com", "www.reddit.com", "old.reddit.com")))
        for (host in hosts) {
            assertFalse(host.contains("onion"))
            assertFalse(host.contains("reddittorjg6"))
        }
    }

    @Test
    fun `reserved test TLDs are filtered not treated as real hosts`() {
        assertNull(extractor.extractHosts("^https?:\\/{2}search\\.libredirect\\.invalid"))
    }

    @Test
    fun `pattern without the expected https prefix is unsupported`() {
        assertNull(extractor.extractHosts("^ftp:\\/{2}example\\.com\\/"))
    }

    @Test
    fun `pattern ending right after the host`() {
        assertEquals(listOf("bsky.app"), extractor.extractHosts("^https?:\\/{2}bsky\\.app"))
    }

    @Test
    fun `extracts a safe hostname pattern for wildcard subdomains`() {
        assertEquals(
            "^([a-zA-Z0-9-]+\\.)*quora\\.com$",
            extractor.extractHostPattern("^https?:\\/{2}([a-zA-Z0-9-]+\\.)*quora\\.com\\/"),
        )
    }

    @Test
    fun `extracts hostname patterns with bounded locale TLDs`() {
        assertEquals(
            "^maps\\.google(\\.[a-z]{2,3}){1,2}$",
            extractor.extractHostPattern("^https?:\\/{2}maps\\.google(\\.[a-z]{2,3}){1,2}\\/"),
        )
    }
}
