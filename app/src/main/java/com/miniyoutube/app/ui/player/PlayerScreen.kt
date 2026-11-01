package com.miniyoutube.app.ui.player

import android.content.Intent
import android.content.pm.ActivityInfo
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.miniyoutube.app.domain.relativeAge
import com.miniyoutube.app.domain.watchUrl
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.PlayerConstants
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.YouTubePlayer
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.listeners.AbstractYouTubePlayerListener
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.listeners.FullscreenListener
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.options.IFramePlayerOptions
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.views.YouTubePlayerView
import java.time.Instant

/** What the player hands over on entering fullscreen: its view, and how to leave. */
private class Fullscreen(
    val view: View,
    val exit: () -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(
    viewModel: PlayerViewModel,
    onBack: () -> Unit,
    onMarkWatched: (String) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var fullscreen by remember { mutableStateOf<Fullscreen?>(null) }
    var error by remember { mutableStateOf<PlayerConstants.PlayerError?>(null) }
    val context = LocalContext.current
    val openInYouTube = {
        context.startActivity(Intent(Intent.ACTION_VIEW, watchUrl(viewModel.videoId).toUri()))
    }

    FullscreenWindow(active = fullscreen != null)
    BackHandler(enabled = fullscreen != null) { fullscreen?.exit?.invoke() }

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {},
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                )
            },
        ) { padding ->
            val ready = state as? PlayerState.Ready ?: return@Scaffold
            val video = ready.video
            // Read once per video: the resume point is saved as playback moves, and the
            // player must not be sent back to it on every save.
            val startAt = remember(viewModel.videoId) { video?.resumeAtSeconds ?: 0 }

            Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
                Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color.Black)) {
                    YouTubePlayerBox(
                        videoId = viewModel.videoId,
                        startAtSeconds = startAt,
                        onProgress = viewModel::onProgress,
                        onEnded = viewModel::onEnded,
                        onError = { error = it },
                        onFullscreen = { fullscreen = it },
                    )
                }

                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(16.dp),
                ) {
                    error?.let {
                        Text(errorText(it), color = MaterialTheme.colorScheme.error)
                    }
                    if (video != null) {
                        Text(video.title, style = MaterialTheme.typography.titleMedium)
                        Text(
                            "${video.channelTitle} · " +
                                relativeAge(Instant.ofEpochMilli(video.publishedAt), Instant.now()),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    // Flow rather than Row: both buttons side by side overflow a 360dp phone.
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        when {
                            video == null -> {}

                            video.watchedAt == null -> {
                                Button(onClick = { onMarkWatched(video.id) }) {
                                    Icon(Icons.Outlined.Check, contentDescription = null)
                                    Spacer(Modifier.width(8.dp))
                                    Text("Mark as watched")
                                }
                            }

                            else -> {
                                OutlinedButton(onClick = viewModel::markUnwatched) {
                                    Icon(
                                        Icons.AutoMirrored.Outlined.Undo,
                                        contentDescription = null,
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text("Back to backlog")
                                }
                            }
                        }
                        OutlinedButton(onClick = openInYouTube) {
                            Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("YouTube")
                        }
                    }
                }
            }
        }

        fullscreen?.let { FullscreenHost(it.view) }
    }
}

/**
 * YouTube's IFrame player, in the WebView the library manages.
 *
 * `rel = 0` limits the end screen to the same channel's videos - the embed no longer
 * allows switching it off entirely - and `ivLoadPolicy = 3` hides annotations. The
 * library pauses on the lifecycle's stop and releases on destroy; `onRelease` covers
 * leaving this screen, which destroys the view without the activity going anywhere.
 */
