package dev.libredirect.mobile.ui.home

data class HomeActions(
    val onRoutingEnabledChange: (Boolean) -> Unit,
    val onBrowserClick: () -> Unit,
    val onServiceClick: (String) -> Unit,
    val onServiceEnabledChange: (String, Boolean) -> Unit,
    val onExceptionsClick: () -> Unit,
    val onRefreshClick: () -> Unit,
    val onCustomServicesClick: () -> Unit,
)
