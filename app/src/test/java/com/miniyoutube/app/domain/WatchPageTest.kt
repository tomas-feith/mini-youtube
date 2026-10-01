package com.miniyoutube.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class WatchPageTest {
    private val channel = "UCLA_DiR1FfKNvjuUpBHmylQ"

    /** Trimmed to the shape of a real page: head meta, player state, then the sidebar. */
    private fun page(
        id: String,
        upcoming: Boolean,
        start: String?,
        sidebar: String = "",
    ) = """
        <meta name="title" content="Launch &amp; Landing {live}">
        <script>var ytInitialPlayerResponse = {"playabilityStatus":{"status":"OK"},
        "videoDetails":{"videoId":"$id","title":"Launch { brace","channelId":"$channel",
        ${if (upcoming) "\"isUpcoming\":true," else ""}"isLiveContent":true},
        "microformat":{"playerMicroformatRenderer":{"publishDate":"2026-09-28T15:25:03-07:00"
        ${if (start != null) ",\"liveBroadcastDetails\":{\"startTimestamp\":\"$start\"}" else ""}}}};
        </script>
        <script>var ytInitialData = {"related":[$sidebar]};</script>
        """.trimIndent()

    @Test
    fun readsAnUpcomingStream() {
        val info =
            parseWatchPage(
                "7ZiUbT1-djA",
                page("7ZiUbT1-djA", true, "2026-10-01T13:20:00+00:00"),
            )!!
        assertTrue(info.upcoming)
        assertEquals(Instant.parse("2026-10-01T13:20:00Z"), info.startsAt)
        assertEquals(Instant.parse("2026-09-28T22:25:03Z"), info.publishedAt)
        assertEquals(channel, info.channelId)
        assertEquals("Launch & Landing {live}", info.title)
    }

    @Test
    fun anOrdinaryVideoIsNotUpcomingEvenWithUpcomingSuggestionsBesideIt() {
        val sidebar =
            """{"videoDetails":{"videoId":"other","isUpcoming":true},""" +
                """"microformat":{"startTimestamp":"2030-01-01T00:00:00+00:00"}}"""
        val info = parseWatchPage("rayrrXot17M", page("rayrrXot17M", false, null, sidebar))!!
        assertFalse(info.upcoming)
        assertNull(info.startsAt)
    }

    @Test
    fun aPageForAnotherVideoOrNoVideoIsNull() {
        assertNull(parseWatchPage("rayrrXot17M", page("DkUuOr21v4s", false, null)))
        assertNull(parseWatchPage("rayrrXot17M", "<html>consent</html>"))
    }

    @Test
    fun aTabYouTubeSwappedForAnotherYieldsNothing() {
        fun tabPage(selectedUrl: String) =
            """{"tabRenderer":{"endpoint":{"commandMetadata":{"webCommandMetadata":""" +
                """{"url":"/@x/videos"}}},"title":"Videos"}},""" +
                """{"tabRenderer":{"endpoint":{"commandMetadata":{"webCommandMetadata":""" +
                """{"url":"$selectedUrl"}}},"title":"Whatever","selected":true,""" +
                """{"contentId":"rayrrXot17M"}"""

        assertEquals(listOf("rayrrXot17M"), parseChannelTabIds(tabPage("/@x/streams"), "streams"))
        // A channel that never streamed answers /streams with its Home tab.
        assertEquals(emptyList<String>(), parseChannelTabIds(tabPage("/@x/featured"), "streams"))
        assertEquals(emptyList<String>(), parseChannelTabIds("{}", "videos"))
    }

    @Test
    fun channelVideoIdsAreInOrderWithoutRepeatsAndSkipPlaylists() {
        val html =
            """{"contentId":"rayrrXot17M"},{"contentId":"rayrrXot17M"},""" +
                """{"contentId":"PLabcdefghijklmnopqrstuvwxyz012345"},{"contentId":"DkUuOr21v4s"}"""
        assertEquals(listOf("rayrrXot17M", "DkUuOr21v4s"), parseChannelVideoIds(html))
    }
}
