package com.miniyoutube.app.data

import com.miniyoutube.app.domain.FEED_WINDOW
import com.miniyoutube.app.domain.Feed
import com.miniyoutube.app.domain.FeedEntry
import com.miniyoutube.app.domain.WatchInfo
import com.miniyoutube.app.network.YouTubeException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.time.Duration
import java.time.Instant

class FeedRefresherTest {
    private val followedAt = Instant.parse("2026-09-01T00:00:00Z")
    private var now = Instant.parse("2026-09-30T12:00:00Z")

    private class FakeStore(
        channels: List<ChannelEntity>,
    ) : RefreshStore {
        val channelRows = channels.associateBy { it.id }.toMutableMap()
        val videos = linkedMapOf<String, VideoEntity>()
        var failInsertFor: String? = null

        override suspend fun channels() = channelRows.values.toList()

        override suspend fun knownVideoIds(channelId: String) =
            videos.values
                .filter { it.channelId == channelId }
                .map { it.id }
                .toSet()

        override suspend fun pendingVideoIds(channelId: String) =
            videos.values
                .filter { it.channelId == channelId && it.availableAt != null }
                .map { it.id }
                .toSet()

        override suspend fun addVideos(videos: List<VideoEntity>): List<VideoEntity> {
            if (videos.any { it.channelId == failInsertFor }) error("FOREIGN KEY constraint failed")
            return videos.filter { this.videos.putIfAbsent(it.id, it) == null }
        }

        override suspend fun markChecked(
            channelId: String,
            title: String,
            checkedAt: Long,
            highWater: Long?,
        ) {
            channelRows[channelId] =
                channelRows.getValue(channelId).copy(
                    title = title,
                    lastCheckedAt = checkedAt,
                    feedHighWater = highWater,
                )
        }

        override suspend fun dueVideos(now: Long) =
            videos.values
                .filter { it.availableAt != null && it.availableAt <= now }
                .map {
                    VideoWithChannel(
                        it.id,
                        it.channelId,
                        channelRows.getValue(it.channelId).title,
                        it.title,
                        it.publishedAt,
                        it.watchedAt,
                        it.resumeAtSeconds,
                    )
                }

        override suspend fun setAvailableAt(
            videoId: String,
            availableAt: Long?,
            publishedAt: Long,
        ) {
            videos[videoId] =
                videos.getValue(videoId).copy(availableAt = availableAt, publishedAt = publishedAt)
        }
    }

    private class FakeSource(
        val feeds: MutableMap<String, Feed> = mutableMapOf(),
        val pages: MutableMap<String, WatchInfo> = mutableMapOf(),
        val uploads: MutableMap<String, List<String>> = mutableMapOf(),
        val streams: MutableMap<String, List<String>> = mutableMapOf(),
        val failingFeeds: Set<String> = emptySet(),
        val refusedFeeds: Set<String> = emptySet(),
    ) : VideoSource {
        val pagesRead = mutableListOf<String>()
        var uploadsRead = 0
        var streamsRead = 0

        override suspend fun feed(channelId: String): Feed {
            if (channelId in failingFeeds) throw IOException("offline")
            if (channelId in refusedFeeds) throw YouTubeException("YouTube answered 404", 404)
            return feeds.getValue(channelId)
        }

        override suspend fun watchInfo(videoId: String): WatchInfo {
            pagesRead += videoId
            return pages[videoId] ?: throw IOException("no page for $videoId")
        }

        override suspend fun channelVideoIds(channelId: String): List<String> {
            uploadsRead++
            return uploads[channelId] ?: throw YouTubeException("YouTube answered 500", 500)
        }

        override suspend fun channelStreamIds(channelId: String): List<String> {
            streamsRead++
            // A channel with no uploads listed stands for one whose every tab is refused.
            if (channelId !in uploads) throw YouTubeException("YouTube answered 500", 500)
            return streams[channelId].orEmpty()
        }
    }

