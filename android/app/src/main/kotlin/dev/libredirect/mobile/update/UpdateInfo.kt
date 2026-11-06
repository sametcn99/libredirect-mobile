package dev.libredirect.mobile.update

data class UpdateInfo(
    val versionName: String,
    val releaseUrl: String,
    /** Human-readable "what's new" lines parsed from the GitHub release body (5.4). */
    val releaseNotes: List<String> = emptyList(),
)

/**
 * Extracts the displayable part of a GitHub release body: markdown bullets
 * and plain lines, capped in count and length, with markdown emphasis and
 * link syntax stripped so the in-app dialog shows clean text.
 */
object ReleaseNotes {
    private const val MAX_LINES = 12
    private const val MAX_LINE_LENGTH = 160

    fun extract(body: String?): List<String> {
        if (body.isNullOrBlank()) return emptyList()
        return body
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { line -> line.removePrefix("-").removePrefix("*").trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith("<") && !it.startsWith("!") }
            .map(::stripMarkdown)
            .take(MAX_LINES)
            .map { it.take(MAX_LINE_LENGTH) }
            .toList()
    }

    private fun stripMarkdown(line: String): String =
        line
            .replace(Regex("\\*\\*([^*]+)\\*\\*"), "$1")
            .replace(Regex("\\*([^*]+)\\*"), "$1")
            .replace(Regex("`([^`]+)`"), "$1")
            .replace(Regex("\\[([^\\]]+)]\\([^)]*\\)"), "$1")
}
