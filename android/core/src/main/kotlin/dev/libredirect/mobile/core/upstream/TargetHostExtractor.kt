package dev.libredirect.mobile.core.upstream

/**
 * Kotlin port of the upstream-config adapter (formerly
 * `tools/src/adapters/libredirect-config.ts`): libredirect/browser_extension's
 * `src/config.json` stores its `targets` as full JS regex strings (including
 * negative lookaheads and wildcard-subdomain character classes) because the
 * browser extension intercepts every network request. This app only routes
 * top-level link clicks/shares, and the manifest schema deliberately has no
 * arbitrary regex — so these helpers recognize a conservative, hand-verified
 * subset of that regex grammar (optional/alternation prefix groups,
 * alternation in the base domain, escaped dots) and return `null` for
 * anything outside it, rather than guess. Verified empirically against every
 * target actually published in config.json as of this writing: 77 of 102
 * targets extract cleanly; the 25 that don't are all genuine
 * wildcard-subdomain or lookahead patterns with no finite host-list
 * equivalent (they surface as "unsupported target" generation notes).
 */
internal object TargetHostExtractor {
    private val TARGET_PREFIXES = listOf("^https?:\\/{2}", "^https?:\\/\\/", "^https:\\/{2}", "^https:\\/\\/")
    private val HOSTNAME_REGEX = Regex("^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)+$")
    private val HOSTNAME_SAFE_OPTION_REGEX = Regex("^[a-z0-9\\\\.-]*$", RegexOption.IGNORE_CASE)
    private val RESERVED_TLDS = setOf("onion", "i2p", "loki", "invalid", "test", "example", "localhost")
    private val ONION_V3_LABEL = Regex("^[a-z2-7]{56}$")

    /** Length of the `(?=` / `(?!` lookahead tokens. */
    private const val LOOKAHEAD_TOKEN_LENGTH = 3

    private sealed interface Token {
        data class Literal(val value: String) : Token

        data class Group(val options: List<String>) : Token
    }

    /**
     * Returns the finite set of hostnames a `targets` regex pattern matches,
     * or `null` if the pattern needs real regex expressiveness (wildcard
     * subdomains, lookaheads, quantified TLD suffixes, ...) this schema
     * doesn't support.
     */
    // A deliberate 1:1 port of the proven upstream adapter parser: the
    // control flow mirrors it exactly, so restructuring would trade reviewed
    // fidelity for style points. The suppressions stay scoped to this
    // function.
    @Suppress("LongMethod", "CyclomaticComplexMethod", "NestedBlockDepth", "ReturnCount", "LoopWithTooManyJumpStatements")
    fun extractHosts(pattern: String): List<String>? {
        var rest = stripTargetPrefix(pattern) ?: return null

        val tokens = mutableListOf<Token>()
        var literalBuf = StringBuilder()
        var i = 0
        while (i < rest.length) {
            val c = rest[i]
            when {
                c == '(' -> {
                    val close = rest.indexOf(')', i)
                    if (close == -1) break // unbalanced -> treat what we have so far as the whole host
                    val inner = rest.substring(i + 1, close)
                    // A lookahead or non-capturing group is never part of the host.
                    if (inner.startsWith("?")) break

                    val options = inner.split("|")
                    if (options.any { !HOSTNAME_SAFE_OPTION_REGEX.matches(it) }) {
                        // A genuine trailing boundary group like (\/|$) is safe to stop at.
                        // A group trying to EXTEND the domain (e.g. a quantified TLD suffix)
                        // must not be silently truncated into a shorter, wrong host.
                        val looksLikeDomainContinuation = options.any { it.startsWith(".") || it.startsWith("\\.") }
                        if (looksLikeDomainContinuation) return null
                        break
                    }

                    val optional = close + 1 < rest.length && rest[close + 1] == '?'
                    tokens += Token.Literal(literalBuf.toString())
                    literalBuf = StringBuilder()
                    val unescaped = options.map { it.replace(Regex("\\\\(.)"), "$1") }
                    tokens += Token.Group(if (optional) unescaped + "" else unescaped)
                    i = close + if (optional) 2 else 1
                }

                c == '\\' -> {
                    if (i + 1 >= rest.length) break
                    val next = rest[i + 1]
                    // Escaped boundary chars are not part of the host.
                    if (next == '/' || next == '$') break
                    literalBuf.append(next)
                    i += 2
                }

                c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '.' || c == '-' -> {
                    literalBuf.append(c)
                    i += 1
                }

                else -> break // any other character (/, $, ?, *, [, ...) ends the host expression
            }
        }
        if (literalBuf.isNotEmpty()) tokens += Token.Literal(literalBuf.toString())

        if (tokens.isEmpty()) return null

        var hosts = listOf("")
        for (token in tokens) {
            hosts =
                when (token) {
                    is Token.Literal -> hosts.map { it + token.value }
                    is Token.Group ->
                        hosts.flatMap { host -> token.options.map { option -> host + option } }
                }
        }

        val result = LinkedHashSet<String>()
        for (host in hosts) {
            val lower = host.lowercase()
            // One unresolvable host invalidates the whole pattern: a finite
            // host list must be exactly right, not partially right.
            if (!HOSTNAME_REGEX.matches(lower)) return null
            val labels = lower.split(".")
            val tld = labels.last()
            if (tld in RESERVED_TLDS) continue // non-clearnet network
            if (labels.any { ONION_V3_LABEL.matches(it) }) continue // onion hash mixed into a clearnet TLD
            result += lower
        }
        return if (result.isEmpty()) null else result.toList()
    }