    private fun channel(
        id: String,
        highWater: Instant? = followedAt,
    ) = ChannelEntity(
        id,
        "Old name $id",
        null,
        followedAt.toEpochMilli(),
        null,
        highWater?.toEpochMilli(),
    )

    private fun entry(
        id: String,
        channelId: String = "A",
        publishedAt: Instant = followedAt.plusSeconds(3600),
        views: Long? = 1_000,
        isShort: Boolean = false,
    ) = FeedEntry(id, channelId, "Video $id", publishedAt, isShort, views)

    private fun page(
        id: String,
        publishedAt: Instant? = followedAt.plusSeconds(3600),
        upcoming: Boolean = false,
        startsAt: Instant? = null,
        channelId: String = "A",
    ) = WatchInfo(id, channelId, "Page title $id", publishedAt, upcoming, startsAt)

    private fun refresher(
        source: FakeSource,
        store: FakeStore,
    ) = FeedRefresher(source, store) { now }

    private fun feedOf(vararg entries: FeedEntry) =
        mutableMapOf("A" to Feed("Channel A", entries.toList()))

    @Test
    fun addsNewVideosAndReportsThemWithTheChannelName() =
        runTest {
            val store = FakeStore(listOf(channel("A")))
            val source = FakeSource(feeds = feedOf(entry("v1")))

            val outcome = refresher(source, store).refresh()

            assertEquals(listOf("v1"), outcome.newVideos.map { it.videoId })
            assertEquals("Channel A", outcome.newVideos.single().channelTitle)
            assertEquals(now.toEpochMilli(), store.videos.getValue("v1").addedAt)
            assertEquals("Channel A", store.channelRows.getValue("A").title)
            assertEquals(now.toEpochMilli(), store.channelRows.getValue("A").lastCheckedAt)
            // A video with views is plainly watchable; no page is fetched for it.
            assertTrue(source.pagesRead.isEmpty())
        }

    @Test
    fun aSecondRunFindsNothingNewAndAWatchedVideoDoesNotComeBack() =
        runTest {
            val store = FakeStore(listOf(channel("A")))
            val refresher = refresher(FakeSource(feeds = feedOf(entry("v1"))), store)
            refresher.refresh()
            store.videos["v1"] = store.videos.getValue("v1").copy(watchedAt = now.toEpochMilli())

            assertTrue(refresher.refresh().newVideos.isEmpty())
            assertEquals(now.toEpochMilli(), store.videos.getValue("v1").watchedAt)
        }

    @Test
    fun oneFailingChannelDoesNotStopTheOthers() =
        runTest {
            val store = FakeStore(listOf(channel("A"), channel("B"), channel("C")))
            store.failInsertFor = "C"
            val source =
                FakeSource(
                    feeds =
                        mutableMapOf(
                            "B" to Feed("Channel B", listOf(entry("vB", "B"))),
                            "C" to Feed("Channel C", listOf(entry("vC", "C"))),
                        ),
                    failingFeeds = setOf("A"),
                )

            val outcome = refresher(source, store).refresh()

            assertEquals(listOf("vB"), outcome.newVideos.map { it.videoId })
            assertEquals(1, outcome.checked)
            assertEquals(2, outcome.failed)
            assertNull(store.channelRows.getValue("A").lastCheckedAt)
        }

