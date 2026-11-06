package dev.libredirect.mobile.core.routing

/**
 * Linear-time glob matching over `*` wildcards (2.9) — no regex, so no ReDoS
 * surface and no regex metacharacters in user input can change the meaning.
 *
 * The classic greedy two-pointer algorithm for patterns whose only wildcard
 * is `*`: literal parts must match in order; a `*` stands for any run of
 * characters, including an empty one. Worst case is O(n*m) with no
 * exponential backtracking, and it terminates on any input.
 */
object WildcardPattern {
    /** True when [value] matches [pattern], where every `*` matches any run of characters. */
    fun matches(
        pattern: String,
        value: String,
    ): Boolean {
        if (!pattern.contains('*')) return pattern.equals(value, ignoreCase = true)

        val p = pattern.lowercase()
        val v = value.lowercase()
        var pIndex = 0
        var vIndex = 0
        var starIndex = -1
        var matchedByStar = 0
        var failed = false

        while (vIndex < v.length && !failed) {
            when {
                pIndex < p.length && (p[pIndex] == '*' || p[pIndex] == v[vIndex]) -> {
                    if (p[pIndex] == '*') {
                        starIndex = pIndex
                        matchedByStar = vIndex
                        pIndex++
                    } else {
                        pIndex++
                        vIndex++
                    }
                }

                starIndex >= 0 -> {
                    // Retry the last '*' matching one more character.
                    pIndex = starIndex + 1
                    matchedByStar++
                    vIndex = matchedByStar
                }

                else -> failed = true
            }
        }
        return !failed && p.drop(pIndex).all { it == '*' }
    }
}
