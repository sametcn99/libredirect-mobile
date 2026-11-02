package dev.libredirect.mobile.ui.browser

/**
 * Color scheme web pages are rendered with inside the in-app browser. It
 * drives the `prefers-color-scheme` media query sites see, independently of
 * the app chrome, which keeps following the system theme.
 */
enum class PageTheme(val id: String) {
    SYSTEM("system"),
    LIGHT("light"),
    DARK("dark"),
    ;

    fun isDark(systemInDarkTheme: Boolean): Boolean =
        when (this) {
            SYSTEM -> systemInDarkTheme
            LIGHT -> false
            DARK -> true
        }

    companion object {
        val DEFAULT = SYSTEM

        fun fromId(id: String?): PageTheme = entries.find { it.id.equals(id, ignoreCase = true) } ?: DEFAULT
    }
}