    @Test
    fun anUpcomingPremiereWaitsUntilItStartsAndIsThenAnnounced() =
        runTest {
            val startsAt = now.plus(Duration.ofHours(20))
            val store = FakeStore(listOf(channel("A")))
            val source =
                FakeSource(
                    feeds = feedOf(entry("p1", views = 0)),
                    pages = mutableMapOf("p1" to page("p1", upcoming = true, startsAt = startsAt)),
                )
            val refresher = refresher(source, store)

            assertTrue("not announced before it starts", refresher.refresh().newVideos.isEmpty())
            assertEquals(startsAt.toEpochMilli(), store.videos.getValue("p1").availableAt)

            // Still in the feed an hour later: known, so neither re-read nor re-added.
            now = now.plus(Duration.ofHours(1))
            source.pagesRead.clear()
            assertTrue(refresher.refresh().newVideos.isEmpty())
            assertTrue(source.pagesRead.isEmpty())

            // After the start, the page no longer says upcoming.
            now = startsAt.plus(Duration.ofMinutes(5))
            source.pages["p1"] = page("p1", upcoming = false, startsAt = startsAt)
            val released = refresher.refresh()
            assertEquals(listOf("p1"), released.newVideos.map { it.videoId })
            assertNull(store.videos.getValue("p1").availableAt)
            // Dated by its start, so it sorts where it went live, not where it was scheduled.
            assertEquals(startsAt.toEpochMilli(), store.videos.getValue("p1").publishedAt)

            // And released once only.
            assertTrue(refresher.refresh().newVideos.isEmpty())
        }

    @Test
    fun aPostponedPremiereIsHeldUntilItsNewTime() =
        runTest {
            val startsAt = now.plus(Duration.ofHours(1))
            val pushedTo = startsAt.plus(Duration.ofDays(1))
            val store = FakeStore(listOf(channel("A")))
            val source =
                FakeSource(
                    feeds = feedOf(entry("p1", views = 0)),
                    pages = mutableMapOf("p1" to page("p1", upcoming = true, startsAt = startsAt)),
                )
            val refresher = refresher(source, store)
            refresher.refresh()

            now = startsAt.plus(Duration.ofMinutes(10))
            source.pages["p1"] = page("p1", upcoming = true, startsAt = pushedTo)
            assertTrue(refresher.refresh().newVideos.isEmpty())
            assertEquals(pushedTo.toEpochMilli(), store.videos.getValue("p1").availableAt)
        }

    @Test
    fun aStreamRunningLateIsRecheckedLaterRatherThanReleased() =
        runTest {
            val startsAt = now.plus(Duration.ofHours(1))
            val store = FakeStore(listOf(channel("A")))
            val source =
                FakeSource(
                    feeds = feedOf(entry("s1", views = 0)),
                    pages = mutableMapOf("s1" to page("s1", upcoming = true, startsAt = startsAt)),
                )
            val refresher = refresher(source, store)
            refresher.refresh()

            now = startsAt.plus(Duration.ofMinutes(10))
            assertTrue(refresher.refresh().newVideos.isEmpty())
            assertEquals(
                now.plus(Duration.ofMinutes(30)).toEpochMilli(),
                store.videos.getValue("s1").availableAt,
            )
        }

    @Test
    fun aZeroViewUploadThatIsNotUpcomingIsAddedAtOnce() =
        runTest {
            val store = FakeStore(listOf(channel("A")))
            val source =
                FakeSource(
                    feeds = feedOf(entry("v1", views = 0)),
                    pages = mutableMapOf("v1" to page("v1")),
                )
            val outcome = refresher(source, store).refresh()
            assertEquals(listOf("v1"), outcome.newVideos.map { it.videoId })
        }

    @Test
    fun anUnreadablePageDoesNotHoldAVideoBack() =
        runTest {
            val store = FakeStore(listOf(channel("A")))
            val outcome =
                refresher(
                    FakeSource(feeds = feedOf(entry("v1", views = 0))),
                    store,
                ).refresh()
            assertEquals(listOf("v1"), outcome.newVideos.map { it.videoId })
        }

    @Test
    fun upcomingWithNoStartTimeIsLeftForTheNextCheck() =
        runTest {
            val store = FakeStore(listOf(channel("A")))
            val source =
                FakeSource(
                    feeds = feedOf(entry("p1", views = 0)),
                    pages = mutableMapOf("p1" to page("p1", upcoming = true, startsAt = null)),
                )
            assertTrue(refresher(source, store).refresh().newVideos.isEmpty())
            assertTrue("p1" !in store.videos)
        }

