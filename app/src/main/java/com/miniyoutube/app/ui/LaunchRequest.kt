package com.miniyoutube.app.ui

/** Something an intent asked for, handled once the nav graph exists. */
sealed interface LaunchRequest {
    data class OpenVideo(
        val videoId: String,
    ) : LaunchRequest

    data class Follow(
        val sharedText: String,
    ) : LaunchRequest
}
