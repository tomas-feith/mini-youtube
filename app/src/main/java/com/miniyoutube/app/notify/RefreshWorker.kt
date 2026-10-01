package com.miniyoutube.app.notify

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.miniyoutube.app.MiniYouTubeApplication
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

/**
 * The hourly feed check. This is what makes notifications work without a server: Android
 * wakes it, it reads each followed channel's feed, and it posts a notification for anything
 * new.
 *
 * Android decides when - and whether - it actually runs, and may defer it in Doze or on a
 * low battery. Opening the app always checks too, so a deferred run delays a notification
 * but never loses a video.
 */
class RefreshWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container =
            (applicationContext as? MiniYouTubeApplication)?.container
                ?: return Result.failure()

        return try {
            val outcome = container.refresher.refresh()
            notifyNewVideos(applicationContext, outcome.newVideos)
            // A channel that failed is retried on the next hourly run anyway; asking for a
            // retry now would re-read every other channel for its sake.
            Result.success()
        } catch (e: CancellationException) {
            // WorkManager stopping us is not a failure; let it propagate.
            throw e
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            // Never throw out of a worker: Android backs off scheduling for a crashing one,
            // which quietly makes notifications stop. logcat is the only witness here.
            Log.w(TAG, "Periodic refresh failed (attempt $runAttemptCount)", e)
            if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
        }
    }

    companion object {
        private const val TAG = "RefreshWorker"

        const val WORK_NAME = "miniyoutube-periodic-refresh"

        /** WorkManager treats this as a floor, not a promise. */
        private const val INTERVAL_HOURS = 1L

        private const val MAX_ATTEMPTS = 3

        /**
         * Register the periodic check. Safe to call on every launch.
         *
         * UPDATE, not REPLACE: replacing the request on each start would reset its period,
         * so an app opened often would never sit long enough for the work to come due.
         * UPDATE keeps the schedule and only carries over changed constraints - a flipped
         * mobile-data setting, or an install that registered the work under older ones.
         *
         * @param onMobileData whether the check may run on a metered network. Off, it waits
         *   for Wi-Fi: a check reads every channel - and while the feed is down, a megabyte
         *   or more each.
         */
        fun schedule(
            context: Context,
            onMobileData: Boolean,
        ) {
            val request =
                PeriodicWorkRequestBuilder<RefreshWorker>(INTERVAL_HOURS, TimeUnit.HOURS)
                    .setConstraints(
                        Constraints
                            .Builder()
                            .setRequiredNetworkType(
                                if (onMobileData) NetworkType.CONNECTED else NetworkType.UNMETERED,
                            ).build(),
                    ).build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
        }
    }
}
