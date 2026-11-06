package dev.libredirect.mobile.redirect

import dev.libredirect.mobile.ui.browser.SearchEngine

/**
 * ACTION_PROCESS_TEXT input decision (C2), extracted so it is unit-testable:
 * a selection that already looks like a web URL is opened as-is (and routed
 * by the normal pipeline); anything else becomes a search in the given
 * engine instead of failing as an unopenable string.
 */
object ProcessTextResolver {
    fun resolve(
        text: String,
        engine: SearchEngine,
    ): String =
        if (text.startsWith("http://", ignoreCase = true) ||
            text.startsWith("https://", ignoreCase = true)
        ) {
            text
        } else {
            engine.buildSearchUrl(text)
        }
}
