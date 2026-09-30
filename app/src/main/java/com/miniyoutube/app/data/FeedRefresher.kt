package com.miniyoutube.app.data

import android.util.Log
import com.miniyoutube.app.domain.Feed
import com.miniyoutube.app.domain.newArrivals
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.time.Instant

/** Where feeds come from. `YouTubeClient::fetchFeed` in the app, a map in the tests. */
fun interface FeedSource {
    suspend fun feed(channelId: String): Feed
}

/** The slice of storage a refresh needs, so it can be tested without Room. */
interface RefreshStore {
    suspend fun channels(): List<ChannelEntity>

    suspend fun knownVideoIds(channelId: String): Set<String>

    /** Inserts what is not there yet, and returns only the rows that were inserted. */
    suspend fun addVideos(videos: List<VideoEntity>): List<VideoEntity>

    suspend fun markChecked(
        channelId: String,
        title: String,
        checkedAt: Long,
    )
}

data class NewVideo(
    val video: VideoEntity,
    val channelTitle: String,
)

data class RefreshOutcome(
    val newVideos: List<NewVideo>,
    val checked: Int,
    val failed: Int,
)

/**
 * Reads every followed channel's feed and adds what is new to the backlog.
 *
 * One channel failing - a network blip, a channel that was deleted - costs that channel
 * this round and nothing else; it is counted and the rest carry on.
 *
 * Calls are serialized with a mutex. The in-app refresh and the background worker share
 * this instance, and while the insert is already safe against a race (see
 * `VideoDao.insertVideos`), two overlapping runs would double the requests for nothing.
 */
class FeedRefresher(
    private val source: FeedSource,
    private val store: RefreshStore,
    private val clock: () -> Instant = Instant::now,
) {
    private val mutex = Mutex()

    suspend fun refresh(): RefreshOutcome =
        mutex.withLock {
            val channels = store.channels()
            val permits = Semaphore(PARALLEL_FETCHES)
            val results =
                coroutineScope {
                    channels
                        .map { channel -> async { permits.withPermit { refreshOne(channel) } } }
                        .awaitAll()
                }
            RefreshOutcome(
                newVideos = results.filterNotNull().flatten(),
                checked = results.count { it != null },
                failed = results.count { it == null },
            )
        }

    /**
     * The videos this channel added, or null if it could not be checked.
     *
     * The writes sit inside the catch as well as the fetch: a channel unfollowed while its
     * feed was in flight makes the insert fail its foreign key, and that must cost this
     * channel's round, not crash the screen that asked for a refresh.
     */
    private suspend fun refreshOne(channel: ChannelEntity): List<NewVideo>? =
        try {
            val feed = source.feed(channel.id)
            val now = clock()
            val arrivals =
                newArrivals(
                    entries = feed.entries,
                    followedAt = Instant.ofEpochMilli(channel.followedAt),
                    known = store.knownVideoIds(channel.id),
                )
            val inserted =
                store.addVideos(
                    arrivals.map {
                        VideoEntity(
                            id = it.videoId,
                            channelId = channel.id,
                            title = it.title,
                            publishedAt = it.publishedAt.toEpochMilli(),
                            addedAt = now.toEpochMilli(),
                            watchedAt = null,
                        )
                    },
                )
            val title = feed.channelTitle ?: channel.title
            store.markChecked(channel.id, title, now.toEpochMilli())
            inserted.map { NewVideo(it, title) }
        } catch (e: CancellationException) {
            throw e
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            Log.w(TAG, "Checking ${channel.id} failed", e)
            null
        }

    private companion object {
        const val TAG = "FeedRefresher"

        /** Enough to finish a few dozen channels quickly without hammering one host. */
        const val PARALLEL_FETCHES = 4
    }
}
