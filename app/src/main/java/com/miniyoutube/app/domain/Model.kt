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
    /**
     * The view count the feed reports, or null when it gives none. Zero is the cheap hint
     * that an entry may be a scheduled premiere or stream that has not started - those sit
     * in the feed from the moment they are scheduled - and is what decides whether the
     * video's page is worth fetching to find out.
     */
    val views: Long? = null,
)

/** A parsed channel feed: the channel's current name and its most recent uploads. */
data class Feed(
    val channelTitle: String?,
    val entries: List<FeedEntry>,
    /**
     * When YouTube served the feed, by YouTube's own clock (the response's `Date` header),
     * or null if it sent none. Following uses this rather than the phone's clock, because
     * it is compared against publish times that YouTube stamped.
     */
    val fetchedAt: Instant? = null,
)

/** What a video's watch page says about it. */
data class WatchInfo(
    val videoId: String,
    val channelId: String?,
    val title: String?,
    val publishedAt: Instant?,
    /** A premiere or stream that is scheduled and has not started yet. */
    val upcoming: Boolean,
    /** When a scheduled premiere or stream starts, or started. */
    val startsAt: Instant?,
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
