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
    private val CANDIDATE =
        Regex("""(?:https?://)?[a-zA-Z0-9][a-zA-Z0-9-]*(?:\.[a-zA-Z0-9][a-zA-Z0-9-]*)+(?::\d{1,5})?(?:/[^\s"'<>]*)?""")

    fun find(text: String?): String? {
        if (text.isNullOrBlank()) return null
        return CANDIDATE
            .findAll(text)
            .map { it.value }
            .map(::withoutTrailingPunctuation)
            .filter(::looksLikeHost)
            .map(::withHttpsScheme)
            .firstOrNull()
    }

    /** Prose often glues sentence punctuation onto the end of a URL. */
    private fun withoutTrailingPunctuation(candidate: String): String =
        candidate.trimEnd('.', ',', ';', ':', '!', '?', ')', ']', '}', '"', '\'')

    private fun looksLikeHost(candidate: String): Boolean {
        val host = candidate.substringAfter("://", candidate).substringBefore('/').substringBefore(':')
        return host.contains('.') && host.any(Char::isLetter)
    }

    private fun withHttpsScheme(candidate: String): String =
        when {
            candidate.startsWith("https://", ignoreCase = true) -> candidate
            candidate.startsWith("http://", ignoreCase = true) -> candidate
            else -> "https://$candidate"
        }
}
