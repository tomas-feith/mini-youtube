package com.miniyoutube.app.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

/** Runs the real SQL, cascade included, against an in-memory database. */
@RunWith(AndroidJUnit4::class)
class VideoDaoTest {
    private lateinit var db: VideoDatabase
    private lateinit var library: Library
    private val now = Instant.parse("2026-09-30T12:00:00Z")

    @Before
    fun open() {
        db =
            Room
                .inMemoryDatabaseBuilder(
                    InstrumentationRegistry.getInstrumentation().targetContext,
                    VideoDatabase::class.java,
                ).build()
        library = Library(db.videoDao()) { now }
    }

    @After
    fun close() = db.close()

    private fun video(
        id: String,
        channelId: String = "UCa",
        publishedAt: Long = 1_000,
    ) = VideoEntity(id, channelId, "Video $id", publishedAt, addedAt = 2_000, watchedAt = null)

    @Test
    fun followingTwiceKeepsTheFirstRowAndItsVideos() =
        runTest {
            assertTrue(library.follow("UCa", "A", null))
            library.addVideos(listOf(video("v1")))

            assertFalse(library.follow("UCa", "A renamed", null))

            assertEquals("A", library.channels().single().title)
            assertEquals(setOf("v1"), library.knownVideoIds("UCa"))
        }

    @Test
    fun addVideosReturnsOnlyWhatWasInserted() =
        runTest {
            library.follow("UCa", "A", null)
            assertEquals(listOf("v1"), library.addVideos(listOf(video("v1"))).map { it.id })
            val second = library.addVideos(listOf(video("v1"), video("v2")))
            assertEquals(listOf("v2"), second.map { it.id })
        }

    @Test
    fun backlogIsUnwatchedNewestFirstWithChannelNames() =
        runTest {
            library.follow("UCa", "Channel A", null)
            library.addVideos(listOf(video("old", publishedAt = 1), video("new", publishedAt = 2)))

            val backlog = library.backlog.first()
            assertEquals(listOf("new", "old"), backlog.map { it.id })
            assertEquals("Channel A", backlog.first().channelTitle)

            library.markWatched("new")
            assertEquals(listOf("old"), library.backlog.first().map { it.id })
            assertEquals(now.toEpochMilli(), library.video("new").first()?.watchedAt)

            library.markUnwatched("new")
            assertEquals(listOf("new", "old"), library.backlog.first().map { it.id })
        }

    @Test
    fun aWatchedVideoIsStillKnown() =
        runTest {
            library.follow("UCa", "A", null)
            library.addVideos(listOf(video("v1")))
            library.markWatched("v1")
            assertEquals(setOf("v1"), library.knownVideoIds("UCa"))
        }

    @Test
    fun unfollowingRemovesTheChannelsVideos() =
        runTest {
            library.follow("UCa", "A", null)
            library.follow("UCb", "B", null)
            library.addVideos(listOf(video("a1", "UCa"), video("b1", "UCb")))

            library.unfollow("UCa")

            assertEquals(listOf("b1"), library.backlog.first().map { it.id })
            assertNull(library.video("a1").first())
        }

    @Test
    fun resumePointAndCheckStamp() =
        runTest {
            library.follow("UCa", "A", null)
            library.addVideos(listOf(video("v1")))
            library.saveResumePoint("v1", 125)
            library.saveResumePoint("v1", -3)
            assertEquals(0, library.video("v1").first()?.resumeAtSeconds)
            library.saveResumePoint("v1", 125)
            assertEquals(125, library.video("v1").first()?.resumeAtSeconds)

            library.markChecked("UCa", "A renamed", 42, 99)
            val channel = library.channels().single()
            assertEquals("A renamed", channel.title)
            assertEquals(42L, channel.lastCheckedAt)
            assertEquals(99L, channel.feedHighWater)
        }

    @Test
    fun followingRecordsYouTubesClockAndTheFeedsMark() =
        runTest {
            val server = Instant.parse("2026-09-30T10:00:00Z")
            library.follow(
                "UCa",
                "A",
                null,
                followedAt = server,
                highWater = server.minusSeconds(60),
            )
            val channel = library.channels().single()
            assertEquals(server.toEpochMilli(), channel.followedAt)
            assertEquals(server.minusSeconds(60).toEpochMilli(), channel.feedHighWater)
        }

    @Test
    fun aPendingPremiereStaysOutOfTheBacklogUntilReleased() =
        runTest {
            library.follow("UCa", "A", null)
            val startsAt = now.plusSeconds(3600).toEpochMilli()
            library.addVideos(
                listOf(video("p1").copy(availableAt = startsAt, publishedAt = startsAt)),
            )

            assertTrue(library.backlog.first().isEmpty())
            assertTrue(library.dueVideos(now.toEpochMilli()).isEmpty())
            assertEquals(setOf("p1"), library.knownVideoIds("UCa"))

            val due = library.dueVideos(startsAt)
            assertEquals(listOf("p1"), due.map { it.id })
            assertEquals("A", due.single().channelTitle)

            library.setAvailableAt("p1", null, startsAt)
            assertEquals(listOf("p1"), library.backlog.first().map { it.id })
            assertTrue(library.dueVideos(startsAt).isEmpty())
        }
}
