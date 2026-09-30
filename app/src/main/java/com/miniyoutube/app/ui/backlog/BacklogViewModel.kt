package com.miniyoutube.app.ui.backlog

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.miniyoutube.app.AppContainer
import com.miniyoutube.app.data.FeedRefresher
import com.miniyoutube.app.data.Library
import com.miniyoutube.app.data.VideoWithChannel
import com.miniyoutube.app.notify.cancelVideoNotification
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant

/** A one-off message for the snackbar, with an undo when there is something to undo. */
data class BacklogMessage(
    val text: String,
    val undoWatchedId: String? = null,
)

class BacklogViewModel(
    private val library: Library,
    private val refresher: FeedRefresher,
    private val appContext: Context,
    private val clock: () -> Instant = Instant::now,
) : ViewModel() {
    /** Null until the first read lands, so the screen can tell "loading" from "empty". */
    val backlog: StateFlow<List<VideoWithChannel>?> =
        library.backlog.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_MS), null)

    val hasChannels: StateFlow<Boolean?> =
        library.channels
            .map { it.isNotEmpty() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_MS), null)

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    private val _messages = Channel<BacklogMessage>(Channel.BUFFERED)
    val messages: Flow<BacklogMessage> = _messages.receiveAsFlow()

    private var lastRefreshAt: Instant? = null

    /**
     * Whether this process has already shown the notification prompt. Held here, in the
     * activity-scoped ViewModel, because the backlog screen leaves composition every time
     * another screen opens, and a flag remembered in the composable would re-prompt on
     * every return to it.
     */
    var askedForNotifications = false

    /**
     * Checks the feeds when the app comes to the foreground, unless it did so recently.
     *
     * The throttle is what makes checking on every return to the app affordable: switching
     * away and back should not re-read every channel.
     */
    fun refreshIfStale() {
        val last = lastRefreshAt
        if (last != null && Duration.between(last, clock()) < STALE_AFTER) return
        refresh()
    }

    fun refresh() {
        if (_refreshing.value) return
        _refreshing.value = true
        viewModelScope.launch {
            try {
                val outcome =
                    try {
                        refresher.refresh()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (
                        @Suppress("TooGenericExceptionCaught") e: Exception,
                    ) {
                        // Per-channel failures are absorbed inside the refresher; reaching
                        // here means the database itself failed. Say so rather than crash.
                        Log.w(TAG, "Refresh failed", e)
                        _messages.send(BacklogMessage("Couldn't check for new videos"))
                        return@launch
                    }
                lastRefreshAt = clock()
                if (outcome.checked == 0 && outcome.failed > 0) {
                    _messages.send(BacklogMessage("Couldn't reach YouTube"))
                } else if (outcome.failed > 0) {
                    _messages.send(
                        BacklogMessage("${outcome.failed} channel(s) couldn't be checked"),
                    )
                }
            } finally {
                _refreshing.value = false
            }
        }
    }

    fun markWatched(videoId: String) {
        viewModelScope.launch {
            library.markWatched(videoId)
            cancelVideoNotification(appContext, videoId)
            _messages.send(BacklogMessage("Marked as watched", undoWatchedId = videoId))
        }
    }

    fun undoWatched(videoId: String) {
        viewModelScope.launch { library.markUnwatched(videoId) }
    }

    companion object {
        private const val TAG = "BacklogViewModel"

        private const val STOP_MS = 5_000L

        private val STALE_AFTER: Duration = Duration.ofMinutes(5)

        fun factory(container: AppContainer): ViewModelProvider.Factory =
            viewModelFactory {
                initializer {
                    BacklogViewModel(container.library, container.refresher, container.appContext)
                }
            }
    }
}
