package com.miniyoutube.app.ui.channels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.miniyoutube.app.AppContainer
import com.miniyoutube.app.data.ChannelEntity
import com.miniyoutube.app.data.FollowResult
import com.miniyoutube.app.data.Follower
import com.miniyoutube.app.data.Library
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class FollowState(
    val input: String = "",
    val busy: Boolean = false,
    /** The outcome of the last attempt, shown under the field. */
    val message: String? = null,
    val isError: Boolean = false,
)

class ChannelsViewModel(
    private val library: Library,
    private val follower: Follower,
    checkOnMobileData: Flow<Boolean>,
    private val saveCheckOnMobileData: (Boolean) -> Unit,
) : ViewModel() {
    /** Null until read, so the switch does not flash off and on. */
    val checkOnMobileData: StateFlow<Boolean?> =
        checkOnMobileData.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_MS), null)

    fun setCheckOnMobileData(allowed: Boolean) = saveCheckOnMobileData(allowed)

    val channels: StateFlow<List<ChannelEntity>?> =
        library.channels.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_MS), null)

    private val _follow = MutableStateFlow(FollowState())
    val follow: StateFlow<FollowState> = _follow.asStateFlow()

    fun onInputChange(text: String) {
        _follow.update { it.copy(input = text, message = null) }
    }

    /** Follows whatever [input] names; the field shows it so a shared link is visible. */
    fun follow(input: String = _follow.value.input) {
        if (_follow.value.busy || input.isBlank()) return
        _follow.update { FollowState(input = input, busy = true) }
        viewModelScope.launch {
            val next =
                when (val result = follower.follow(input)) {
                    is FollowResult.Followed -> {
                        FollowState(
                            message = "Following ${result.title}. New uploads will show up here.",
                        )
                    }

                    is FollowResult.AlreadyFollowing -> {
                        FollowState(message = "Already following ${result.title}")
                    }

                    is FollowResult.Failed -> {
                        FollowState(input = input, message = result.message, isError = true)
                    }
                }
            _follow.value = next
        }
    }

    fun unfollow(channelId: String) {
        viewModelScope.launch { library.unfollow(channelId) }
    }

    companion object {
        private const val STOP_MS = 5_000L

        fun factory(container: AppContainer): ViewModelProvider.Factory =
            viewModelFactory {
                initializer {
                    ChannelsViewModel(
                        container.library,
                        container.follower,
                        container.settings.checkOnMobileData,
                        container::setCheckOnMobileData,
                    )
                }
            }
    }
}
