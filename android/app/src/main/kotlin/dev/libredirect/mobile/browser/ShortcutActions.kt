package dev.libredirect.mobile.browser

/**
 * Intent actions fired by the app's launcher shortcuts (res/xml/shortcuts.xml)
 * and home-screen service shortcuts, handled in [dev.libredirect.mobile.MainActivity].
 */
object ShortcutActions {
    const val OPEN_NEW_TAB = "dev.libredirect.mobile.ACTION_OPEN_NEW_TAB"
    const val SHOW_SERVICES = "dev.libredirect.mobile.ACTION_SHOW_SERVICES"
    const val OPEN_SERVICE = "dev.libredirect.mobile.ACTION_OPEN_SERVICE"
    const val EXTRA_ROUTE_ID = "dev.libredirect.mobile.EXTRA_ROUTE_ID"
}
