package dev.libredirect.mobile.core.routing

import dev.libredirect.mobile.core.url.ParsedUrl

object ExceptionMatcher {
    fun matches(
        url: ParsedUrl,
        rules: List<ExceptionRule>,
    ): Boolean = rules.any { matchesRule(url, it) }

    private fun matchesRule(
        url: ParsedUrl,
        rule: ExceptionRule,
    ): Boolean =
        when (rule) {
            is ExceptionRule.Domain -> {
                val host = rule.host.lowercase()
                url.host == host || url.host.endsWith(".$host")
            }
            is ExceptionRule.UrlPrefix -> {
                "${url.host}${url.rawPath}".startsWith(rule.value, ignoreCase = true)
            }
        }
}
