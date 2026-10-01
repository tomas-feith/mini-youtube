package com.miniyoutube.app.ui.backlog

import android.content.Context
import android.net.ConnectivityManager
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.miniyoutube.app.AppContainer
import com.miniyoutube.app.data.FeedRefresher
import com.miniyoutube.app.data.Library
import com.miniyoutube.app.data.RefreshOutcome
import com.miniyoutube.app.data.VideoWithChannel
import com.miniyoutube.app.notify.cancelVideoNotification
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
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
    /** Whether Android currently lets this app use a network. */
    private val networkReady: () -> Boolean = { true },
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
                        // A cold start can run before Android lifts the app's network block -
                        // Battery Saver held it for up to ten seconds after launch - so wait
                        // for it, and try a round where nothing answered once more.
                        awaitNetwork()
                        refresher.refresh().let {
                            if (nothingAnswered(it)) {
                                delay(POLL_MS)
                                awaitNetwork()
                                refresher.refresh()
                            } else {
                                it
                            }
                        }
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
                // Not throttled after reaching nothing: the next return to the app should
                // try again, and it costs nothing while offline.
                if (!nothingAnswered(outcome)) lastRefreshAt = clock()
                failureMessage(outcome)?.let { _messages.send(BacklogMessage(it)) }
            } finally {
                _refreshing.value = false
            }
        }
    }

    /** Returns once there is a usable network, or after a while regardless. */
    private suspend fun awaitNetwork() {
        repeat(NETWORK_WAIT_POLLS) {
            if (networkReady()) return
            delay(POLL_MS)
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
        /**
         * What to say about a refresh that did not fully succeed, or null if it did.
         *
         * "Couldn't reach" is kept for when nothing answered at all; when YouTube answered
         * with errors, blaming the connection would send the user to check the wrong thing.
         */
        fun failureMessage(outcome: RefreshOutcome): String? =
            when {
                outcome.failed == 0 -> null
                outcome.checked > 0 -> "${outcome.failed} channel(s) couldn't be checked"
                outcome.unreachable == outcome.failed -> "Couldn't reach YouTube. Are you online?"
                else -> "YouTube isn't answering properly right now. Try again later."
            }

        private fun nothingAnswered(outcome: RefreshOutcome) =
            outcome.failed > 0 && outcome.unreachable == outcome.failed && outcome.checked == 0

        private const val TAG = "BacklogViewModel"

        private const val POLL_MS = 1_000L

        private const val NETWORK_WAIT_POLLS = 12

        private const val STOP_MS = 5_000L

        private val STALE_AFTER: Duration = Duration.ofMinutes(5)

        fun factory(container: AppContainer): ViewModelProvider.Factory =
            viewModelFactory {
                initializer {
                    val connectivity =
                        container.appContext.getSystemService(ConnectivityManager::class.java)
                    BacklogViewModel(
                        container.library,
                        container.refresher,
                        container.appContext,
                        // Null while there is no network - or while this app is blocked
                        // from the one there is, which is the case being waited out.
                        networkReady = { connectivity?.activeNetwork != null },
                    )
                }
            }
    }
}