@Composable
private fun YouTubePlayerBox(
    videoId: String,
    startAtSeconds: Int,
    onProgress: (Float) -> Unit,
    onEnded: () -> Unit,
    onError: (PlayerConstants.PlayerError) -> Unit,
    onFullscreen: (Fullscreen?) -> Unit,
) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    // The view outlives any one composition, so its listeners read the latest callbacks
    // through these rather than capturing the first ones forever.
    val progress by rememberUpdatedState(onProgress)
    val ended by rememberUpdatedState(onEnded)
    val failed by rememberUpdatedState(onError)
    val fullscreen by rememberUpdatedState(onFullscreen)

    AndroidView(
        factory = { context ->
            YouTubePlayerView(context).apply {
                enableAutomaticInitialization = false
                lifecycle.addObserver(this)
                addFullscreenListener(
                    object : FullscreenListener {
                        override fun onEnterFullscreen(
                            fullscreenView: View,
                            exitFullscreen: () -> Unit,
                        ) = fullscreen(Fullscreen(fullscreenView, exitFullscreen))

                        override fun onExitFullscreen() = fullscreen(null)
                    },
                )
                val playerView = this
                val options =
                    IFramePlayerOptions
                        .Builder(context)
                        .controls(1)
                        .rel(0)
                        .ivLoadPolicy(3)
                        .fullscreen(1)
                        .build()
                initialize(
                    object : AbstractYouTubePlayerListener() {
                        override fun onReady(youTubePlayer: YouTubePlayer) {
                            youTubePlayer.loadVideo(videoId, startAtSeconds.toFloat())
                        }

                        override fun onCurrentSecond(
                            youTubePlayer: YouTubePlayer,
                            second: Float,
                        ) = progress(second)

                        override fun onStateChange(
                            youTubePlayer: YouTubePlayer,
                            state: PlayerConstants.PlayerState,
                        ) {
                            // Keep the screen awake while a video plays, and only then.
                            playerView.keepScreenOn = state == PlayerConstants.PlayerState.PLAYING
                            if (state == PlayerConstants.PlayerState.ENDED) ended()
                        }

                        override fun onError(
                            youTubePlayer: YouTubePlayer,
                            error: PlayerConstants.PlayerError,
                        ) = failed(error)
                    },
                    options,
                )
            }
        },
        onRelease = { view ->
            // The view releases itself on the lifecycle's ON_DESTROY, and release() is not
            // idempotent - it unregisters a network receiver, which throws the second time.
            // So release here only when leaving the screen got in first.
            val alreadyReleased = lifecycle.currentState == Lifecycle.State.DESTROYED
            lifecycle.removeObserver(view)
            if (!alreadyReleased) view.release()
        },
        modifier = Modifier.fillMaxSize(),
    )
}

/** Hosts the view the player hands over for fullscreen, above everything else. */
@Composable
private fun FullscreenHost(view: View) {
    AndroidView(
        factory = { FrameLayout(it) },
        update = { container ->
            if (view.parent !== container) {
                (view.parent as? ViewGroup)?.removeView(view)
                container.removeAllViews()
                container.addView(
                    view,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    ),
                )
            }
        },
        modifier = Modifier.fillMaxSize().background(Color.Black),
    )
}

/**
 * Landscape and no system bars while fullscreen; both restored on leaving it, and on
 * leaving the screen while still in it.
 */
@Composable
private fun FullscreenWindow(active: Boolean) {
    val activity = LocalActivity.current ?: return
    DisposableEffect(active) {
        val window = activity.window
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        if (active) {
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        }
        onDispose {
            if (active) {
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                controller.show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }
}

private fun errorText(error: PlayerConstants.PlayerError): String =
    when (error) {
        PlayerConstants.PlayerError.VIDEO_NOT_PLAYABLE_IN_EMBEDDED_PLAYER -> {
            "The uploader doesn't allow this video to play outside YouTube. Open it there instead."
        }

        PlayerConstants.PlayerError.VIDEO_NOT_FOUND -> {
            "This video is unavailable - it may have been removed or made private."
        }

        PlayerConstants.PlayerError.REQUEST_MISSING_HTTP_REFERER -> {
            "YouTube refused the embedded player. Open the video in YouTube instead."
        }

        else -> {
            "The video couldn't be played here. Open it in YouTube instead."
        }
    }
