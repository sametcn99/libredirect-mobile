package dev.libredirect.mobile.core.routing

import dev.libredirect.mobile.core.url.ParsedUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WildcardPatternTest {
    @Test
    fun `star matches any run including empty`() {
        assertTrue(WildcardPattern.matches("*.example.com", "www.example.com"))
        assertFalse(WildcardPattern.matches("*.example.com", "example.com"))
        assertTrue(WildcardPattern.matches("a*bc", "abc"))
        assertTrue(WildcardPattern.matches("a*bc", "axx-bc"))
    }

    @Test
    fun `no wildcard falls back to exact case-insensitive compare`() {
        assertTrue(WildcardPattern.matches("Example.COM", "example.com"))
        assertFalse(WildcardPattern.matches("example.com", "example.org"))
    }

    @Test
    fun `multiple stars and trailing stars`() {
        assertTrue(WildcardPattern.matches("*x*", "axb"))
        assertTrue(WildcardPattern.matches("abc*", "abcdef"))
        assertFalse(WildcardPattern.matches("abc*", "xabcdef"))
    }

    @Test
    fun `terminates on adversarial input without backtracking blowup`() {
        val pattern = "*a*a*a*a*a*a*a*a*a*b"
        val value = "a".repeat(5000)
        // Must return (false) quickly; an exponential matcher would hang.
        assertFalse(WildcardPattern.matches(pattern, value))
    }
}

class ExceptionWildcardAndSourceAppTest {
    private fun url(
        host: String,
        path: String = "/",
    ): ParsedUrl = ParsedUrl("https", host, path, null, null)

    @Test
    fun `wildcard domain rule matches subdomain`() {
        val rules = listOf(ExceptionRule.Domain("*.example.com"))
        assertTrue(ExceptionMatcher.matches(url("www.example.com"), rules))
        assertFalse(ExceptionMatcher.matches(url("example.org"), rules))
    }

    @Test
    fun `wildcard url-prefix rule matches host plus path segments`() {
        val rules = listOf(ExceptionRule.UrlPrefix("*.example.com/*/live"))
        assertTrue(ExceptionMatcher.matches(url("www.example.com", "/watch/live"), rules))
        assertFalse(ExceptionMatcher.matches(url("www.example.com", "/watch/replay"), rules))
    }

    @Test
    fun `non-wildcard rules keep their previous semantics`() {
        val domain = listOf(ExceptionRule.Domain("example.com"))
        assertTrue(ExceptionMatcher.matches(url("example.com"), domain))
        assertTrue(ExceptionMatcher.matches(url("www.example.com"), domain))
        assertFalse(ExceptionMatcher.matches(url("badexample.com"), domain))
    }

    @Test
    fun `source-app rule matches the reported referrer package`() {
        val rules = listOf(ExceptionRule.SourceApp("com.twitter.android"))
        assertTrue(ExceptionMatcher.matches(url("x.com"), rules, sourceApp = "com.twitter.android"))
        assertFalse(ExceptionMatcher.matches(url("x.com"), rules, sourceApp = "com.other.app"))
        assertFalse(ExceptionMatcher.matches(url("x.com"), rules, sourceApp = null))
    }
}

class ReverseRouterTest {
    private val manifest =
        dev.libredirect.mobile.core.manifest.Manifest(
            schemaVersion = 1,
            revision = 1,
            generatedAt = "2026-11-07T00:00:00Z",
            routes =
                listOf(
                    dev.libredirect.mobile.core.manifest.Route(
                        id = "youtube",
                        name = "YouTube",
                        hosts = listOf("youtube.com"),
                        frontends =
                            listOf(
                                dev.libredirect.mobile.core.manifest.Frontend(
                                    id = "invidious",
                                    name = "Invidious",
                                    strategy = dev.libredirect.mobile.core.manifest.Strategy.ReplaceOrigin,
                                    instances = listOf("https://yewtu.be", "https://inv.example"),
                                ),
                            ),
                    ),
                    dev.libredirect.mobile.core.manifest.Route(
                        id = "reddit",
                        name = "Reddit",
                        hosts = listOf("reddit.com"),
                        frontends =
                            listOf(
                                dev.libredirect.mobile.core.manifest.Frontend(
                                    id = "redlib",
                                    name = "Redlib",
                                    strategy =
                                        dev.libredirect.mobile.core.manifest.Strategy.Template(
                                            "{instance}{path}",
                                        ),
                                    instances = listOf("https://redlib.example"),
                                ),
                                dev.libredirect.mobile.core.manifest.Frontend(
                                    id = "teddit",
                                    name = "Teddit",
                                    strategy =
                                        dev.libredirect.mobile.core.manifest.Strategy.Template(
                                            "{instance}/r{path}",
                                        ),
                                    instances = listOf("https://teddit.example"),
                                ),
                            ),
                    ),
                ),
        )

    @Test
    fun `replace-origin frontend reverses to the first service host`() {
        val original = ReverseRouter.reverse("https://yewtu.be/watch?v=abc&t=2#top", manifest)
        assertEquals("https://youtube.com/watch?v=abc&t=2#top", original)
    }

    @Test
    fun `unknown frontend host is never reversed`() {
        assertEquals(null, ReverseRouter.reverse("https://random.example/watch", manifest))
    }

    @Test
    fun `pure-prefix template reverses deterministically`() {
        val original = ReverseRouter.reverse("https://redlib.example/r/askscience", manifest)
        assertEquals("https://reddit.com/r/askscience", original)
    }

    @Test
    fun `mixed template with literal text is never reversed`() {
        assertEquals(null, ReverseRouter.reverse("https://teddit.example/r/askscience", manifest))
    }

    @Test
    fun `frontend instance of one route does not reverse through another route`() {
        // inv.example belongs to youtube; asking for a reddit-shaped path is
        // still the youtube route — path is copied verbatim.
        val original = ReverseRouter.reverse("https://inv.example/r/askscience", manifest)
        assertEquals("https://youtube.com/r/askscience", original)
    }
}
