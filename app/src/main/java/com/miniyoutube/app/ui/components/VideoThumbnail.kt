package com.miniyoutube.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.miniyoutube.app.domain.thumbnailUrl

@Composable
fun VideoThumbnail(
    videoId: String,
    modifier: Modifier = Modifier,
) {
    AsyncImage(
        model = thumbnailUrl(videoId),
        // The title sits right beside it, so describing the image would read it twice.
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier =
            modifier
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
    )
}
