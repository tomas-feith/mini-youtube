package com.miniyoutube.app.ui.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.miniyoutube.app.AppContainer
import com.miniyoutube.app.data.Library
import com.miniyoutube.app.data.VideoWithChannel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.math.abs

sealed interface PlayerState {
    data object Loading : PlayerState

    /**
     * [video] is null for an id the library does not hold - a notification for a channel
     * since unfollowed. It still plays; the id is all the player needs.
     */
    data class Ready(
        val video: VideoWithChannel?,
    ) : PlayerState
}

class PlayerViewModel(
    private val library: Library,
    val videoId: String,
) : ViewModel() {
    val state: StateFlow<PlayerState> =
        library
            .video(videoId)
            .map<VideoWithChannel?, PlayerState> { PlayerState.Ready(it) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_MS), PlayerState.Loading)

    private var lastSavedSecond = -1

    /**
     * Records where playback is, every [SAVE_EVERY_SECONDS] of movement.
     *
     * Periodic rather than on leaving the screen, because leaving is exactly when the
     * ViewModel's scope is being cancelled and a final write can be lost. The cost is up to
     * ten seconds of rewatching, which is also roughly what YouTube itself does.
     */
    fun onProgress(second: Float) {
        val now = second.toInt()
        if (lastSavedSecond >= 0 && abs(now - lastSavedSecond) < SAVE_EVERY_SECONDS) return
        lastSavedSecond = now
        viewModelScope.launch { library.saveResumePoint(videoId, now) }
    }

    /** At the end there is nothing to resume, so the next open starts from the top. */
    fun onEnded() {
        lastSavedSecond = 0
        viewModelScope.launch { library.saveResumePoint(videoId, 0) }
    }

    fun markUnwatched() {
        viewModelScope.launch { library.markUnwatched(videoId) }
    }

    companion object {
        private const val STOP_MS = 5_000L
        private const val SAVE_EVERY_SECONDS = 10

        fun factory(
            container: AppContainer,
            videoId: String,
        ): ViewModelProvider.Factory =
            viewModelFactory {
                initializer { PlayerViewModel(container.library, videoId) }
            }
    }
}
