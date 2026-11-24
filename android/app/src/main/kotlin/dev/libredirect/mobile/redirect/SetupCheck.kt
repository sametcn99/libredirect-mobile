package dev.libredirect.mobile.redirect

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal object SetupCheck {
    const val SAMPLE_URL = "https://example.org/libredirect-setup"

    enum class Stage { IDLE, WAITING, RECEIVED, RESOLVED, CHOOSER, DELIVERED, FAILED }

    private val _stage = MutableStateFlow(Stage.IDLE)
    val stage = _stage.asStateFlow()

    fun start() {
        _stage.value = Stage.WAITING
    }

    fun record(
        url: String,
        stage: Stage,
    ) {
        if (url == SAMPLE_URL && _stage.value != Stage.IDLE) _stage.value = stage
    }
}
