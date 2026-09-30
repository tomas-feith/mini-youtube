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
    fun overflowIsAFullFeedEntirelyNewerThanTheMark() {
        val mark = followedAt
        val full = (1..FEED_WINDOW).map { entry("v$it", mark.plusSeconds(it.toLong())) }
        assertEquals(true, feedOverflowed(full, mark))

        // The oldest entry is the mark itself: continuous with the last check.
        val touching = full.dropLast(1) + entry("edge", mark)
        assertEquals(false, feedOverflowed(touching, mark))

        // Not full: nothing can have been pushed out.
        assertEquals(false, feedOverflowed(full.drop(1), mark))

        // No mark yet: nothing to compare against.
        assertEquals(false, feedOverflowed(full, null))
    }

    @Test
    fun gapCandidatesSkipTheFeedAndStopAtTheFirstKnownVideo() {
        val uploads = listOf("f1", "gap1", "f2", "gap2", "seen", "older")
        assertEquals(
            listOf("gap1", "gap2"),
            gapCandidates(uploads, inFeed = setOf("f1", "f2"), known = setOf("seen")),
        )
        assertEquals(
            emptyList<String>(),
            gapCandidates(listOf("seen", "x"), emptySet(), setOf("seen")),
        )
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
