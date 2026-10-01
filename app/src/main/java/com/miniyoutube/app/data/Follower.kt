package com.miniyoutube.app.data

import android.util.Log
import com.miniyoutube.app.domain.parseChannelInput
import com.miniyoutube.app.network.YouTubeClient
import com.miniyoutube.app.network.YouTubeException
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
            val feed =
                try {
                    client.fetchFeed(info.id)
                } catch (e: YouTubeException) {
                    // The feed endpoint has spells of refusing every channel. Checks fall
                    // back to the videos tab then, so following can too; it still gives
                    // YouTube's clock, and the high-water mark defaults to the follow.
                    Log.w(TAG, "Feed for ${info.id} refused; following from its videos tab", e)
                    null
                }
            val title = info.title ?: feed?.channelTitle ?: info.id
            val followed =
                library.follow(
                    channelId = info.id,
                    title = title,
                    avatarUrl = info.avatarUrl,
                    // YouTube's clock, not the phone's: this is compared against publish
                    // times YouTube stamped, and a phone clock running ahead would
                    // otherwise silently drop every upload made in the difference.
                    followedAt = feed?.fetchedAt ?: client.fetchUploads(info.id).fetchedAt,
                    highWater = feed?.entries?.maxOfOrNull { it.publishedAt },
                )
            if (followed) {
                FollowResult.Followed(title)
            } else {
                FollowResult.AlreadyFollowing(title)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: YouTubeException) {
            // Written for the screen: "No channel at /@x", "That video is private...".
            FollowResult.Failed(e.message ?: "YouTube refused that")
        } catch (e: IOException) {
            // No answer at all. The system's wording ("Unable to resolve host ...") says
            // what failed, not what to do about it.
            Log.w(TAG, "Following failed", e)
            FollowResult.Failed("Couldn't reach YouTube. Are you online?")
        } catch (e: IllegalArgumentException) {
            // OkHttp's verdict on a URL it cannot build, which a malformed path produces.
            FollowResult.Failed(e.message ?: "That link couldn't be used")
        }
    }

    private companion object {
        const val TAG = "Follower"
    }
}
