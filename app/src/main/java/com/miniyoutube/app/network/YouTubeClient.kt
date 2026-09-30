package com.miniyoutube.app.network

import com.miniyoutube.app.domain.ChannelInfo
import com.miniyoutube.app.domain.ChannelRef
import com.miniyoutube.app.domain.Feed
import com.miniyoutube.app.domain.parseChannelPage
import com.miniyoutube.app.domain.parseFeed
import com.miniyoutube.app.domain.parseOEmbedAuthorPath
import com.miniyoutube.app.domain.watchUrl
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.xml.sax.SAXException
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/** A request that reached YouTube and was refused, or answered with something unusable. */
class YouTubeException(
    message: String,
    val code: Int? = null,
    cause: Throwable? = null,
) : IOException(message, cause)

/**
 * Everything this app asks of YouTube, none of which needs an API key or an account.
 *
 * - The channel **feed**, a public Atom document of the fifteen most recent uploads. It is
 *   the only thing read on a schedule.
 * - A channel **page**, read once when following, for the channel id behind a handle.
 * - **oEmbed** for a shared video link, which names the uploader.
 *
 * The Data API would say more - durations, live status - but it needs a key the user would
 * have to create in a Google Cloud project, and a quota. The feed says enough.
 *
 * @param baseUrl overridable so tests can point it at a local server.
 * @param io the dispatcher blocking calls run on, injectable for the same reason.
 */
class YouTubeClient(
    private val http: OkHttpClient,
    private val baseUrl: String = "https://www.youtube.com",
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun fetchFeed(channelId: String): Feed {
        val body = get("$baseUrl/feeds/videos.xml?channel_id=$channelId")
        return try {
            parseFeed(body)
        } catch (e: SAXException) {
            throw YouTubeException(
                "The feed for $channelId was not readable: ${e.message}",
                cause = e,
            )
        }
    }

    /**
     * Turns what the user entered into a channel id, name and avatar.
     *
     * @throws YouTubeException with a message fit to show on screen.
     */
    suspend fun resolve(ref: ChannelRef): ChannelInfo =
        when (ref) {
            is ChannelRef.ById -> {
                // The page only adds the name and avatar here; the id is already known,
                // so a page that fails to parse still yields a followable channel.
                runCatchingPage("/channel/${ref.channelId}")
                    ?: ChannelInfo(ref.channelId, null, null)
            }

            is ChannelRef.ByPath -> {
                runCatchingPage(ref.path)
                    ?: throw YouTubeException("Couldn't find a channel at ${ref.path}")
            }

            is ChannelRef.ByVideo -> {
                val url = URLEncoder.encode(watchUrl(ref.videoId), "UTF-8")
                val json =
                    try {
                        get("$baseUrl/oembed?url=$url&format=json")
                    } catch (e: YouTubeException) {
                        // oEmbed answers 401/403 for a private video and 400/404 for one
                        // that does not exist. Anything else is YouTube having a bad moment,
                        // and saying "private" then would send the user looking for the
                        // wrong problem.
                        if (e.code in VIDEO_UNAVAILABLE_CODES) {
                            throw YouTubeException(
                                "That video is private or doesn't exist",
                                e.code,
                                e,
                            )
                        }
                        throw e
                    }
                val path =
                    parseOEmbedAuthorPath(json)
                        ?: throw YouTubeException("Couldn't tell who uploaded that video")
                runCatchingPage(path)
                    ?: throw YouTubeException("Couldn't find the channel that uploaded it")
            }
        }

    /** The parsed page, or null for a page that loads but names no channel. 404 throws. */
    private suspend fun runCatchingPage(path: String): ChannelInfo? {
        val html =
            try {
                get("$baseUrl$path")
            } catch (e: YouTubeException) {
                if (e.code == HTTP_NOT_FOUND) {
                    throw YouTubeException("No channel at $path", e.code, e)
                }
                throw e
            }
        return parseChannelPage(html)
    }

    private suspend fun get(url: String): String =
        withContext(io) {
            val request =
                Request
                    .Builder()
                    .url(url)
                    .header("User-Agent", USER_AGENT)
                    .header("Accept-Language", "en")
                    // Declines the EU cookie consent interstitial up front. Without it a
                    // request from Europe can be redirected to consent.youtube.com, whose
                    // page has no channel id in it.
                    .header("Cookie", "SOCS=CAI")
                    .build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw YouTubeException("YouTube answered ${response.code}", response.code)
                }
                response.body?.string() ?: throw YouTubeException("Empty response")
            }
        }

    companion object {
        private const val HTTP_NOT_FOUND = 404

        private val VIDEO_UNAVAILABLE_CODES = setOf(400, 401, 403, 404)

        /**
         * A desktop browser, so the page served is the full `www` one whose head carries
         * the feed link. An OkHttp default agent is served a stripped page.
         */
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/128.0 Safari/537.36"

        private const val TIMEOUT_SECONDS = 20L

        fun defaultClient(): OkHttpClient =
            OkHttpClient
                .Builder()
                .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .build()
    }
}
