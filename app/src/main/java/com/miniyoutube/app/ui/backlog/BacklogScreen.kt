package com.miniyoutube.app.ui.backlog

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.Subscriptions
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.miniyoutube.app.data.VideoWithChannel
import com.miniyoutube.app.domain.relativeAge
import com.miniyoutube.app.notify.canPostNotifications
import com.miniyoutube.app.ui.components.VideoThumbnail
import kotlinx.coroutines.delay
import java.time.Instant

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BacklogScreen(
    viewModel: BacklogViewModel,
    onOpenVideo: (String) -> Unit,
    onOpenChannels: () -> Unit,
) {
    val backlog by viewModel.backlog.collectAsStateWithLifecycle()
    val hasChannels by viewModel.hasChannels.collectAsStateWithLifecycle()
    val refreshing by viewModel.refreshing.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    LifecycleEventEffect(Lifecycle.Event.ON_START) { viewModel.refreshIfStale() }

    LaunchedEffect(viewModel) {
        viewModel.messages.collect { message ->
            val result =
                snackbar.showSnackbar(
                    message = message.text,
                    actionLabel = message.undoWatchedId?.let { "Undo" },
                    duration = SnackbarDuration.Short,
                )
            if (result == SnackbarResult.ActionPerformed) {
                message.undoWatchedId?.let(viewModel::undoWatched)
            }
        }
    }

    // Ages are relative to now, so they are re-read every minute rather than frozen at
    // whatever time the screen happened to be composed.
    val now by produceState(Instant.now()) {
        while (true) {
            delay(60_000)
            value = Instant.now()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    val count = backlog?.size ?: 0
                    Text(if (count == 0) "Backlog" else "Backlog ($count)")
                },
                actions = {
                    IconButton(onClick = onOpenChannels) {
                        Icon(Icons.Outlined.Subscriptions, contentDescription = "Channels")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = viewModel::refresh,
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            val videos = backlog
            Column(Modifier.fillMaxSize()) {
                NotificationBanner(
                    alreadyAsked = viewModel.askedForNotifications,
                    onAsked = { viewModel.askedForNotifications = true },
                )
                when {
                    videos == null || hasChannels == null -> {}

                    hasChannels == false -> {
                        EmptyState(
                            text = "Follow a channel and its new videos will collect here.",
                            action = "Add a channel",
                            onAction = onOpenChannels,
                        )
                    }

                    videos.isEmpty() -> {
                        EmptyState(text = "All caught up.")
                    }

                    else -> {
                        LazyColumn(
                            contentPadding = PaddingValues(vertical = 8.dp),
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            items(videos, key = { it.id }) { video ->
                                VideoRow(
                                    video = video,
                                    now = now,
                                    onOpen = { onOpenVideo(video.id) },
                                    onWatched = { viewModel.markWatched(video.id) },
                                    modifier = Modifier.animateItem(),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun VideoRow(
    video: VideoWithChannel,
    now: Instant,
    onOpen: () -> Unit,
    onWatched: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            modifier
                .fillMaxWidth()
                .clickable(onClick = onOpen)
                .padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
    ) {
        VideoThumbnail(video.id, Modifier.width(144.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                video.title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "${video.channelTitle} · ${relativeAge(
                    Instant.ofEpochMilli(video.publishedAt),
                    now,
                )}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onWatched) {
            Icon(
                Icons.Outlined.CheckCircle,
                contentDescription = "Mark as watched",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Scrollable so the pull-to-refresh gesture still works on an empty backlog - the box only
 * sees a pull that a scrollable child passes up to it.
 */
@Composable
private fun EmptyState(
    text: String,
    action: String? = null,
    onAction: () -> Unit = {},
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(32.dp),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.padding(top = 120.dp),
        ) {
            Text(
                text,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            if (action != null) Button(onClick = onAction) { Text(action) }
        }
    }
}

/**
 * Asks for the notification permission once, and says so when it is missing.
 *
 * The banner rather than repeated prompts: Android stops showing the system dialog after
 * two refusals, so from then on the settings page is the only way back, and the banner is
 * what points there. Re-checked on every resume, since that is where the user returns from
 * the settings page.
 */
@Composable
private fun NotificationBanner(
    alreadyAsked: Boolean,
    onAsked: () -> Unit,
) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(canPostNotifications(context)) }
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            granted = it
        }

    LaunchedEffect(Unit) {
        if (!granted && !alreadyAsked && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            onAsked()
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { granted = canPostNotifications(context) }

    if (granted) return
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        shape = MaterialTheme.shapes.medium,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
        ) {
            Icon(Icons.Outlined.NotificationsOff, contentDescription = null)
            Spacer(Modifier.width(12.dp))
            Text(
                "Notifications are off",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = {
                    context.startActivity(
                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
                    )
                },
            ) { Text("Turn on") }
        }
    }
}
