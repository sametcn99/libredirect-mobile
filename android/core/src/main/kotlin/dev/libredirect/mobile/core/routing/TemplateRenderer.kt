package dev.libredirect.mobile.core.routing

import dev.libredirect.mobile.core.url.ParsedUrl
import dev.libredirect.mobile.core.url.QueryString

/**
 * Implements the placeholder grammar specified in the Phase 1 manifest-spec
 * plan artifact: {instance}, {path}, {query:NAME}, {fragment}. This is the
 * single source of truth both this Kotlin implementation and
 * tools/validate-routes.ts's grammar check must agree on independently —
 * there is no shared code between them.
 *
 * {path} and {fragment} are substituted verbatim (a URL path/fragment can't
 * contain an unencoded '?' or '#', so they're safe by construction).
 * {query:NAME} carries attacker-influenced data into a newly built query
 * string, so it is decoded then re-encoded fresh rather than trusted as-is.
 */
object TemplateRenderer {
    private val PLACEHOLDER = Regex("\\{(instance|path|fragment|query:[A-Za-z0-9_]+)\\}")

    fun render(
        template: String,
        url: ParsedUrl,
        instance: String,
    ): String =
        PLACEHOLDER.replace(template) { match ->
            val token = match.groupValues[1]
            when {
                token == "instance" -> instance
                token == "path" -> url.rawPath
                token == "fragment" -> url.rawFragment.orEmpty()
                token.startsWith("query:") -> {
                    val name = token.removePrefix("query:")
                    QueryString.find(url.rawQuery, name)?.let { QueryString.encode(it) }.orEmpty()
                }
                else -> match.value
            }
        }
}
