package com.miniyoutube.app.domain

import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.StringReader
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.parsers.ParserConfigurationException

private const val ATOM_NS = "http://www.w3.org/2005/Atom"
private const val YT_NS = "http://www.youtube.com/xml/schemas/2015"

/**
 * Parses the Atom feed at `/feeds/videos.xml?channel_id=...`.
 *
 * `javax.xml` rather than Android's `XmlPullParser`, because it exists on both the device
 * and the JVM the unit tests run on; the pull parser is a stub off-device.
 *
 * An entry missing its id or date is skipped rather than failing the feed: one malformed
 * upload should not hide the rest of a channel.
 *
 * @throws org.xml.sax.SAXException when the body is not XML at all, which is what YouTube
 *   serves as an HTML error page. The caller treats that as a failed fetch.
 */
fun parseFeed(xml: String): Feed {
    val factory =
        DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isExpandEntityReferences = false
        }
    try {
        // A feed has no business declaring a DTD, so refuse one outright: that closes off
        // entity expansion attacks on the JVM. Android's parser never resolves external
        // entities and rejects this feature name, which is the exception swallowed here.
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
    } catch (
        @Suppress("SwallowedException") e: ParserConfigurationException,
    ) {
        // Unsupported on Android; nothing to do.
    }
    val doc = factory.newDocumentBuilder().parse(InputSource(StringReader(xml)))
    val root = doc.documentElement

    val channelTitle =
        root
            .childElements(ATOM_NS, "title")
            .firstOrNull()
            ?.textContent
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    val entries = root.childElements(ATOM_NS, "entry").mapNotNull(::parseEntry)
    return Feed(channelTitle, entries)
}

private fun parseEntry(entry: Element): FeedEntry? {
    val videoId = entry.childText(YT_NS, "videoId") ?: return null
    val channelId = entry.childText(YT_NS, "channelId") ?: return null
    val published = entry.childText(ATOM_NS, "published")?.let(::parseInstant) ?: return null
    val title = entry.childText(ATOM_NS, "title").orEmpty()
    val link =
        entry
            .childElements(ATOM_NS, "link")
            .firstOrNull { it.getAttribute("rel") == "alternate" }
            ?.getAttribute("href")
            .orEmpty()

    return FeedEntry(
        videoId = videoId,
        channelId = channelId,
        title = title,
        publishedAt = published,
        isShort = link.contains("/shorts/"),
    )
}

/** YouTube writes `+00:00` offsets, which `Instant.parse` accepts since Java 12 only. */
private fun parseInstant(text: String): Instant? =
    try {
        OffsetDateTime.parse(text).toInstant()
    } catch (
        @Suppress("SwallowedException") e: DateTimeParseException,
    ) {
        null
    }

private fun Element.childElements(
    ns: String,
    name: String,
): List<Element> {
    val nodes = childNodes
    return (0 until nodes.length)
        .map { nodes.item(it) }
        .filterIsInstance<Element>()
        .filter { it.namespaceURI == ns && it.localName == name }
}

private fun Element.childText(
    ns: String,
    name: String,
): String? =
    childElements(ns, name)
        .firstOrNull()
        ?.textContent
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
