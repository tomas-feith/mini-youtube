package com.miniyoutube.app.domain

import java.net.URI
import java.net.URISyntaxException

private val CHANNEL_ID = Regex("UC[A-Za-z0-9_-]{22}")
private val VIDEO_ID = Regex("[A-Za-z0-9_-]{11}")

/** A bare handle, with or without its `@`. YouTube allows letters, digits, `_`, `-`, `.`. */
private val HANDLE = Regex("@?([\\p{L}\\p{N}._-]{3,30})")

private val YOUTUBE_HOSTS =
    setOf("youtube.com", "www.youtube.com", "m.youtube.com", "music.youtube.com")

private val VIDEO_PATH_PREFIXES = listOf("shorts", "live", "embed", "v")

/**
 * Reads a channel link, video link, `@handle` or channel id out of free text.
 *
 * Free text because Android's share sheet hands over whatever the sharing app composed -
 * the YouTube app sends a bare URL today, but other apps prefix a title. The first thing
 * that looks like a URL wins; failing that, the whole input is read as an id or a handle.
 *
 * Returns null for anything that cannot name a channel, so the screen can say so before
 * making a request.
 */
fun parseChannelInput(input: String): ChannelRef? {
    val text = input.trim()
    val url =
        text.split(Regex("\\s+")).firstOrNull {
            // A handle may itself contain "youtu" and a dot - "@youtube.fan" - so an `@`
            // at the start rules a token out as a URL.
            !it.startsWith("@") && it.contains("youtu") && it.contains('.')
        }
    return when {
        text.isEmpty() -> null
        url != null -> parseUrl(url)
        CHANNEL_ID.matches(text) -> ChannelRef.ById(text)
        else -> HANDLE.matchEntire(text)?.let { ChannelRef.ByPath("/@${it.groupValues[1]}") }
    }
}

private fun parseUrl(raw: String): ChannelRef? {
    val withScheme = if (raw.contains("://")) raw else "https://$raw"
    val uri =
        try {
            URI(withScheme)
        } catch (
            @Suppress("SwallowedException") e: URISyntaxException,
        ) {
            null
        }
    val host = uri?.host?.lowercase()
    // rawPath keeps a non-ASCII handle percent-encoded, which is what the request needs.
    val segments =
        uri
            ?.rawPath
            .orEmpty()
            .split('/')
            .filter { it.isNotEmpty() }
    return when {
        uri == null || host == null -> {
            null
        }

        host == "youtu.be" -> {
            segments.firstOrNull()?.takeIf(VIDEO_ID::matches)?.let(ChannelRef::ByVideo)
        }

        host in YOUTUBE_HOSTS -> {
            parseYouTubePath(uri, segments)
        }

        else -> {
            null
        }
    }
}

private fun parseYouTubePath(
    uri: URI,
    segments: List<String>,
): ChannelRef? {
    val first = segments.firstOrNull() ?: return null
    val second = segments.getOrNull(1)
    return when {
        first == "watch" -> {
            queryParam(uri, "v")?.takeIf(VIDEO_ID::matches)?.let(ChannelRef::ByVideo)
        }

        first in VIDEO_PATH_PREFIXES -> {
            second?.takeIf(VIDEO_ID::matches)?.let(ChannelRef::ByVideo)
        }

        first == "channel" -> {
            second?.takeIf(CHANNEL_ID::matches)?.let(ChannelRef::ById)
        }

        first.startsWith("@") && first.length > 1 -> {
            ChannelRef.ByPath("/$first")
        }

        (first == "c" || first == "user") && second != null -> {
            ChannelRef.ByPath("/$first/$second")
        }

        else -> {
            null
        }
    }
}

private fun queryParam(
    uri: URI,
    name: String,
): String? =
    uri.rawQuery
        ?.split('&')
        ?.map { it.split('=', limit = 2) }
        ?.firstOrNull { it.size == 2 && it[0] == name }
        ?.get(1)
