package dev.libredirect.mobile.ui.browser

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class BrowserBookmark(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val url: String,
    val createdAtEpochMillis: Long = System.currentTimeMillis(),
)
