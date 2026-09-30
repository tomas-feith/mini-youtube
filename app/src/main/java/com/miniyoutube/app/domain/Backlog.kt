package com.miniyoutube.app.domain

import java.time.Duration
import java.time.Instant

/**
 * Which feed entries become backlog items.
 *
 * Three filters, each guarding against a different way the backlog could fill with things
 * the user never asked for:
 *
 * - **Shorts** are excluded outright; the app exists partly to not show them.
 * - **Anything published before the channel was followed** is excluded. Following means
 *   "tell me about new uploads", so the feed's back catalogue - up to fifteen videos - must
 *   not arrive as a wall of backlog the first time the channel is checked.
 * - **Anything already known** is excluded, watched or not. A watched video leaves the
 *   backlog but its row stays, and this is what stops the next check re-adding it while it
 *   is still in the feed.
 */
fun newArrivals(
    entries: List<FeedEntry>,
    followedAt: Instant,
    known: Set<String>,
): List<FeedEntry> =
    entries
        .asSequence()
        .filterNot { it.isShort }
        .filterNot { it.publishedAt.isBefore(followedAt) }
        .filterNot { it.videoId in known }
        .distinctBy { it.videoId }
        .toList()

private const val SECONDS_PER_MINUTE = 60L
private const val MINUTES_PER_HOUR = 60L
private const val HOURS_PER_DAY = 24L
private const val DAYS_PER_WEEK = 7L
private const val DAYS_PER_MONTH = 30L
private const val DAYS_PER_YEAR = 365L

/**
 * "5 min ago", "3 h ago", "2 d ago" - how long a video has been waiting.
 *
 * A time slightly in the future, which a phone clock running behind YouTube's produces,
 * reads as "just now" rather than as a negative age.
 */
fun relativeAge(
    then: Instant,
    now: Instant,
): String {
    val seconds = Duration.between(then, now).seconds.coerceAtLeast(0)
    val minutes = seconds / SECONDS_PER_MINUTE
    val hours = minutes / MINUTES_PER_HOUR
    val days = hours / HOURS_PER_DAY
    return when {
        minutes < 1 -> "just now"
        hours < 1 -> "$minutes min ago"
        days < 1 -> "$hours h ago"
        days < DAYS_PER_WEEK -> "$days d ago"
        days < DAYS_PER_MONTH -> "${days / DAYS_PER_WEEK} wk ago"
        days < DAYS_PER_YEAR -> "${days / DAYS_PER_MONTH} mo ago"
        else -> "${days / DAYS_PER_YEAR} y ago"
    }
}
