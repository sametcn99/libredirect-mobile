package dev.libredirect.mobile.media

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Process-wide channel between the WebView's media state bridge (injected
 * JS reporting play/pause), the browser ViewModel (which can run JS against
 * the page) and [MediaPlaybackService]'s notification actions. A plain
 * singleton because the service and the activity-scoped ViewModel have no
 * shared scope; state is a StateFlow so the notification can rebuild
 * itself at any time.
 */
object MediaPlaybackBus {
    enum class Command { PLAY_PAUSE, STOP }

    data class MediaState(
        val isPlaying: Boolean = false,
        /** True while the user paused via the notification (controls the injected resume). */
        val pausedByUser: Boolean = false,
        val pageTitle: String = "",
        val host: String = "",
    )

    private val _state = MutableStateFlow(MediaState())
    val state: StateFlow<MediaState> = _state.asStateFlow()

    private val _commands = MutableSharedFlow<Command>(extraBufferCapacity = 8)
    val commands: SharedFlow<Command> = _commands.asSharedFlow()

    fun reportPlaying(playing: Boolean) {
        _state.update { current ->
            current.copy(
                isPlaying = playing,
                pausedByUser = if (playing) false else current.pausedByUser,
            )
        }
    }

    fun setMeta(
        pageTitle: String,
        host: String,
    ) {
        _state.update { it.copy(pageTitle = pageTitle, host = host) }
    }

    fun send(command: Command) {
        _commands.tryEmit(command)
    }

    fun reset() {
        _state.value = MediaState()
    }
}
