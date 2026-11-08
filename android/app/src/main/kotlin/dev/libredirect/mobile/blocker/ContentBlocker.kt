package dev.libredirect.mobile.blocker

/**
 * Host-based content/tracker blocking (4.2). The blocklist is plain data:
 * one hostname per line, optionally in hosts-file form ("0.0.0.0 host",
 * comments). No JS, no element-hiding rules, no regex — a hostname set with
 * O(1) lookups plus a suffix rule (blocking a domain blocks its subdomains).
 * The list ships embedded in the APK as a fallback and can be updated
 * remotely through the same fetch-validate-activate pipeline as the routing
 * manifest.
 */
object ContentBlocker {
    const val MAX_RECORDS = 200_000
    const val MAX_BYTES = 8L * 1024 * 1024

    private val HOSTNAME =
        Regex("^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)+$")

    private const val INITIAL_HOST_CAPACITY = 4096

    /**
     * Parses blocklist text into a hostname set. Accepts bare hostnames and
     * hosts-file lines (`0.0.0.0 host`, `127.0.0.1 host`, `::1 host`);
     * comments, blanks and malformed lines are dropped. Returns at most
     * [maxRecords] entries — a truncated-but-valid list beats no list.
     */
    fun parseHosts(
        raw: String,
        maxRecords: Int = MAX_RECORDS,
    ): Set<String> {
        val hosts = HashSet<String>(minOf(maxRecords, INITIAL_HOST_CAPACITY))
        raw.lineSequence()
            .mapNotNull(::extractHost)
            .take(maxRecords)
            .toCollection(hosts)
        return hosts
    }

    // Guard chain: blank/comment → hosts-file form → comment suffix → validity.
    @Suppress("ReturnCount")
    private fun extractHost(line: String): String? {
        var candidate = line.trim()
        if (candidate.isEmpty() || candidate.startsWith("#")) return null
        // Hosts-file form: "<ip> <host>" (optionally followed by a comment).
        if (candidate.any { it.isWhitespace() }) {
            candidate = candidate.substringBefore('#').trim()
            val columns = candidate.split(Regex("\\s+"))
            if (columns.size != 2 || !isIpLiteral(columns[0])) return null
            candidate = columns[1]
        }
        val commentStart = candidate.indexOf('#')
        if (commentStart >= 0) candidate = candidate.take(commentStart).trim()
        val host = candidate.lowercase()
        return if (HOSTNAME.matches(host)) host else null
    }

    private fun isIpLiteral(value: String): Boolean =
        value.all { it.isDigit() || it == '.' } && value.contains('.') ||
            value == "::1"

    /**
     * O(1)-ish lookup: exact host match, or a registered parent domain
     * (`doubleclick.net` blocks `ad.doubleclick.net`). The lookup itself is
     * a small bounded loop over the host's labels, all touching the set.
     */
    fun shouldBlock(
        host: String,
        blocked: Set<String>,
    ): Boolean =
        // Walk the host's parent labels against the set; single exit.
        generateSequence(host.lowercase().trimEnd('.')) { previous ->
            previous.substringAfter('.', "")
        }.takeWhile(String::isNotEmpty).any { candidate -> candidate in blocked }
}