    /**
     * Converts the hostname portion of an upstream target regex into an
     * anchored hostname-only pattern. This intentionally discards path
     * predicates: the Android router resolves links by host, while preserving
     * the hostname regex is enough to cover wildcard subdomains and locale
     * TLDs without embedding arbitrary URL regexes in the manifest.
     */
    // Ported 1:1 alongside [extractHosts]; see the note there.
    @Suppress("ReturnCount")
    fun extractHostPattern(pattern: String): String? {
        var rest = stripTargetPrefix(pattern) ?: return null

        // A lookahead at the very beginning is part of the hostname expression
        // (for example stackexchange's API exclusion); a later lookahead is a
        // path predicate and must be removed.
        findLookaheadAfterIndex(rest, minimumIndex = 2)?.let { index ->
            rest = rest.substring(0, index)
        }

        val pathGroup = rest.findPathBoundary()
        if (pathGroup >= 0) rest = rest.substring(0, pathGroup)
        val escapedSlash = rest.indexOf("\\/")
        if (escapedSlash >= 0) rest = rest.substring(0, escapedSlash)
        rest = rest.removeSuffix("$").trim()
        // Normalize unbounded wildcard labels to a bounded hostname label before
        // they are stored in the manifest. This keeps matching predictable and
        // avoids handing a backtracking-heavy `.*` expression to the client.
        rest = rest.replace(".*", "[a-zA-Z0-9-]+")

        val nonClearnetHost = Regex("invalid|localhost|\\.onion|\\.i2p|\\.loki", RegexOption.IGNORE_CASE)
        if (rest.isEmpty() || nonClearnetHost.containsMatchIn(rest)) {
            return null
        }
        val result = "^$rest$"
        return try {
            // Validate the syntax now so malformed upstream patterns are
            // reported and never reach the manifest or the Android runtime.
            Regex(result)
            result
        } catch (_: Exception) {
            null
        }
    }

    private fun stripTargetPrefix(pattern: String): String? {
        for (prefix in TARGET_PREFIXES) {
            if (pattern.startsWith(prefix)) return pattern.substring(prefix.length)
        }
        return null
    }

    /** First `(?=` / `(?!` occurrence strictly after [minimumIndex], if any. */
    private fun findLookaheadAfterIndex(
        rest: String,
        minimumIndex: Int,
    ): Int? {
        for (index in minimumIndex..rest.length - LOOKAHEAD_TOKEN_LENGTH) {
            if (rest.startsWith("(?=", index) || rest.startsWith("(?!", index)) return index
        }
        return null
    }

    /** First "(" followed by "\/", "/" or "$" — the start of a path/boundary group. */
    private fun String.findPathBoundary(): Int {
        for (index in 0..length - 2) {
            if (this[index] == '(' && startsPathBoundaryAt(index)) return index
        }
        return -1
    }

    private fun String.startsPathBoundaryAt(openParenIndex: Int): Boolean {
        val next = this[openParenIndex + 1]
        return next == '/' ||
            next == '$' ||
            (openParenIndex + 2 < length && next == '\\' && this[openParenIndex + 2] == '/')
    }
}
