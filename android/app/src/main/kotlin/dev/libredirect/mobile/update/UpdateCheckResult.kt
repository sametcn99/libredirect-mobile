package dev.libredirect.mobile.update

/** Outcome of a single GitHub Releases update check. */
sealed interface UpdateCheckResult {
    /** A newer release than the installed one is published. */
    data class Available(val info: UpdateInfo) : UpdateCheckResult

    /** The check succeeded and no newer release exists. */
    data object UpToDate : UpdateCheckResult

    /** The check could not complete (offline, GitHub unreachable, bad response). */
    data object Failed : UpdateCheckResult
}
