package com.miniyoutube.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface VideoDao {
    @Query("SELECT * FROM channels ORDER BY title COLLATE NOCASE")
    fun observeChannels(): Flow<List<ChannelEntity>>

    @Query("SELECT * FROM channels")
    suspend fun channels(): List<ChannelEntity>

    /**
     * IGNORE, never REPLACE. SQLite implements REPLACE as delete-then-insert, and the
     * delete cascades: re-following a channel would silently wipe its backlog and, with
     * it, the record of which videos had already been watched.
     *
     * @return the row id, or -1 when the channel is already followed.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertChannel(channel: ChannelEntity): Long

    /** Records a successful check, and picks up a rename. Touches nothing the user owns. */
    @Query(
        "UPDATE channels SET title = :title, lastCheckedAt = :checkedAt, " +
            "feedHighWater = :highWater WHERE id = :id",
    )
    suspend fun markChecked(
        id: String,
        title: String,
        checkedAt: Long,
        highWater: Long?,
    )

    @Query("DELETE FROM channels WHERE id = :id")
    suspend fun deleteChannel(id: String)

    @Query(
        "SELECT v.id, v.channelId, c.title AS channelTitle, v.title, v.publishedAt, " +
            "v.watchedAt, v.resumeAtSeconds " +
            "FROM videos v JOIN channels c ON c.id = v.channelId " +
            "WHERE v.watchedAt IS NULL AND v.availableAt IS NULL ORDER BY v.publishedAt DESC",
    )
    fun observeBacklog(): Flow<List<VideoWithChannel>>

    @Query(
        "SELECT v.id, v.channelId, c.title AS channelTitle, v.title, v.publishedAt, " +
            "v.watchedAt, v.resumeAtSeconds " +
            "FROM videos v JOIN channels c ON c.id = v.channelId WHERE v.id = :id",
    )
    fun observeVideo(id: String): Flow<VideoWithChannel?>

    /** Premieres and streams whose start has come, waiting to be released. */
    @Query(
        "SELECT v.id, v.channelId, c.title AS channelTitle, v.title, v.publishedAt, " +
            "v.watchedAt, v.resumeAtSeconds " +
            "FROM videos v JOIN channels c ON c.id = v.channelId " +
            "WHERE v.availableAt IS NOT NULL AND v.availableAt <= :now",
    )
    suspend fun dueVideos(now: Long): List<VideoWithChannel>

    /** Null releases the video into the backlog; a time postpones it to then. */
    @Query(
        "UPDATE videos SET availableAt = :availableAt, publishedAt = :publishedAt WHERE id = :id",
    )
    suspend fun setAvailableAt(
        id: String,
        availableAt: Long?,
        publishedAt: Long,
    )

    @Query("SELECT id FROM videos WHERE channelId = :channelId")
    suspend fun videoIds(channelId: String): List<String>

    @Query("SELECT id FROM videos WHERE channelId = :channelId AND availableAt IS NOT NULL")
    suspend fun pendingVideoIds(channelId: String): List<String>

    /**
     * IGNORE so that two checks racing - the in-app refresh and the background worker -
     * cannot both claim a video. Only the one whose insert landed gets a row id back, and
     * only that one treats it as new.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertVideos(videos: List<VideoEntity>): List<Long>

    @Query("UPDATE videos SET watchedAt = :watchedAt WHERE id = :id")
    suspend fun setWatchedAt(
        id: String,
        watchedAt: Long?,
    )

    @Query("UPDATE videos SET resumeAtSeconds = :seconds WHERE id = :id")
    suspend fun setResumeAt(
        id: String,
        seconds: Int,
    )
}