    @Test
    fun anOverflowedFeedIsBackfilledFromTheVideosTab() =
        runTest {
            val lastSeen = followedAt.plus(Duration.ofDays(1))
            val store = FakeStore(listOf(channel("A", highWater = lastSeen)))
            store.videos["old"] =
                VideoEntity("old", "A", "Old", lastSeen.toEpochMilli(), 0, watchedAt = 1)
            // Fifteen entries, all newer than anything seen before: the feed rolled over.
            // Most are Shorts, which count towards the window but never reach the backlog.
            val base = lastSeen.plus(Duration.ofDays(3))
            val feedEntries =
                (1..FEED_WINDOW).map { i ->
                    entry(
                        "f$i",
                        publishedAt = base.plus(Duration.ofHours(i.toLong())),
                        isShort =
                            i > 2,
                    )
                }
            val source =
                FakeSource(
                    feeds = feedOf(*feedEntries.toTypedArray()),
                    uploads =
                        mutableMapOf(
                            "A" to listOf("f2", "f1", "gap2", "gap1", "old", "older"),
                        ),
                    pages =
                        mutableMapOf(
                            "gap2" to page("gap2", publishedAt = lastSeen.plus(Duration.ofDays(2))),
                            "gap1" to page("gap1", publishedAt = lastSeen.plus(Duration.ofDays(1))),
                        ),
                )

            val outcome = refresher(source, store).refresh()

            assertEquals(
                setOf("f1", "f2", "gap1", "gap2"),
                outcome.newVideos.map { it.videoId }.toSet(),
            )
            assertEquals("Page title gap2", store.videos.getValue("gap2").title)
            // The walk stopped at the first known video; nothing past it was fetched.
            assertEquals(listOf("gap2", "gap1"), source.pagesRead)
            assertEquals(
                feedEntries.maxOf { it.publishedAt }.toEpochMilli(),
                store.channelRows.getValue("A").feedHighWater,
            )
        }

    @Test
    fun aFeedThatStillReachesTheMarkIsNotBackfilled() =
        runTest {
            // The mark sits among the entries: the oldest is older than it, so nothing can
            // have fallen out between the two checks.
            val entries =
                (1..FEED_WINDOW).map {
                    entry(
                        "f$it",
                        publishedAt = followedAt.plus(Duration.ofHours(it.toLong())),
                    )
                }
            val store = FakeStore(listOf(channel("A", highWater = entries[2].publishedAt)))
            val source = FakeSource(feeds = feedOf(*entries.toTypedArray()))

            refresher(source, store).refresh()

            assertEquals(0, source.uploadsRead)
        }

    @Test
    fun backfillStopsAtAVideoFromBeforeTheFollow() =
        runTest {
            val store = FakeStore(listOf(channel("A", highWater = followedAt)))
            val base = followedAt.plus(Duration.ofDays(3))
            val feedEntries =
                (1..FEED_WINDOW).map {
                    entry("f$it", publishedAt = base.plusSeconds(it.toLong()))
                }
            val source =
                FakeSource(
                    feeds = feedOf(*feedEntries.toTypedArray()),
                    uploads = mutableMapOf("A" to listOf("f1", "pre", "prepre")),
                    pages =
                        mutableMapOf(
                            "pre" to page("pre", publishedAt = followedAt.minusSeconds(1)),
                        ),
                )

            val outcome = refresher(source, store).refresh()

            assertTrue(outcome.newVideos.none { it.videoId == "pre" })
            assertEquals(listOf("pre"), source.pagesRead)
        }

