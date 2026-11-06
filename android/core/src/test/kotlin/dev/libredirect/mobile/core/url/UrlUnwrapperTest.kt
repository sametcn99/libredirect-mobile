package dev.libredirect.mobile.core.url

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlUnwrapperTest {
    @Test
    fun `google search redirect unwraps to target`() {
        val result =
            UrlUnwrapper.unwrapOnce("https://www.google.com/url?q=https%3A%2F%2Fexample.org%2Fpage&sa=D")
        assertEquals("https://example.org/page", result)
    }

    @Test
    fun `facebook and instagram wrappers unwrap`() {
        assertEquals(
            "https://example.org/a",
            UrlUnwrapper.unwrapOnce("https://l.facebook.com/l.php?u=https%3A%2F%2Fexample.org%2Fa"),
        )
        assertEquals(
            "https://example.org/b",
            UrlUnwrapper.unwrapOnce("https://l.instagram.com/?u=https%3A%2F%2Fexample.org%2Fb"),
        )
    }

    @Test
    fun `youtube redirect and slack-redir unwrap`() {
        assertEquals(
            "https://example.org/c",
            UrlUnwrapper.unwrapOnce("https://www.youtube.com/redirect?q=https%3A%2F%2Fexample.org%2Fc"),
        )
        assertEquals(
            "https://example.org/d",
            UrlUnwrapper.unwrapOnce("https://slack-redir.net/link?url=https%3A%2F%2Fexample.org%2Fd"),
        )
    }

    @Test
    fun `out reddit path wrapper unwraps`() {
        assertEquals(
            "https://example.org/e",
            UrlUnwrapper.unwrapOnce("https://out.reddit.com/out?url=https%3A%2F%2Fexample.org%2Fe"),
        )
    }

    @Test
    fun `amp cache url unwraps`() {
        assertEquals(
            "https://example.org/page",
            UrlUnwrapper.unwrapOnce("https://www-example-com.cdn.ampproject.org/c/s/example.org/page"),
        )
    }

    @Test
    fun `google amp url unwraps`() {
        assertEquals(
            "https://example.org/page",
            UrlUnwrapper.unwrapOnce("https://www.google.com/amp/s/example.org/page"),
        )
    }

    @Test
    fun `nested wrappers unwrap up to the depth limit`() {
        val nested =
            "https://www.google.com/url?q=" +
                "https%3A%2F%2Fl.facebook.com%2Fl.php%3Fu%3Dhttps%253A%252F%252Fexample.org%252Ffinal"
        val result = UrlUnwrapper.unwrapAll(nested)
        assertEquals("https://example.org/final", result.url)
        assertEquals(2, result.count)
    }

    @Test
    fun `unwrap loops are bounded`() {
        val loop =
            "https://www.google.com/url?q=" + "https%3A%2F%2Fwww.google.com%2Furl%3Fq%3D".repeat(3) +
                "https%253A%252F%252Fexample.org"
        val result = UrlUnwrapper.unwrapAll(loop)
        assertTrue(result.count <= UrlUnwrapper.MAX_UNWRAP_DEPTH)
    }

    @Test
    fun `unknown hosts and non-wrapper shapes are untouched`() {
        assertNull(UrlUnwrapper.unwrapOnce("https://example.org/page"))
        assertNull(UrlUnwrapper.unwrapOnce("https://www.google.com/search?q=hello"))
    }

    @Test
    fun `target that fails validation keeps the original`() {
        // Localhost target must not be produced as a "wrapped" destination.
        assertNull(UrlUnwrapper.unwrapOnce("https://l.facebook.com/l.php?u=http%3A%2F%2Flocalhost%3A8080%2Fx"))
        assertNull(UrlUnwrapper.unwrapOnce("https://l.facebook.com/l.php?u=javascript%3Aalert(1)"))
    }
}

class ShortLinkPolicyTest {
    @Test
    fun `known shorteners are allowed`() {
        assertTrue(ShortLinkPolicy.isAllowed("t.co"))
        assertTrue(ShortLinkPolicy.isAllowed("bit.ly"))
        assertTrue(ShortLinkPolicy.isAllowed("T.CO"))
    }

