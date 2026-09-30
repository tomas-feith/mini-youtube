package com.miniyoutube.app.data

import com.miniyoutube.app.domain.Feed
import com.miniyoutube.app.domain.FeedEntry
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.time.Instant

class FeedRefresherTest {
    private val followedAt = Instant.parse("2026-09-01T00:00:00Z")
    private val now = Instant.parse("2026-09-30T12:00:00Z")

    private class FakeStore(
        channels: List<ChannelEntity>,
    ) : RefreshStore {
        val channelRows = channels.associateBy { it.id }.toMutableMap()
        val videos = mutableMapOf<String, VideoEntity>()
        var failInsertFor: String? = null

        override suspend fun channels() = channelRows.values.toList()

        override suspend fun knownVideoIds(channelId: String) =
            videos.values
                .filter { it.channelId == channelId }
                .map { it.id }
                .toSet()

        override suspend fun addVideos(videos: List<VideoEntity>): List<VideoEntity> {
            if (videos.any { it.channelId == failInsertFor }) {
                error("FOREIGN KEY constraint failed")
            }
            return videos.filter { this.videos.putIfAbsent(it.id, it) == null }
        }

        override suspend fun markChecked(
            channelId: String,
            title: String,
            checkedAt: Long,
        ) {
            channelRows[channelId] =
                channelRows.getValue(channelId).copy(title = title, lastCheckedAt = checkedAt)
        }
    }

    private fun channel(id: String) =
        ChannelEntity(id, "Old name $id", null, followedAt.toEpochMilli(), null)

    private fun entry(
        id: String,
        channelId: String,
        publishedAt: Instant = followedAt.plusSeconds(3600),
    ) = FeedEntry(id, channelId, "Video $id", publishedAt, isShort = false)

    @Test
    fun addsNewVideosAndReportsThemWithTheChannelName() =
        runTest {
            val store = FakeStore(listOf(channel("A")))
            val feeds = mapOf("A" to Feed("Channel A", listOf(entry("v1", "A"))))
            val refresher = FeedRefresher({ feeds.getValue(it) }, store) { now }

            val outcome = refresher.refresh()

            assertEquals(listOf("v1"), outcome.newVideos.map { it.video.id })
            assertEquals("Channel A", outcome.newVideos.single().channelTitle)
            assertEquals(now.toEpochMilli(), store.videos.getValue("v1").addedAt)
            assertEquals(null, store.videos.getValue("v1").watchedAt)
            assertEquals("Channel A", store.channelRows.getValue("A").title)
            assertEquals(now.toEpochMilli(), store.channelRows.getValue("A").lastCheckedAt)
        }

    @Test
    fun aSecondRunFindsNothingNew() =
        runTest {
            val store = FakeStore(listOf(channel("A")))
            val feeds = mapOf("A" to Feed("Channel A", listOf(entry("v1", "A"))))
            val refresher = FeedRefresher({ feeds.getValue(it) }, store) { now }

            refresher.refresh()
            assertTrue(refresher.refresh().newVideos.isEmpty())
        }

    @Test
    fun aWatchedVideoStillInTheFeedDoesNotComeBack() =
        runTest {
            val store = FakeStore(listOf(channel("A")))
            val feeds = mapOf("A" to Feed("Channel A", listOf(entry("v1", "A"))))
            val refresher = FeedRefresher({ feeds.getValue(it) }, store) { now }
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
            val refresher =
                FeedRefresher(
                    source = { id ->
                        if (id == "A") throw IOException("offline")
                        Feed("Channel $id", listOf(entry("v$id", id)))
                    },
                    store = store,
                ) { now }

            val outcome = refresher.refresh()

            assertEquals(listOf("vB"), outcome.newVideos.map { it.video.id })
            assertEquals(1, outcome.checked)
            assertEquals(2, outcome.failed)
            // A failed channel is not stamped as checked.
            assertEquals(null, store.channelRows.getValue("A").lastCheckedAt)
        }

    @Test
    fun aFeedWithNoTitleKeepsTheStoredName() =
        runTest {
            val store = FakeStore(listOf(channel("A")))
            val refresher = FeedRefresher({ Feed(null, emptyList()) }, store) { now }
            refresher.refresh()
            assertEquals("Old name A", store.channelRows.getValue("A").title)
        }
}
