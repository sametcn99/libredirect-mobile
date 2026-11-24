package dev.libredirect.mobile.ui.browser

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class BrowserBookmark(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val url: String,
    val createdAtEpochMillis: Long = System.currentTimeMillis(),
    val canonicalUrl: String? = null,
) {
    val openUrl: String
        get() = canonicalUrl ?: url

    fun matches(value: String): Boolean = value == url || value == canonicalUrl
}
