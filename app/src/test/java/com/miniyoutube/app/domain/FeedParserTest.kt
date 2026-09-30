package com.miniyoutube.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.xml.sax.SAXException
import java.time.Instant

class FeedParserTest {
    private val sample =
        requireNotNull(javaClass.classLoader?.getResource("feed-sample.xml")).readText()

    @Test
    fun readsChannelTitle() {
        assertEquals("Marques Brownlee", parseFeed(sample).channelTitle)
    }

    @Test
    fun readsEveryWellFormedEntryAndSkipsTheBrokenOne() {
        val ids = parseFeed(sample).entries.map { it.videoId }
        assertEquals(listOf("rayrrXot17M", "R6yNUnRXZ64", "DkUuOr21v4s"), ids)
    }

    @Test
    fun readsEntryFields() {
        val first = parseFeed(sample).entries.first()
        assertEquals("UCBJycsmduvYEL83R_U4JriQ", first.channelId)
        assertEquals("Nothing Headphone 1 Pro: They Copied the Wrong Thing!", first.title)
        assertEquals(Instant.parse("2026-09-29T01:01:35Z"), first.publishedAt)
        assertFalse(first.isShort)
    }

    @Test
    fun readsViewCountsWhereTheFeedGivesThem() {
        val entries = parseFeed(sample).entries.associateBy { it.videoId }
        assertEquals(3942740L, entries.getValue("rayrrXot17M").views)
        assertEquals(null, entries.getValue("DkUuOr21v4s").views)
    }

    @Test
    fun marksShortsByTheirLink() {
        val entries = parseFeed(sample).entries.associateBy { it.videoId }
        assertTrue(entries.getValue("R6yNUnRXZ64").isShort)
        assertFalse(entries.getValue("DkUuOr21v4s").isShort)
    }

    @Test
    fun decodesEntitiesInTitles() {
        val entry = parseFeed(sample).entries.first { it.videoId == "DkUuOr21v4s" }
        assertEquals("Xiaomi 18 Pro Max: They've Done It Again!", entry.title)
    }

    @Test
    fun anEmptyFeedHasNoEntries() {
        val xml =
            """<feed xmlns="http://www.w3.org/2005/Atom"><title>Quiet</title></feed>"""
        val feed = parseFeed(xml)
        assertEquals("Quiet", feed.channelTitle)
        assertTrue(feed.entries.isEmpty())
    }

    @Test(expected = SAXException::class)
    fun anHtmlErrorPageIsNotAFeed() {
        parseFeed("<!DOCTYPE html><html lang=en><p>404. That's an error.")
    }

    @Test(expected = SAXException::class)
    fun refusesADoctypeRatherThanExpandingEntities() {
        parseFeed(
            """<?xml version="1.0"?><!DOCTYPE f [<!ENTITY x "boom">]>""" +
                """<feed xmlns="http://www.w3.org/2005/Atom"><title>&x;</title></feed>""",
        )
    }
}
