package com.miniyoutube.app.domain

/**
 * The feed link every channel page carries in its `<head>`. It is the one place the page
 * states the channel id in a stable, documented form - the JSON blobs further down are
 * YouTube's internal state and change shape without notice.
 */
private val FEED_LINK = Regex("feeds/videos\\.xml\\?channel_id=(UC[A-Za-z0-9_-]{22})")

/** Fallback for a page whose head has changed: the canonical URL of a `/channel/` page. */
private val CANONICAL =
    Regex(
        "<link rel=\"canonical\" " +
            "href=\"https://www\\.youtube\\.com/channel/(UC[A-Za-z0-9_-]{22})\"",
    )

private val OG_TITLE = Regex("<meta property=\"og:title\" content=\"([^\"]*)\"")
private val OG_IMAGE = Regex("<meta property=\"og:image\" content=\"([^\"]*)\"")

private const val HEX_RADIX = 16

private val AUTHOR_URL = Regex("\"author_url\"\\s*:\\s*\"([^\"]+)\"")

/** Reads the channel's id, name and avatar out of its HTML page, or null if it has none. */
fun parseChannelPage(html: String): ChannelInfo? {
    val id =
        FEED_LINK.find(html)?.groupValues?.get(1)
            ?: CANONICAL.find(html)?.groupValues?.get(1)
            ?: return null
    val title =
        OG_TITLE
            .find(html)
            ?.groupValues
            ?.get(1)
            ?.let(::unescapeHtml)
            ?.takeIf { it.isNotBlank() }
    val avatar =
        OG_IMAGE
            .find(html)
            ?.groupValues
            ?.get(1)
            ?.let(::unescapeHtml)
    return ChannelInfo(id, title, avatar)
}

/**
 * The uploader's page path from an oEmbed response, e.g. `/@mkbhd`.
 *
 * A regex rather than a JSON parser for one string field: `org.json` is a stub off-device,
 * and a serialization library for a single lookup is more machinery than it saves.
 */
fun parseOEmbedAuthorPath(json: String): String? {
    val url =
        AUTHOR_URL
            .find(json)
            ?.groupValues
            ?.get(1)
            ?.replace("\\/", "/") ?: return null
    val path = url.substringAfter("youtube.com", missingDelimiterValue = "")
    return path.takeIf { it.startsWith("/") && it.length > 1 }
}

/** Decodes the handful of entities YouTube writes into attribute values. */
fun unescapeHtml(text: String): String =
    Regex("&(#x[0-9a-fA-F]+|#[0-9]+|amp|lt|gt|quot|apos);").replace(text) { match ->
        val entity = match.groupValues[1]
        when {
            entity == "amp" -> "&"
            entity == "lt" -> "<"
            entity == "gt" -> ">"
            entity == "quot" -> "\""
            entity == "apos" -> "'"
            entity.startsWith("#x") -> codePoint(entity.drop(2).toIntOrNull(HEX_RADIX), match.value)
            else -> codePoint(entity.drop(1).toIntOrNull(), match.value)
        }
    }

private fun codePoint(
    value: Int?,
    original: String,
): String =
    if (value != null && Character.isValidCodePoint(value)) {
        String(Character.toChars(value))
    } else {
        original
    }
