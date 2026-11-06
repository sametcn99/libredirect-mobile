package dev.libredirect.mobile.core.routing

import dev.libredirect.mobile.core.url.ParsedUrl

object ExceptionMatcher {
    fun matches(
        url: ParsedUrl,
        rules: List<ExceptionRule>,
        sourceApp: String? = null,
    ): Boolean = rules.any { matchesRule(url, it, sourceApp) }

    private fun matchesRule(
        url: ParsedUrl,
        rule: ExceptionRule,
        sourceApp: String?,
    ): Boolean =
        when (rule) {
            is ExceptionRule.Domain ->
                if (rule.host.contains('*')) {
                    WildcardPattern.matches(rule.host, url.host)
                } else {
                    val host = rule.host.lowercase()
                    url.host == host || url.host.endsWith(".$host")
                }
            is ExceptionRule.UrlPrefix -> {
                val target = "${url.host}${url.rawPath}"
                if (rule.value.contains('*')) {
                    WildcardPattern.matches(rule.value, target)
                } else {
                    target.startsWith(rule.value, ignoreCase = true)
                }
            }
            is ExceptionRule.SourceApp ->
                sourceApp != null && sourceApp.equals(rule.packageName, ignoreCase = true)
        }
}
