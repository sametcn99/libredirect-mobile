package dev.libredirect.mobile.ui.browser

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class BrowserHistoryItem(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val url: String,
    val visitedAtEpochMillis: Long = System.currentTimeMillis(),
)
