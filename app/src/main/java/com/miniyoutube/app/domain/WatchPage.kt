package com.miniyoutube.app.domain

private val META_TITLE = Regex("<meta name=\"title\" content=\"([^\"]*)\"")
private val CHANNEL_ID_FIELD = Regex("\"channelId\":\"(UC[A-Za-z0-9_-]{22})\"")
private val PUBLISH_DATE = Regex("\"publishDate\":\"([^\"]+)\"")
private val START_TIMESTAMP = Regex("\"startTimestamp\":\"([^\"]+)\"")
private val CONTENT_ID = Regex("\"contentId\":\"([A-Za-z0-9_-]{11})\"")

/**
 * Reads what the feed cannot say about a video out of its watch page: whether it is a
 * premiere or stream that has not started, when it starts, and its exact publish time.
 *
 * The page embeds YouTube's player state as JSON. Only two objects in it are read, and
 * each is cut out by brace matching before anything is searched for, because the rest of
 * the page carries the same field names for *other* videos - the sidebar's suggestions
 * can themselves be upcoming streams, and a page-wide search would pick those up:
 *
 * - `videoDetails`, for the channel and `isUpcoming`;
 * - `microformat`, for `publishDate` and a stream's `startTimestamp`.
 *
 * Returns null when the page does not describe [videoId] at all - a consent page, an
 * error page, or a layout this no longer understands.
 */
fun parseWatchPage(
    videoId: String,
    html: String,
): WatchInfo? {
    val details = jsonObjectAfter(html, "\"videoDetails\":{\"videoId\":\"$videoId\"") ?: return null
    val microformat = jsonObjectAfter(html, "\"microformat\":{").orEmpty()
    return WatchInfo(
        videoId = videoId,
        channelId = CHANNEL_ID_FIELD.find(details)?.groupValues?.get(1),
        title =
            META_TITLE
                .find(html)
                ?.groupValues
                ?.get(1)
                ?.let(::unescapeHtml),
        publishedAt =
            PUBLISH_DATE
                .find(microformat)
                ?.groupValues
                ?.get(1)
                ?.let(::parseInstant),
        upcoming = details.contains("\"isUpcoming\":true"),
        startsAt =
            START_TIMESTAMP
                .find(microformat)
                ?.groupValues
                ?.get(1)
                ?.let(::parseInstant),
    )
}

/**
 * The ids on a channel's `/videos` tab, newest first, without repeats.
 *
 * That tab lists long-form uploads only - Shorts and streams have tabs of their own - so
 * nothing here needs filtering for Shorts. It shows the latest thirty, twice what the feed
 * holds, which is what makes it useful for filling a gap the feed has rolled past.
 */
fun parseChannelVideoIds(html: String): List<String> =
    CONTENT_ID
        .findAll(html)
        .map { it.groupValues[1] }
        .distinct()
        .toList()

/**
 * The ids on the channel tab [tab] ("videos", "streams"), or none if YouTube served
 * another tab in its place.
 *
 * A channel without streams answers `/streams` with its Home or Videos tab - a mix of
 * uploads, Shorts shelves and other channels' videos, under a 200. The selected tab's
 * own link names what was really served, in any interface language.
 */
fun parseChannelTabIds(
    html: String,
    tab: String,
): List<String> = if (selectedChannelTab(html) == tab) parseChannelVideoIds(html) else emptyList()

/** The last path segment of the selected tab's link: "videos", "streams", "featured". */
internal fun selectedChannelTab(html: String): String? =
    SELECTED
        .findAll(html)
        .firstNotNullOfOrNull { selected ->
            val start = html.lastIndexOf(TAB_RENDERER, selected.range.first)
            if (start < 0 ||
                selected.range.first - start > MAX_TAB_HEADER
            ) {
                return@firstNotNullOfOrNull null
            }
            TAB_URL
                .find(html.substring(start, selected.range.first))
                ?.groupValues
                ?.get(1)
                ?.substringAfterLast('/')
        }

private val SELECTED = Regex(""""selected":true""")
private const val TAB_RENDERER = "\"tabRenderer\":{"
private val TAB_URL = Regex(""""url":"([^"]+)"""")

/** A tab's endpoint and title come before `selected`; much further is something else. */
private const val MAX_TAB_HEADER = 2_000

/**
 * The JSON object that begins with [prefix], which must end in its opening `{`, cut out
 * by matching braces. Strings are skipped over, escapes included, so a brace inside a
 * title cannot end the object early.
 */
private fun jsonObjectAfter(
    text: String,
    prefix: String,
): String? {
    val start = text.indexOf(prefix)
    if (start < 0) return null
    val open = text.indexOf('{', start)
    var depth = 0
    var inString = false
    var escaped = false
    for (i in open until text.length) {
        val c = text[i]
        when {
            escaped -> escaped = false
            inString && c == '\\' -> escaped = true
            c == '"' -> inString = !inString
            inString -> Unit
            c == '{' -> depth++
            c == '}' -> if (--depth == 0) return text.substring(open, i + 1)
        }
    }
    return null
}
