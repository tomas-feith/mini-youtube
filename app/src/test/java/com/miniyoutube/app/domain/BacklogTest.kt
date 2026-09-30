package com.miniyoutube.app.domain

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Duration
import java.time.Instant

class BacklogTest {
    private val followedAt = Instant.parse("2026-09-01T12:00:00Z")

    private fun entry(
        id: String,
        publishedAt: Instant,
        isShort: Boolean = false,
    ) = FeedEntry(id, "UCx", "title $id", publishedAt, isShort)

    @Test
    fun keepsOnlyNewUnseenLongFormVideos() {
        val entries =
            listOf(
                entry("new", followedAt.plusSeconds(60)),
                entry("short", followedAt.plusSeconds(60), isShort = true),
                entry("old", followedAt.minusSeconds(60)),
                entry("known", followedAt.plusSeconds(120)),
            )
        val result = newArrivals(entries, followedAt, known = setOf("known"))
        assertEquals(listOf("new"), result.map { it.videoId })
    }

    @Test
    fun theFirstCheckAfterFollowingAddsNothingFromTheBackCatalogue() {
        val backCatalogue =
            (1..15).map {
                entry(
                    "v$it",
                    followedAt.minus(Duration.ofDays(it.toLong())),
                )
            }
        assertEquals(emptyList<FeedEntry>(), newArrivals(backCatalogue, followedAt, emptySet()))
    }

    @Test
    fun aVideoPublishedAtTheExactFollowInstantCounts() {
        val result = newArrivals(listOf(entry("edge", followedAt)), followedAt, emptySet())
        assertEquals(listOf("edge"), result.map { it.videoId })
    }

    @Test
    fun duplicateEntriesAreCollapsed() {
        val e = entry("dup", followedAt.plusSeconds(1))
        assertEquals(1, newArrivals(listOf(e, e), followedAt, emptySet()).size)
    }

    @Test
    fun relativeAges() {
        val now = Instant.parse("2026-09-30T12:00:00Z")
        assertEquals("just now", relativeAge(now.minusSeconds(30), now))
        assertEquals("just now", relativeAge(now.plusSeconds(300), now))
        assertEquals("5 min ago", relativeAge(now.minus(Duration.ofMinutes(5)), now))
        assertEquals("59 min ago", relativeAge(now.minus(Duration.ofMinutes(59)), now))
        assertEquals("1 h ago", relativeAge(now.minus(Duration.ofMinutes(60)), now))
        assertEquals("23 h ago", relativeAge(now.minus(Duration.ofHours(23)), now))
        assertEquals("1 d ago", relativeAge(now.minus(Duration.ofHours(24)), now))
        assertEquals("6 d ago", relativeAge(now.minus(Duration.ofDays(6)), now))
        assertEquals("1 wk ago", relativeAge(now.minus(Duration.ofDays(7)), now))
        assertEquals("4 wk ago", relativeAge(now.minus(Duration.ofDays(29)), now))
        assertEquals("1 mo ago", relativeAge(now.minus(Duration.ofDays(30)), now))
        assertEquals("1 y ago", relativeAge(now.minus(Duration.ofDays(365)), now))
    }
}
