package dev.libredirect.mobile.core.url

/**
 * Extracts the first plausible URL from free text (a shared message, the
 * clipboard). Pure Kotlin — no android.util.Patterns — so the extraction
 * rules are unit-testable on the JVM from both the share-target path and
 * the clipboard offer.
 *
 * A candidate needs a dotted host containing at least one letter (which
 * filters out times, versions and plain numbers). A missing scheme is
 * normalized to `https://`, so "youtube.com/watch?v=x" survives routing's
 * scheme allowlist instead of being dropped as malformed.
 */
object UrlCandidate {
    private const val DOMAIN = """[a-zA-Z0-9][a-zA-Z0-9-]*(?:\.[a-zA-Z0-9][a-zA-Z0-9-]*)+"""
    private val CANDIDATE =
        Regex(
            """(?<![@a-zA-Z0-9_./-])(?:https?://)?$DOMAIN(?::\d{1,5})?(?:[/?#][^\s"'<>]*)?""",
        )

    data class Match(val range: IntRange, val url: String)

    fun find(text: String?): String? = text?.let { matches(it).firstOrNull()?.url }

    fun matches(text: String): Sequence<Match> =
        CANDIDATE.findAll(text).mapNotNull { match ->
            val candidate = withoutTrailingPunctuation(match.value)
            if (!looksLikeHost(candidate)) return@mapNotNull null
            Match(match.range.first until match.range.first + candidate.length, withHttpsScheme(candidate))
        }

    private fun withoutTrailingPunctuation(value: String): String {
        var candidate = value.trimEnd('.', ',', ';', ':', '!', '?', '"', '\'')
        val brackets = mapOf(')' to '(', ']' to '[', '}' to '{')
        while (candidate.isNotEmpty()) {
            val last = candidate.last()
            val open = brackets[last]
            if (open == null || candidate.count { it == last } <= candidate.count { it == open }) break
            candidate = candidate.dropLast(1).trimEnd('.', ',', ';', ':', '!', '?')
        }
        return candidate
    }

    private fun looksLikeHost(candidate: String): Boolean {
        val host =
            candidate.substringAfter(
                "://",
                candidate,
            ).substringBefore('/').substringBefore('?').substringBefore('#').substringBefore(':')
        return host.contains('.') && host.any(Char::isLetter)
    }

    private fun withHttpsScheme(candidate: String): String =
        when {
            candidate.startsWith("https://", ignoreCase = true) -> candidate
            candidate.startsWith("http://", ignoreCase = true) -> candidate
            else -> "https://$candidate"
        }
}
