package com.miniyoutube.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.miniyoutube.app.domain.isVideoId
import com.miniyoutube.app.ui.LaunchRequest
import com.miniyoutube.app.ui.MiniYouTubeNavHost
import com.miniyoutube.app.ui.theme.MiniYouTubeTheme
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {
    /**
     * Where an intent asked the app to go: a video from a notification, or a link shared
     * from another app. Consumed by the nav host, which clears it once acted on.
     */
    private val launchRequest = MutableStateFlow<LaunchRequest?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // Only on a fresh start: after a configuration change or process restore the
        // intent is the one already acted on, and replaying it would re-open the video
        // or re-follow the channel.
        if (savedInstanceState == null) launchRequest.value = requestFrom(intent)

        val container = (application as MiniYouTubeApplication).container
        setContent {
            MiniYouTubeTheme {
                MiniYouTubeNavHost(
                    container = container,
                    launchRequest = launchRequest,
                    onLaunchHandled = { launchRequest.value = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        requestFrom(intent)?.let { launchRequest.value = it }
    }

    private fun requestFrom(intent: Intent?): LaunchRequest? {
        if (intent == null) return null
        // The activity is exported, so any app can send this extra; only a well-formed id is
        // allowed anywhere near the player.
        intent.getStringExtra(EXTRA_VIDEO_ID)?.takeIf(::isVideoId)?.let {
            return LaunchRequest.OpenVideo(it)
        }
        if (intent.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            intent.getStringExtra(Intent.EXTRA_TEXT)?.let { return LaunchRequest.Follow(it) }
        }
        return null
    }

    companion object {
        const val EXTRA_VIDEO_ID = "com.miniyoutube.app.VIDEO_ID"
    }
}
