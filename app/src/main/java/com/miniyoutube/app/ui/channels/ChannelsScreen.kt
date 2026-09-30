package com.miniyoutube.app.ui.channels

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.miniyoutube.app.data.ChannelEntity
import com.miniyoutube.app.domain.relativeAge
import java.time.Instant

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChannelsScreen(
    viewModel: ChannelsViewModel,
    onBack: () -> Unit,
) {
    val channels by viewModel.channels.collectAsStateWithLifecycle()
    val follow by viewModel.follow.collectAsStateWithLifecycle()
    var confirming by remember { mutableStateOf<ChannelEntity?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Channels") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(bottom = 24.dp),
            modifier = Modifier.fillMaxSize().padding(padding).imePadding(),
        ) {
            item { FollowField(follow, viewModel::onInputChange, { viewModel.follow() }) }
            item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
            val list = channels.orEmpty()
            if (channels != null && list.isEmpty()) {
                item {
                    Text(
                        "No channels yet. Paste a link above, or open a channel or video in " +
                            "the YouTube app and share it to Mini YouTube.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
            items(list, key = { it.id }) { channel ->
                ChannelRow(channel, onUnfollow = { confirming = channel })
            }
        }
    }

    confirming?.let { channel ->
        AlertDialog(
            onDismissRequest = { confirming = null },
            title = { Text("Unfollow ${channel.title}?") },
            text = { Text("Its videos will be removed from your backlog.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.unfollow(channel.id)
                        confirming = null
                    },
                ) { Text("Unfollow") }
            },
            dismissButton = { TextButton(onClick = { confirming = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun FollowField(
    state: FollowState,
    onChange: (String) -> Unit,
    onSubmit: () -> Unit,
) {
    Column(Modifier.padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = state.input,
                onValueChange = onChange,
                label = { Text("Channel link or @handle") },
                singleLine = true,
                enabled = !state.busy,
                keyboardOptions =
                    KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onSubmit() }),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Button(onClick = onSubmit, enabled = !state.busy && state.input.isNotBlank()) {
                if (state.busy) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Text("Follow")
                }
            }
        }
        state.message?.let {
            Spacer(Modifier.height(8.dp))
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color =
                    if (state.isError) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
            )
        }
    }
}

@Composable
private fun ChannelRow(
    channel: ChannelEntity,
    onUnfollow: () -> Unit,
) {
    // Deliberately not a link to the channel on YouTube: its page is Shorts and
    // recommendations, which is what this app exists to keep out of the way.
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier.fillMaxWidth().padding(
                start = 16.dp,
                end = 4.dp,
                top = 8.dp,
                bottom = 8.dp,
            ),
    ) {
        AsyncImage(
            model = channel.avatarUrl,
            contentDescription = null,
            modifier =
                Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
        )
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(channel.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val checked =
                channel.lastCheckedAt?.let {
                    "Checked ${relativeAge(Instant.ofEpochMilli(it), Instant.now())}"
                } ?: "Not checked yet"
            Text(
                checked,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onUnfollow) {
            Icon(Icons.Outlined.Delete, contentDescription = "Unfollow ${channel.title}")
        }
    }
}
