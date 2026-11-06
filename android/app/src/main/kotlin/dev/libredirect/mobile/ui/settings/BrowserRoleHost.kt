package dev.libredirect.mobile.ui.settings

/**
 * Lets the Settings screen ask the host [dev.libredirect.mobile.MainActivity]
 * to launch the system "set as default browser" role dialog (C1). Implemented
 * by the activity because a role request must be started with an activity
 * result launcher that survives configuration changes.
 */
interface BrowserRoleHost {
    fun requestBrowserRole()
}
