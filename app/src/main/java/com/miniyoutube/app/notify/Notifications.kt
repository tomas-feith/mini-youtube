package com.miniyoutube.app.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.miniyoutube.app.MainActivity
import com.miniyoutube.app.R
import com.miniyoutube.app.data.NewVideo

const val CHANNEL_ID = "new-videos"

private const val GROUP_KEY = "com.miniyoutube.app.NEW_VIDEOS"

/** The group summary's id. Video notifications use the video id's hash, never this. */
private const val SUMMARY_ID = 0

/** Title and body of one video's notification, kept pure so the wording can be tested. */
data class VideoNotificationText(
    val title: String,
    val body: String,
)

fun videoNotificationText(video: NewVideo): VideoNotificationText =
    VideoNotificationText(title = video.channelTitle, body = video.title)

/** The summary line shown when the group is collapsed. */
fun summaryText(count: Int): String = if (count == 1) "1 new video" else "$count new videos"

/**
 * One notification per video, bundled under a summary.
 *
 * Per video rather than one digest, because each can then open its own video when tapped,
 * and can be cleared the moment that video is marked watched in the app - a digest would
 * keep naming videos the user has already dealt with.
 *
 * The id is derived from the video id so that clearing it later needs nothing stored.
 */
fun notificationId(videoId: String): Int =
    videoId.hashCode().let {
        if (it ==
            SUMMARY_ID
        ) {
            1
        } else {
            it
        }
    }

/**
 * Create the channel. Safe to call repeatedly: an existing channel is left alone, including
 * any importance or sound the user changed on it.
 */
fun ensureChannel(context: Context) {
    val channel =
        NotificationChannel(
            CHANNEL_ID,
            "New videos",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "A channel you follow published a video."
        }
    NotificationManagerCompat.from(context).createNotificationChannel(channel)
}

/** Whether the app may post. Below API 33 the permission is granted at install time. */
fun canPostNotifications(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED

/**
 * Post one notification per new video, or nothing without permission.
 *
 * Silently doing nothing is right here: this runs from a background worker with no screen
 * to explain a refusal on, and the videos are in the backlog regardless.
 */
fun notifyNewVideos(
    context: Context,
    videos: List<NewVideo>,
) {
    if (videos.isEmpty()) return
    // Spelled out rather than delegated to canPostNotifications: Lint verifies the guard
    // only when it can see it in the same function as the notify() call.
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
        PackageManager.PERMISSION_GRANTED
    ) {
        return
    }
    ensureChannel(context)
    val manager = NotificationManagerCompat.from(context)

    videos.forEach { video ->
        val text = videoNotificationText(video)
        val notification =
            NotificationCompat
                .Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(text.title)
                .setContentText(text.body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text.body))
                .setWhen(video.publishedAt)
                .setShowWhen(true)
                .setGroup(GROUP_KEY)
                .setAutoCancel(true)
                .setContentIntent(openVideoIntent(context, video.videoId))
                .build()
        manager.notify(notificationId(video.videoId), notification)
    }

    val showing =
        manager.activeNotifications.count {
            it.id != SUMMARY_ID && it.notification.group == GROUP_KEY
        }
    val summary =
        NotificationCompat
            .Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(summaryText(showing.coerceAtLeast(videos.size)))
            .setGroup(GROUP_KEY)
            .setGroupSummary(true)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent(context))
            .build()
    manager.notify(SUMMARY_ID, summary)
}

/**
 * Clear a video's notification once it has been dealt with in the app, and the summary
 * with it if that was the last one - a summary over nothing is an empty shade entry.
 */
fun cancelVideoNotification(
    context: Context,
    videoId: String,
) {
    val manager = NotificationManagerCompat.from(context)
    manager.cancel(notificationId(videoId))
    val remaining =
        manager.activeNotifications.count {
            it.id != SUMMARY_ID && it.notification.group == GROUP_KEY
        }
    if (remaining == 0) manager.cancel(SUMMARY_ID)
}

private fun openVideoIntent(
    context: Context,
    videoId: String,
): PendingIntent {
    val intent =
        Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(MainActivity.EXTRA_VIDEO_ID, videoId)
        }
    return PendingIntent.getActivity(
        context,
        // Distinct request codes, or every video's intent would collapse into one and all
        // the notifications would open the same video.
        notificationId(videoId),
        intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}

private fun openAppIntent(context: Context): PendingIntent {
    val intent =
        Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
    return PendingIntent.getActivity(
        context,
        SUMMARY_ID,
        intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}
