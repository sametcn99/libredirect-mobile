package dev.libredirect.mobile.core.url

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Property-style fuzzing over the security-critical URL input points (§6):
 * whatever the input shape, parsing, unwrapping and tracking cleanup must
 * terminate quickly and never throw — failures are expressed as null or
 * unchanged output, never as crashes.
 */
class UrlFuzzTest {
    private val seeds =
        listOf(
            "https://example.org/path?q=1#f",
            "http://localhost/x?y=z",
            "javascript:alert(1)",
            "https://google.com/url?q=",
            "https://google.com/url?q=%",
            "https://l.facebook.com/l.php?u=%E0%A4%A",
            "https://www-example.cdn.ampproject.org/c/s/",
            "https://www-example.cdn.ampproject.org/c/%",
            "https://x.com/a?s=20&t=%",
            "https://amazon.de/?ref_=%",
            "file:///etc/passwd?%",
            "https://[::1]/?utm_%",
            "https://example.org/%E0%A4%A?ref_%=",
            "https://out.reddit.com/out?url=%",
            "?utm_source=%",
            "https://example.org?" + "a=%".repeat(64),
        )

    private val alphabets = listOf("abcdefghijklmnopqrstuvwxyz", "%&#=?/[]:@+ ", "0123456789", "\\\"'<>")

    private fun mutate(
        random: Random,
        seed: String,
    ): String {
        var value = seed
        repeat(random.nextInt(1, 6)) {
            val alphabet = alphabets[random.nextInt(alphabets.size)]
            val position = random.nextInt(value.length + 1)
            val insert = alphabet[random.nextInt(alphabet.length)].toString()
            value = value.substring(0, position) + insert + value.substring(position.coerceAtMost(value.length))
        }
        return value
    }

    @Test
    fun `parser never throws on mutated input`() {
        val random = Random(1234)
        repeat(2000) {
            val input = mutate(random, seeds[random.nextInt(seeds.size)])
            try {
                UrlParser.parse(input)
                UrlParser.parseScheme(input)
                UrlUnwrapper.unwrapAll(input)
                TrackingParams.cleanQuery("a=%", "example.org")
            } catch (error: Exception) {
                throw AssertionError("Threw on: '$input'", error)
            }
        }
    }

    @Test
    fun `unwrap output is always a valid http url or the input`() {
        val random = Random(99)
        repeat(2000) {
            val input = mutate(random, seeds[random.nextInt(seeds.size)])
            val result = UrlUnwrapper.unwrapAll(input)
            if (result.url != input) {
                assertTrue(
                    "unwrapped to '$result.url' from '$input'",
                    UrlValidation.isAllowedTargetUrl(result.url),
                )
            }
        }
    }

    @Test
    fun `wildcard matching terminates on mutated inputs`() {
        val random = Random(5)
        val targets = listOf("www.example.com/watch/live", "a".repeat(300))
        repeat(1000) {
            val pattern = mutate(random, "*.example.com/*/live")
            val value = targets[random.nextInt(targets.size)]
            try {
                dev.libredirect.mobile.core.routing.WildcardPattern.matches(pattern, value)
            } catch (error: Exception) {
                throw AssertionError("Threw on pattern '$pattern'", error)
            }
        }
    }
}
