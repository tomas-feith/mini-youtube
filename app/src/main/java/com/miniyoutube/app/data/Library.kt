package com.miniyoutube.app.data

import kotlinx.coroutines.flow.Flow
import java.time.Instant

/**
 * The single way the UI and the worker touch the database.
 *
 * @param clock injectable so a test can say what "now" is.
 */
class Library(
    private val dao: VideoDao,
    private val clock: () -> Instant = Instant::now,
) : RefreshStore {
    val channels: Flow<List<ChannelEntity>> = dao.observeChannels()

    /** Unwatched videos, newest first. */
    val backlog: Flow<List<VideoWithChannel>> = dao.observeBacklog()

    fun video(id: String): Flow<VideoWithChannel?> = dao.observeVideo(id)

    /**
     * @param followedAt when following began, by YouTube's clock where known; the phone's
     *   clock is only the fallback. See `Feed.fetchedAt`.
     * @param highWater the newest publish time in the feed read while following, so the
     *   first check can already tell whether the feed has rolled past anything.
     * @return false when the channel was already followed, which leaves it untouched.
     */
    suspend fun follow(
        channelId: String,
        title: String,
        avatarUrl: String?,
        followedAt: Instant? = null,
        highWater: Instant? = null,
    ): Boolean {
        val since = followedAt ?: clock()
        return dao.insertChannel(
            ChannelEntity(
                id = channelId,
                title = title,
                avatarUrl = avatarUrl,
                followedAt = since.toEpochMilli(),
                lastCheckedAt = null,
                feedHighWater = (highWater ?: since).toEpochMilli(),
            ),
        ) != -1L
    }

    /** Removes the channel and, through the cascade, every video it brought in. */
    suspend fun unfollow(channelId: String) = dao.deleteChannel(channelId)

    suspend fun markWatched(videoId: String) = dao.setWatchedAt(videoId, clock().toEpochMilli())

    /** The undo for [markWatched]. The video returns to the backlog where it was. */
    suspend fun markUnwatched(videoId: String) = dao.setWatchedAt(videoId, null)

    suspend fun saveResumePoint(
        videoId: String,
        seconds: Int,
    ) = dao.setResumeAt(videoId, seconds.coerceAtLeast(0))

    override suspend fun channels(): List<ChannelEntity> = dao.channels()

    override suspend fun knownVideoIds(channelId: String): Set<String> =
        dao.videoIds(channelId).toSet()

    override suspend fun pendingVideoIds(channelId: String): Set<String> =
        dao.pendingVideoIds(channelId).toSet()

    override suspend fun addVideos(videos: List<VideoEntity>): List<VideoEntity> {
        if (videos.isEmpty()) return emptyList()
        val rowIds = dao.insertVideos(videos)
        return videos.zip(rowIds).filter { (_, rowId) -> rowId != -1L }.map { it.first }
    }

    override suspend fun markChecked(
        channelId: String,
        title: String,
        checkedAt: Long,
        highWater: Long?,
    ) = dao.markChecked(channelId, title, checkedAt, highWater)

    override suspend fun dueVideos(now: Long): List<VideoWithChannel> = dao.dueVideos(now)

    override suspend fun setAvailableAt(
        videoId: String,
        availableAt: Long?,
        publishedAt: Long,
    ) = dao.setAvailableAt(videoId, availableAt, publishedAt)
}
