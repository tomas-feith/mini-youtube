package com.miniyoutube.app.data

import android.util.Log
import com.miniyoutube.app.domain.Feed
import com.miniyoutube.app.domain.FeedEntry
import com.miniyoutube.app.domain.WatchInfo
import com.miniyoutube.app.domain.feedOverflowed
import com.miniyoutube.app.domain.gapCandidates
import com.miniyoutube.app.domain.newArrivals
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.time.Duration
import java.time.Instant

/** What a refresh reads from YouTube. `YouTubeClient` in the app, maps in the tests. */
interface VideoSource {
    suspend fun feed(channelId: String): Feed

    suspend fun watchInfo(videoId: String): WatchInfo

    suspend fun channelVideoIds(channelId: String): List<String>
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
        highWater: Long?,
    )

    /** Pending premieres and streams whose start time is at or before [now]. */
    suspend fun dueVideos(now: Long): List<VideoWithChannel>

    /** Releases a pending video (null) or postpones it; either way re-dates it. */
    suspend fun setAvailableAt(
        videoId: String,
        availableAt: Long?,
        publishedAt: Long,
    )
}

/** A video that has just become watchable, for the notification. */
data class NewVideo(
    val videoId: String,
    val title: String,
    val publishedAt: Long,
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
 * Beyond the feed itself it deals with the two things the feed gets wrong:
 *
 * - **Premieres and scheduled streams** sit in the feed from the moment they are
 *   scheduled. An entry the feed reports zero views for has its page checked; one that
 *   has not started is stored as pending, out of the backlog, and released - and only
 *   then announced - by the first refresh after it starts.
 * - **The feed's fifteen-entry window.** When it has rolled past videos between two
 *   checks (see `feedOverflowed`), the channel's `/videos` tab is read to fill the gap.
 *
 * One channel failing - a network blip, a deleted channel - costs that channel this round
 * and nothing else. Calls are serialized with a mutex: the in-app refresh and the
 * background worker share this instance, and overlapping runs would double the requests.
 */
class FeedRefresher(
    private val source: VideoSource,
    private val store: RefreshStore,
    private val clock: () -> Instant = Instant::now,
) {
    private val mutex = Mutex()

    suspend fun refresh(): RefreshOutcome =
        mutex.withLock {
            val released = releaseDue()
            val channels = store.channels()
            val permits = Semaphore(PARALLEL_FETCHES)
            val results =
                coroutineScope {
                    channels
                        .map { channel -> async { permits.withPermit { refreshOne(channel) } } }
                        .awaitAll()
                }
            RefreshOutcome(
                newVideos = released + results.filterNotNull().flatten(),
                checked = results.count { it != null },
                failed = results.count { it == null },
            )
        }

    /**
     * Releases pending premieres whose start has come, after checking they did start.
     *
     * A premiere can be pushed back, and a stream can sit at "waiting for host" past its
     * scheduled time; releasing either on the old time would announce something that
     * cannot be watched. One still upcoming is postponed to its new start, or re-checked
     * a little later if it has none. A page that cannot be read releases the video
     * regardless - better an early notification than one that never comes.
     */
    private suspend fun releaseDue(): List<NewVideo> {
        val now = clock()
        val released = mutableListOf<NewVideo>()
        try {
            for (video in store.dueVideos(now.toEpochMilli())) {
                val info = inspect(video.id)
                if (info?.upcoming == true) {
                    val later =
                        info.startsAt?.takeIf { it.isAfter(now) } ?: now.plus(LATE_RECHECK)
                    store.setAvailableAt(video.id, later.toEpochMilli(), later.toEpochMilli())
                } else {
                    store.setAvailableAt(video.id, null, video.publishedAt)
                    released +=
                        NewVideo(video.id, video.title, video.publishedAt, video.channelTitle)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            // Whatever was released before the failure stays released and is announced;
            // the rest is still due and the next refresh picks it up.
            Log.w(TAG, "Releasing pending videos failed", e)
        }
        return released
    }

    /**
     * The videos this channel made watchable, or null if it could not be checked.
     *
     * The writes sit inside the catch as well as the fetch: a channel unfollowed while its
     * feed was in flight makes the insert fail its foreign key, and that must cost this
     * channel's round, not crash the screen that asked for a refresh.
     */
    private suspend fun refreshOne(channel: ChannelEntity): List<NewVideo>? =
        try {
            val feed = source.feed(channel.id)
            val now = clock()
            val followedAt = Instant.ofEpochMilli(channel.followedAt)
            val known = store.knownVideoIds(channel.id)

            val arrivals =
                newArrivals(feed.entries, followedAt, known).mapNotNull {
                    classify(it, channel.id, now)
                }
            val highWater = channel.feedHighWater?.let(Instant::ofEpochMilli)
            val backfilled =
                if (feedOverflowed(feed.entries, highWater)) {
                    backfill(channel, feed, known + arrivals.map { it.id }, now)
                } else {
                    emptyList()
                }

            val inserted = store.addVideos(arrivals + backfilled)
            val title = feed.channelTitle ?: channel.title
            val newestInFeed = feed.entries.maxOfOrNull { it.publishedAt.toEpochMilli() }
            store.markChecked(
                channel.id,
                title,
                now.toEpochMilli(),
                listOfNotNull(channel.feedHighWater, newestInFeed).maxOrNull(),
            )
            inserted
                .filter { it.availableAt == null }
                .map { NewVideo(it.id, it.title, it.publishedAt, title) }
        } catch (e: CancellationException) {
            throw e
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            Log.w(TAG, "Checking ${channel.id} failed", e)
            null
        }

    /**
     * The row a new feed entry becomes: watchable now, pending until it starts, or null to
     * leave it for the next check.
     *
     * Only an entry with zero views is looked into. Everything already watchable has been
     * watched by someone within the hour, so this costs a page fetch only for premieres,
     * streams, and the rare upload caught in its first minutes.
     */
    private suspend fun classify(
        entry: FeedEntry,
        channelId: String,
        now: Instant,
    ): VideoEntity? {
        val row =
            VideoEntity(
                id = entry.videoId,
                channelId = channelId,
                title = entry.title,
                publishedAt = entry.publishedAt.toEpochMilli(),
                addedAt = now.toEpochMilli(),
                watchedAt = null,
            )
        if (entry.views != 0L) return row
        val info = inspect(entry.videoId)
        if (info?.upcoming != true) return row
        // Upcoming with no start time cannot be scheduled; not storing it means the next
        // check asks again, since the entry is still in the feed and still unknown.
        val startsAt = info.startsAt?.toEpochMilli() ?: return null
        return row.copy(publishedAt = startsAt, availableAt = startsAt)
    }

    /**
     * Fills a gap the feed rolled past, from the channel's `/videos` tab.
     *
     * That tab is newest first, so the walk skips what the feed already covered and stops
     * at the first video already known: everything past it was seen before. It also stops
     * at a video older than the follow, and after [MAX_BACKFILL_LOOKUPS] pages, since each
     * candidate costs a watch-page fetch for its exact publish time.
     *
     * Streams are not on that tab and so are not backfilled; Shorts are not either, which
     * is what is wanted.
     */
    private suspend fun backfill(
        channel: ChannelEntity,
        feed: Feed,
        known: Set<String>,
        now: Instant,
    ): List<VideoEntity> {
        val ids =
            try {
                source.channelVideoIds(channel.id)
            } catch (e: CancellationException) {
                throw e
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                Log.w(TAG, "Backfilling ${channel.id} failed", e)
                return emptyList()
            }
        val candidates =
            gapCandidates(ids, feed.entries.map { it.videoId }.toSet(), known)
                .take(MAX_BACKFILL_LOOKUPS)
        val followedAt = Instant.ofEpochMilli(channel.followedAt)
        val found = mutableListOf<VideoEntity>()
        for (id in candidates) {
            val info = inspect(id)
            // Newest first, so one from before the follow means the rest are too.
            if (info?.publishedAt?.isBefore(followedAt) == true) break
            backfillRow(id, info, channel.id, now)?.let { found += it }
        }
        return found
    }

    /**
     * The row a backfilled video becomes, or null to skip it: a page that could not be
     * read or gave no date, a video credited to another channel, or a premiere not yet
     * started - which the feed will list when it does.
     */
    private fun backfillRow(
        id: String,
        info: WatchInfo?,
        channelId: String,
        now: Instant,
    ): VideoEntity? {
        val published = info?.publishedAt ?: return null
        if (info.upcoming || (info.channelId != null && info.channelId != channelId)) return null
        return VideoEntity(
            id = id,
            channelId = channelId,
            title = info.title ?: id,
            publishedAt = published.toEpochMilli(),
            addedAt = now.toEpochMilli(),
            watchedAt = null,
        )
    }

    /** The watch page's verdict, or null when it could not be read. */
    private suspend fun inspect(videoId: String): WatchInfo? =
        try {
            source.watchInfo(videoId)
        } catch (e: CancellationException) {
            throw e
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            Log.w(TAG, "Reading the page for $videoId failed", e)
            null
        }

    private companion object {
        const val TAG = "FeedRefresher"

        /** Enough to finish a few dozen channels quickly without hammering one host. */
        const val PARALLEL_FETCHES = 4

        /** Watch pages fetched per gap at most; each is around a megabyte. */
        const val MAX_BACKFILL_LOOKUPS = 15

        /** How soon to look again at a stream still waiting past its start time. */
        val LATE_RECHECK: Duration = Duration.ofMinutes(30)
    }
}