    @Test
    fun aVideoFromAnotherChannelOnThePageIsNotBackfilled() =
        runTest {
            val store = FakeStore(listOf(channel("A", highWater = followedAt)))
            val base = followedAt.plus(Duration.ofDays(3))
            val feedEntries =
                (1..FEED_WINDOW).map {
                    entry("f$it", publishedAt = base.plusSeconds(it.toLong()))
                }
            val source =
                FakeSource(
                    feeds = feedOf(*feedEntries.toTypedArray()),
                    uploads = mutableMapOf("A" to listOf("collab")),
                    pages = mutableMapOf("collab" to page("collab", channelId = "UCother")),
                )

            val outcome = refresher(source, store).refresh()

            assertTrue(outcome.newVideos.none { it.videoId == "collab" })
        }

    @Test
    fun aRefusedFeedIsCheckedFromTheVideosTabInstead() =
        runTest {
            val store = FakeStore(listOf(channel("A", highWater = followedAt)))
            store.videos["seen"] =
                VideoEntity("seen", "A", "Seen", followedAt.toEpochMilli(), 0, watchedAt = 1)
            val source =
                FakeSource(
                    refusedFeeds = setOf("A"),
                    uploads = mutableMapOf("A" to listOf("new2", "new1", "seen", "older")),
                    pages =
                        mutableMapOf(
                            "new2" to page("new2", publishedAt = followedAt.plusSeconds(7200)),
                            "new1" to page("new1", publishedAt = followedAt.plusSeconds(3600)),
                        ),
                )

            val outcome = refresher(source, store).refresh()

            assertEquals(listOf("new2", "new1"), outcome.newVideos.map { it.videoId })
            assertEquals("Old name A", outcome.newVideos.first().channelTitle)
            assertEquals(1, outcome.checked)
            // The walk stopped at the first known video.
            assertEquals(listOf("new2", "new1"), source.pagesRead)
            assertEquals(now.toEpochMilli(), store.channelRows.getValue("A").lastCheckedAt)
            // Nothing was learned about the feed, so its mark stays where it was.
            assertEquals(followedAt.toEpochMilli(), store.channelRows.getValue("A").feedHighWater)
        }

    @Test
    fun withoutTheFeedAQuietChannelCostsOnePageAtMost() =
        runTest {
            val store = FakeStore(listOf(channel("A")))
            val source =
                FakeSource(
                    refusedFeeds = setOf("A"),
                    uploads = mutableMapOf("A" to listOf("pre1", "pre2", "pre3")),
                    pages =
                        mutableMapOf(
                            "pre1" to page("pre1", publishedAt = followedAt.minusSeconds(1)),
                        ),
                )

            val outcome = refresher(source, store).refresh()

            assertTrue(outcome.newVideos.isEmpty())
            assertEquals(1, outcome.checked)
            assertEquals(listOf("pre1"), source.pagesRead)
        }

    @Test
    fun withoutTheFeedAnUnreadablePageIsSkippedAndTriedAgain() =
        runTest {
            val store = FakeStore(listOf(channel("A")))
            val source =
                FakeSource(
                    refusedFeeds = setOf("A"),
                    uploads = mutableMapOf("A" to listOf("v2", "v1", "pre")),
                    pages =
                        mutableMapOf(
                            "v1" to page("v1"),
                            "pre" to page("pre", publishedAt = followedAt.minusSeconds(1)),
                        ),
                )
            val refresher = refresher(source, store)

            // One region-blocked video does not hide the one behind it.
            assertEquals(listOf("v1"), refresher.refresh().newVideos.map { it.videoId })
            assertEquals(listOf("v2", "v1", "pre"), source.pagesRead)

            // Still above the first known video, so the next check asks again.
            source.pages["v2"] = page("v2", publishedAt = followedAt.plusSeconds(7200))
            source.pagesRead.clear()
            assertEquals(listOf("v2"), refresher.refresh().newVideos.map { it.videoId })
            assertEquals(listOf("v2"), source.pagesRead)
        }

    @Test
    fun watchPagesFailingAltogetherEndTheWalkEarly() =
        runTest {
            val store = FakeStore(listOf(channel("A")))
            val source =
                FakeSource(
                    refusedFeeds = setOf("A"),
                    uploads = mutableMapOf("A" to (1..10).map { "v$it" }),
                )

            refresher(source, store).refresh()

            assertEquals(listOf("v1", "v2"), source.pagesRead)
        }

