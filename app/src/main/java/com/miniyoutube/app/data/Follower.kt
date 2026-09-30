package com.miniyoutube.app.data

import com.miniyoutube.app.domain.parseChannelInput
import com.miniyoutube.app.network.YouTubeClient
import kotlinx.coroutines.CancellationException
import java.io.IOException

sealed interface FollowResult {
    data class Followed(
        val title: String,
    ) : FollowResult

    data class AlreadyFollowing(
        val title: String,
    ) : FollowResult

    data class Failed(
        val message: String,
    ) : FollowResult
}

/** Turns a pasted or shared link into a followed channel. */
class Follower(
    private val client: YouTubeClient,
    private val library: Library,
) {
    suspend fun follow(input: String): FollowResult {
        val ref =
            parseChannelInput(input)
                ?: return FollowResult.Failed(
                    "That doesn't look like a YouTube channel, video link or @handle",
                )
        return try {
            val info = client.resolve(ref)
            // Read the feed before committing. It confirms the channel can actually be
            // followed - the page and the feed are separate endpoints - and names it when
            // the page did not.
            val feed = client.fetchFeed(info.id)
            val title = info.title ?: feed.channelTitle ?: info.id
            if (library.follow(info.id, title, info.avatarUrl)) {
                FollowResult.Followed(title)
            } else {
                FollowResult.AlreadyFollowing(title)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            FollowResult.Failed(e.message ?: "Couldn't reach YouTube")
        } catch (e: IllegalArgumentException) {
            // OkHttp's verdict on a URL it cannot build, which a malformed path produces.
            FollowResult.Failed(e.message ?: "That link couldn't be used")
        }
    }
}
