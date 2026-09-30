package com.miniyoutube.app.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A followed channel.
 *
 * Times are epoch milliseconds rather than ISO strings. Both sort, but only one sorts
 * reliably: `Instant.toString()` drops a zero fraction, so "...:35Z" and "...:35.120Z"
 * compare as text in the wrong order.
 */
@Entity(tableName = "channels")
data class ChannelEntity(
    /** YouTube's `UC...` id. The feed, the page and the player all key on it. */
    @PrimaryKey val id: String,
    val title: String,
    val avatarUrl: String?,
    /**
     * When the user followed the channel, by YouTube's clock (see `Feed.fetchedAt`).
     * Uploads older than this never enter the backlog; see `newArrivals`.
     */
    val followedAt: Long,
    /** The last time the channel's feed was read successfully, or null if never. */
    val lastCheckedAt: Long?,
    /**
     * The newest publish time seen in the channel's feed, Shorts included. A later feed
     * whose oldest entry is newer still has rolled past videos this app never saw; see
     * `feedOverflowed`. Null until the first check after the upgrade that added it.
     */
    val feedHighWater: Long? = null,
)

/**
 * A video that has entered the backlog, and stays here after it leaves.
 *
 * A watched video is not deleted: its row is what tells the next feed check that this id
 * is already known, and without it every video still in the feed - the last fifteen
 * uploads - would come straight back the moment it was marked watched.
 *
 * Deleting the channel deletes its videos (the cascade), since an unfollowed channel's
 * backlog is not something the user asked to keep.
 */
@Entity(
    tableName = "videos",
    foreignKeys = [
        ForeignKey(
            entity = ChannelEntity::class,
            parentColumns = ["id"],
            childColumns = ["channelId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("channelId"), Index("watchedAt")],
)
data class VideoEntity(
    @PrimaryKey val id: String,
    val channelId: String,
    val title: String,
    val publishedAt: Long,
    /** When the feed check found it. Distinct from [publishedAt] when a check ran late. */
    val addedAt: Long,
    /** Null while in the backlog; set when the user marks it watched. */
    val watchedAt: Long?,
    /** Where playback was left, so reopening a long video resumes rather than restarts. */
    @ColumnInfo(defaultValue = "0") val resumeAtSeconds: Int = 0,
    /**
     * For a premiere or stream found before it started: when it starts. Such a row is
     * known - so the next check does not fetch its page again - but kept out of the backlog
     * until a refresh after this time releases it, sets this back to null, and announces
     * it. Null for everything already watchable.
     */
    val availableAt: Long? = null,
)

/** A video joined with the name of its channel, which is how every screen shows it. */
data class VideoWithChannel(
    val id: String,
    val channelId: String,
    val channelTitle: String,
    val title: String,
    val publishedAt: Long,
    val watchedAt: Long?,
    val resumeAtSeconds: Int,
)