    @Test
    fun `subdomains and strangers are not allowed`() {
        assertTrue(!ShortLinkPolicy.isAllowed("evil.t.co"))
        assertTrue(!ShortLinkPolicy.isAllowed("t.co.evil.com"))
        assertTrue(!ShortLinkPolicy.isAllowed("example.com"))
    }
}

class TrackingParamsExtensionTest {
    @Test
    fun `amazon prefixes are stripped only on amazon hosts`() {
        assertEquals(
            "https://amazon.de/dp/B0X?keep=1",
            TrackingParams.stripFrom(
                "https://amazon.de/dp/B0X?ref_=nav&pd_rd_w=abc&keep=1",
                UrlParser.parse("https://amazon.de/dp/B0X?ref_=nav&pd_rd_w=abc&keep=1")!!,
            ),
        )
        assertEquals(
            "https://example.org/?ref_=nav",
            TrackingParams.stripFrom(
                "https://example.org/?ref_=nav",
                UrlParser.parse("https://example.org/?ref_=nav")!!,
            ),
        )
    }

    @Test
    fun `x com strips s and t but other hosts keep them`() {
        assertEquals(
            "https://x.com/user/status/1",
            TrackingParams.stripFrom(
                "https://x.com/user/status/1?s=20&t=abc",
                UrlParser.parse("https://x.com/user/status/1?s=20&t=abc")!!,
            ),
        )
        assertEquals(
            "https://example.com/a?s=20",
            TrackingParams.stripFrom("https://example.com/a?s=20", UrlParser.parse("https://example.com/a?s=20")!!),
        )
    }

    @Test
    fun `spotify and linkedin scoped parameters`() {
        assertEquals(
            "https://open.spotify.com/track/1",
            TrackingParams.stripFrom(
                "https://open.spotify.com/track/1?si=abc",
                UrlParser.parse("https://open.spotify.com/track/1?si=abc")!!,
            ),
        )
        assertEquals(
            "https://www.linkedin.com/posts/x",
            TrackingParams.stripFrom(
                "https://www.linkedin.com/posts/x?trk=feed&trackingId=zz",
                UrlParser.parse("https://www.linkedin.com/posts/x?trk=feed&trackingId=zz")!!,
            ),
        )
    }

    @Test
    fun `removedCount counts stripped pairs`() {
        val query = "utm_source=a&keep=1&fbclid=b"
        assertEquals(2, TrackingParams.removedCount(query, "example.org"))
        assertEquals(0, TrackingParams.removedCount("keep=1", "example.org"))
    }

    @Test
    fun `user-defined global and host rules apply`() {
        val scope = CustomTrackingScope(global = setOf("mytag"), byHost = mapOf("shop.example" to setOf("aff*")))
        val url = UrlParser.parse("https://shop.example/p?mytag=1&affid=9&x=2")!!
        assertEquals(
            "https://shop.example/p?x=2",
            TrackingParams.stripFrom("https://shop.example/p?mytag=1&affid=9&x=2", url, scope),
        )
        val other = UrlParser.parse("https://other.example/p?affid=9")!!
        assertEquals(
            "https://other.example/p?affid=9",
            TrackingParams.stripFrom("https://other.example/p?affid=9", other, scope),
        )
    }
}

class UrlValidationTargetTest {
    @Test
    fun `public https targets are allowed`() {
        assertTrue(UrlValidation.isAllowedTargetUrl("https://example.org/page?x=1"))
        assertTrue(UrlValidation.isAllowedTargetUrl("http://example.org/"))
    }

    @Test
    fun `local and private targets are rejected`() {
        assertTrue(!UrlValidation.isAllowedTargetUrl("http://localhost/x"))
        assertTrue(!UrlValidation.isAllowedTargetUrl("http://127.0.0.1/x"))
        assertTrue(!UrlValidation.isAllowedTargetUrl("http://192.168.1.4/x"))
        assertTrue(!UrlValidation.isAllowedTargetUrl("http://10.0.0.1/x"))
        assertTrue(!UrlValidation.isAllowedTargetUrl("http://sub.localhost/x"))
    }

    @Test
    fun `non-http schemes are rejected`() {
        assertTrue(!UrlValidation.isAllowedTargetUrl("javascript:alert(1)"))
        assertTrue(!UrlValidation.isAllowedTargetUrl("file:///etc/passwd"))
    }
}
