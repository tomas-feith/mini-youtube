package com.miniyoutube.app.domain

import java.time.Instant

/** One `<entry>` of a channel's Atom feed, as YouTube publishes it. */
data class FeedEntry(
    val videoId: String,
    val channelId: String,
    val title: String,
    val publishedAt: Instant,
    /**
     * True for a Short. The feed marks one only through its link, which points at
     * `/shorts/<id>` rather than `/watch?v=<id>`; there is no separate flag.
     */
    val isShort: Boolean,
)

/** A parsed channel feed: the channel's current name and its most recent uploads. */
data class Feed(
    val channelTitle: String?,
    val entries: List<FeedEntry>,
)

/** What resolving a link or handle produces: enough to follow the channel. */
data class ChannelInfo(
    val id: String,
    val title: String?,
    val avatarUrl: String?,
)

/** The 16:9 thumbnail. `hqdefault` is 4:3 with letterboxing baked into the image. */
fun thumbnailUrl(videoId: String): String = "https://i.ytimg.com/vi/$videoId/mqdefault.jpg"

private val VIDEO_ID_FORMAT = Regex("[A-Za-z0-9_-]{11}")

/**
 * Whether [id] has the shape of a YouTube video id.
 *
 * Checked on anything that arrives from outside the app before it is used: the id becomes
 * part of a navigation route, where a `/` would crash the lookup, and is interpolated into
 * the player's JavaScript, where a quote would break out of the string.
 */
fun isVideoId(id: String): Boolean = VIDEO_ID_FORMAT.matches(id)

fun watchUrl(videoId: String): String = "https://www.youtube.com/watch?v=$videoId"

/**
 * What the user typed or shared, reduced to a way of finding the channel.
 *
 * Only [ById] is a channel id outright. The others need a request: a page path is fetched
 * and read for the id, and a video is looked up to find who uploaded it.
 */
sealed interface ChannelRef {
    data class ById(
        val channelId: String,
    ) : ChannelRef

    /** A path on youtube.com that renders the channel: `/@handle`, `/c/name`, `/user/name`. */
    data class ByPath(
        val path: String,
    ) : ChannelRef

    data class ByVideo(
        val videoId: String,
    ) : ChannelRef
}
