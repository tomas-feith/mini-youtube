package com.miniyoutube.app.data

import android.util.Log
import com.miniyoutube.app.domain.Feed
import com.miniyoutube.app.domain.FeedEntry
import com.miniyoutube.app.domain.WatchInfo
import com.miniyoutube.app.domain.feedOverflowed
import com.miniyoutube.app.domain.gapCandidates
import com.miniyoutube.app.domain.newArrivals
import com.miniyoutube.app.network.YouTubeException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.io.IOException
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
    /** Of [failed], those that never got an answer: no connection, a timeout. */
    val unreachable: Int = 0,
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
 * - **The feed being down.** YouTube's feed endpoint has spells of answering 404 for
 *   every channel. When it refuses, the channel is checked from its `/videos` tab
 *   instead (see [checkFromVideosTab]).
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
            val checked = results.filterIsInstance<ChannelResult.Checked>()
            RefreshOutcome(
                newVideos = released + checked.flatMap { it.newVideos },
                checked = checked.size,
                failed = results.size - checked.size,
                unreachable = results.count { it is ChannelResult.Unreachable },
            )
        }

    private sealed interface ChannelResult {
        data class Checked(
            val newVideos: List<NewVideo>,
        ) : ChannelResult

        /** The request never got an answer. */
        data object Unreachable : ChannelResult

        /** YouTube answered, but with an error or something unreadable. */
        data object Refused : ChannelResult
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
     * The videos this channel made watchable, or why it could not be checked.
     *
     * The writes sit inside the catch as well as the fetch: a channel unfollowed while its
     * feed was in flight makes the insert fail its foreign key, and that must cost this
     * channel's round, not crash the screen that asked for a refresh.
     */
    private suspend fun refreshOne(channel: ChannelEntity): ChannelResult =
        try {
            val feed =
                try {
                    source.feed(channel.id)
                } catch (e: YouTubeException) {
                    // YouTube answered, so the site is up and only the feed is not.
                    Log.w(TAG, "Feed for ${channel.id} refused; reading its videos tab", e)
                    null
                }
            ChannelResult.Checked(
                if (feed == null) checkFromVideosTab(channel) else checkFeed(channel, feed),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: YouTubeException) {
            Log.w(TAG, "Checking ${channel.id} failed", e)
            ChannelResult.Refused
        } catch (e: IOException) {
            Log.w(TAG, "Checking ${channel.id} failed", e)
            ChannelResult.Unreachable
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            Log.w(TAG, "Checking ${channel.id} failed", e)
            ChannelResult.Refused
        }

    private suspend fun checkFeed(
        channel: ChannelEntity,
        feed: Feed,
    ): List<NewVideo> {
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

        val title = feed.channelTitle ?: channel.title
        val newestInFeed = feed.entries.maxOfOrNull { it.publishedAt.toEpochMilli() }
        return save(
            channel,
            title,
            arrivals + backfilled,
            now,
            listOfNotNull(channel.feedHighWater, newestInFeed).maxOrNull(),
        )
    }

    /**
     * Checks a channel without its feed, from the `/videos` tab: the newest uploads not
     * yet known, each dated by its watch page.
     *
     * The walk stops at the first known video, at the first from before the follow, and at
     * a page it cannot read - left unstored, that video is simply tried again next time.
     * So a channel with nothing new costs one listing and at most one watch page. Shorts
     * are not on that tab, which suits; streams are not either, and wait for the feed.
     *
     * The feed's high-water mark is left alone: nothing here says what the feed holds.
     */
    private suspend fun checkFromVideosTab(channel: ChannelEntity): List<NewVideo> {
        val now = clock()
        val candidates =
            gapCandidates(
                source.channelVideoIds(channel.id),
                emptySet(),
                store.knownVideoIds(channel.id),
            ).take(MAX_BACKFILL_LOOKUPS)
        val found = walk(channel, candidates, now, stopAtUnreadable = true)
        return save(channel, channel.title, found, now, channel.feedHighWater)
    }

    /** Stores what a check found and returns the rows that are new and watchable now. */
    private suspend fun save(
        channel: ChannelEntity,
        title: String,
        rows: List<VideoEntity>,
        now: Instant,
        highWater: Long?,
    ): List<NewVideo> {
        val inserted = store.addVideos(rows)
        store.markChecked(channel.id, title, now.toEpochMilli(), highWater)
        return inserted
            .filter { it.availableAt == null }
            .map { NewVideo(it.id, it.title, it.publishedAt, title) }
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
        // Unlike the feed fallback, a gap is filled once: the next check's mark is past it.
        return walk(channel, candidates, now, stopAtUnreadable = false)
    }

    /** Dates [candidates], newest first, by their watch pages, and turns them into rows. */
    private suspend fun walk(
        channel: ChannelEntity,
        candidates: List<String>,
        now: Instant,
        stopAtUnreadable: Boolean,
    ): List<VideoEntity> {
        val followedAt = Instant.ofEpochMilli(channel.followedAt)
        val found = mutableListOf<VideoEntity>()
        for (id in candidates) {
            val info = inspect(id)
            if (endsWalk(info, followedAt, stopAtUnreadable)) break
            pageRow(id, info, channel.id, now)?.let { found += it }
        }
        return found
    }

    /**
     * Whether the walk stops at this page. Uploads come newest first, so one from before
     * the follow means the rest are too. A premiere is dated by its scheduling until it
     * starts, so it does not count.
     */
    private fun endsWalk(
        info: WatchInfo?,
        followedAt: Instant,
        stopAtUnreadable: Boolean,
    ): Boolean =
        if (info == null) {
            stopAtUnreadable
        } else {
            !info.upcoming && info.publishedAt?.isBefore(followedAt) == true
        }

    /**
     * The row a video read from its page becomes, or null to skip it: a page that could
     * not be read or gave no date, a video credited to another channel, or an upcoming
     * one with no start time. One with a start time is stored pending, as from the feed.
     */
    private fun pageRow(
        id: String,
        info: WatchInfo?,
        channelId: String,
        now: Instant,
    ): VideoEntity? {
        if (info == null || (info.channelId != null && info.channelId != channelId)) return null
        val published = (if (info.upcoming) info.startsAt else info.publishedAt) ?: return null
        return VideoEntity(
            id = id,
            channelId = channelId,
            title = info.title ?: id,
            publishedAt = published.toEpochMilli(),
            addedAt = now.toEpochMilli(),
            watchedAt = null,
            availableAt = if (info.upcoming) published.toEpochMilli() else null,
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
