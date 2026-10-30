package dev.libredirect.mobile.manifest

sealed interface RefreshResult {
    data class Activated(val revision: Int) : RefreshResult

    /** Fetched successfully, but its revision was <= the currently active one. */
    data object NotModified : RefreshResult

    data class Rejected(val reason: RefreshRejectionReason) : RefreshResult
}

enum class RefreshRejectionReason {
    FETCH_FAILED,
    MALFORMED_MANIFEST,
    UNSUPPORTED_SCHEMA_VERSION,
    SELF_TEST_FAILED,
}