    @Test
    fun withoutTheFeedStreamsAreFoundOnTheirTab() =
        runTest {
            val store = FakeStore(listOf(channel("A")))
            val source =
                FakeSource(
                    refusedFeeds = setOf("A"),
                    uploads = mutableMapOf("A" to listOf("pre")),
                    streams = mutableMapOf("A" to listOf("live1", "preStream")),
                    pages =
                        mutableMapOf(
                            "pre" to page("pre", publishedAt = followedAt.minusSeconds(1)),
                            "live1" to page("live1"),
                            "preStream" to
                                page("preStream", publishedAt = followedAt.minusSeconds(5)),
                        ),
                )

            val outcome = refresher(source, store).refresh()

            assertEquals(listOf("live1"), outcome.newVideos.map { it.videoId })
            assertEquals(listOf("pre", "live1", "preStream"), source.pagesRead)
        }

    @Test
    fun aKnownPremiereAtTheTopOfATabDoesNotHideNewUploadsBelowIt() =
        runTest {
            val startsAt = now.plus(Duration.ofDays(2))
            val store = FakeStore(listOf(channel("A")))
            store.videos["p1"] =
                VideoEntity(
                    "p1",
                    "A",
                    "Premiere",
                    startsAt.toEpochMilli(),
                    0,
                    watchedAt = null,
                    availableAt = startsAt.toEpochMilli(),
                )
            val source =
                FakeSource(
                    refusedFeeds = setOf("A"),
                    uploads = mutableMapOf("A" to listOf("p1", "v1", "pre")),
                    pages =
                        mutableMapOf(
                            "v1" to page("v1"),
                            "pre" to page("pre", publishedAt = followedAt.minusSeconds(1)),
                        ),
                )

            val outcome = refresher(source, store).refresh()

            assertEquals(listOf("v1"), outcome.newVideos.map { it.videoId })
            // The premiere was skipped, not re-read and not stopped at.
            assertEquals(listOf("v1", "pre"), source.pagesRead)
        }

    @Test
    fun withoutTheFeedAnUpcomingPremiereIsHeldBack() =
        runTest {
            val startsAt = now.plus(Duration.ofHours(5))
            val store = FakeStore(listOf(channel("A")))
            val source =
                FakeSource(
                    refusedFeeds = setOf("A"),
                    uploads = mutableMapOf("A" to listOf("p1", "pre")),
                    pages =
                        mutableMapOf(
                            // Dated by its scheduling, before the follow: must not end the walk.
                            "p1" to
                                page(
                                    "p1",
                                    publishedAt = followedAt.minusSeconds(60),
                                    upcoming = true,
                                    startsAt = startsAt,
                                ),
                            "pre" to page("pre", publishedAt = followedAt.minusSeconds(1)),
                        ),
                )

            assertTrue(refresher(source, store).refresh().newVideos.isEmpty())
            assertEquals(startsAt.toEpochMilli(), store.videos.getValue("p1").availableAt)
            assertEquals(listOf("p1", "pre"), source.pagesRead)
        }

    @Test
    fun noAnswerAndAWrongAnswerAreToldApart() =
        runTest {
            val store = FakeStore(listOf(channel("A"), channel("B")))
            // A: no connection. B: the feed refused, and so did the videos tab.
            val source = FakeSource(failingFeeds = setOf("A"), refusedFeeds = setOf("B"))

            val outcome = refresher(source, store).refresh()

            assertEquals(0, outcome.checked)
            assertEquals(2, outcome.failed)
            assertEquals(1, outcome.unreachable)
            // No connection: the tabs are not tried for A, they would fail the same way.
            assertEquals(1, source.uploadsRead)
            assertEquals(1, source.streamsRead)
        }
}
