package dev.libredirect.mobile.core.routing

import dev.libredirect.mobile.core.url.UrlParser
import org.junit.Assert.assertEquals
import org.junit.Test

class TemplateRendererTest {
    @Test
    fun `substitutes instance path query and fragment`() {
        val url = UrlParser.parse("https://reddit.com/r/kotlin?sort=top#comments")!!
        val output =
            TemplateRenderer.render(
                "{instance}{path}?sort={query:sort}#{fragment}",
                url,
                "https://redlib.example.org",
            )
        assertEquals("https://redlib.example.org/r/kotlin?sort=top#comments", output)
    }

    @Test
    fun `query value is percent-decoded then re-encoded, not passed through raw`() {
        val url = UrlParser.parse("https://reddit.com/search?q=a%26b")!!
        val output = TemplateRenderer.render("{instance}/search?q={query:q}", url, "https://redlib.example.org")
        // "a&b" round-tripped through decode -> encode must not become a literal '&'
        // that would split the query string into two parameters.
        assertEquals("https://redlib.example.org/search?q=a%26b", output)
    }

    @Test
    fun `unknown placeholder is left untouched, not silently dropped`() {
        val url = UrlParser.parse("https://reddit.com/")!!
        val output = TemplateRenderer.render("{instance}/{unknown}", url, "https://redlib.example.org")
        assertEquals("https://redlib.example.org/{unknown}", output)
    }

    @Test
    fun `literal text around placeholders is preserved`() {
        val url = UrlParser.parse("https://reddit.com/")!!
        val output = TemplateRenderer.render("prefix-{instance}-suffix", url, "https://redlib.example.org")
        assertEquals("prefix-https://redlib.example.org-suffix", output)
    }
}
