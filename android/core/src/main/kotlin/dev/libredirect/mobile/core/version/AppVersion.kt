package dev.libredirect.mobile.core.version

/**
 * Compares "MAJOR.MINOR.PATCH"-style version strings numerically (never
 * lexically - "0.10.0" must sort after "0.9.0", not before it). A leading
 * "v"/"V" is stripped, and any pre-release/build metadata suffix
 * ("-beta.1", "+build5") is ignored for comparison purposes.
 */
object AppVersion {
    fun isNewer(
        candidate: String,
        current: String,
    ): Boolean {
        val candidateParts = parse(candidate)
        val currentParts = parse(current)
        return candidateParts != null && currentParts != null && compare(candidateParts, currentParts) > 0
    }

    private fun parse(raw: String): List<Int>? {
        val cleaned =
            raw.trim()
                .removePrefix("v")
                .removePrefix("V")
                .substringBefore('-')
                .substringBefore('+')
        val parsed = cleaned.takeIf(String::isNotEmpty)?.split('.')?.map { it.toIntOrNull() }
        return parsed?.takeIf { segments -> segments.all { it != null } }?.mapNotNull { it }
    }

    private fun compare(
        a: List<Int>,
        b: List<Int>,
    ): Int {
        val length = maxOf(a.size, b.size)
        for (i in 0 until length) {
            val diff = a.getOrElse(i) { 0 }.compareTo(b.getOrElse(i) { 0 })
            if (diff != 0) return diff
        }
        return 0
    }
}
