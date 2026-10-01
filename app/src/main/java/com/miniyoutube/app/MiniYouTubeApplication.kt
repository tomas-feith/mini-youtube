package com.miniyoutube.app

import android.app.Application
import android.content.Context
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import com.miniyoutube.app.data.FeedRefresher
import com.miniyoutube.app.data.Follower
import com.miniyoutube.app.data.Library
import com.miniyoutube.app.data.VideoDatabase
import com.miniyoutube.app.data.VideoSource
import com.miniyoutube.app.domain.Feed
import com.miniyoutube.app.domain.WatchInfo
import com.miniyoutube.app.network.YouTubeClient
import com.miniyoutube.app.notify.RefreshWorker
import com.miniyoutube.app.notify.ensureChannel
import okhttp3.OkHttpClient

/**
 * Manual dependency wiring.
 *
 * Held by the Application because everything here must be reachable from the background
 * worker as well as from the activity, and the worker can start the process with no
 * activity ever existing. Sharing one [refresher] between them is what lets its mutex
 * keep the two from checking at the same time.
 */
class AppContainer(
    context: Context,
) {
    /** Held as the application context so nothing here can pin an activity. */
    val appContext: Context = context.applicationContext

    val http: OkHttpClient by lazy { YouTubeClient.defaultClient() }

    val youtube: YouTubeClient by lazy { YouTubeClient(http) }

    val library: Library by lazy { Library(VideoDatabase.get(appContext).videoDao()) }

    val refresher: FeedRefresher by lazy {
        FeedRefresher(source = YouTubeSource(youtube), store = library)
    }

    val follower: Follower by lazy { Follower(youtube, library) }
}

/** The refresher's view of the network client. */
private class YouTubeSource(
    private val client: YouTubeClient,
) : VideoSource {
    override suspend fun feed(channelId: String): Feed = client.fetchFeed(channelId)

    override suspend fun watchInfo(videoId: String): WatchInfo = client.fetchWatchInfo(videoId)

    override suspend fun channelVideoIds(channelId: String): List<String> =
        client.fetchChannelVideoIds(channelId)

    override suspend fun channelStreamIds(channelId: String): List<String> =
        client.fetchChannelStreamIds(channelId)
}

class MiniYouTubeApplication :
    Application(),
    SingletonImageLoader.Factory {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)

        // Created up front so the channel shows in system settings from the first launch,
        // and can be tuned before anything is posted to it.
        ensureChannel(this)
        RefreshWorker.schedule(this)
    }

    /** Thumbnails share the app's OkHttp client: one connection pool rather than two. */
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader
            .Builder(context)
            .components {
                add(OkHttpNetworkFetcherFactory(callFactory = { container.http }))
            }.build()
}
