package com.miniyoutube.app.network

import com.miniyoutube.app.domain.ChannelInfo
import com.miniyoutube.app.domain.ChannelRef
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.time.Instant

class YouTubeClientTest {
    private val server = MockWebServer()
    private val id = "UCBJycsmduvYEL83R_U4JriQ"
    private val page =
        """
        <link rel="alternate" type="application/rss+xml" href="https://www.youtube.com/feeds/videos.xml?channel_id=$id">
        <meta property="og:title" content="Marques Brownlee">
        <meta property="og:image" content="https://yt3.example/a.jpg">
        """.trimIndent()

    @Before
    fun start() = server.start()

    @After
    fun stop() = server.shutdown()

    private fun client() =
        YouTubeClient(
            OkHttpClient(),
            baseUrl = server.url("").toString().trimEnd('/'),
        )

    @Test
    fun fetchesAndParsesAFeedWithTheConsentCookie() =
        runTest {
            val xml =
                requireNotNull(
                    javaClass.classLoader?.getResource("feed-sample.xml"),
                ).readText()
            server.enqueue(
                MockResponse().setBody(xml).setHeader("Date", "Wed, 30 Sep 2026 10:15:00 GMT"),
            )

            val feed = client().fetchFeed(id)

            assertEquals(3, feed.entries.size)
            // YouTube's clock, from the response, for following.
            assertEquals(Instant.parse("2026-09-30T10:15:00Z"), feed.fetchedAt)
            val request = server.takeRequest()
            assertEquals("/feeds/videos.xml?channel_id=$id", request.path)
            assertEquals("SOCS=CAI", request.getHeader("Cookie"))
            assertTrue(request.getHeader("User-Agent")!!.startsWith("Mozilla/5.0"))
        }

    @Test
    fun anHtmlBodyWhereAFeedShouldBeIsAnError() =
        runTest {
            server.enqueue(MockResponse().setBody("<!DOCTYPE html><html><p>oops"))
            try {
                client().fetchFeed(id)
                fail("expected YouTubeException")
            } catch (e: YouTubeException) {
                assertTrue(e.message!!.contains("not readable"))
            }
        }

    @Test
    fun resolvesAHandle() =
        runTest {
            server.enqueue(MockResponse().setBody(page))
            val info = client().resolve(ChannelRef.ByPath("/@mkbhd"))
            assertEquals(ChannelInfo(id, "Marques Brownlee", "https://yt3.example/a.jpg"), info)
            assertEquals("/@mkbhd", server.takeRequest().path)
        }

    @Test
    fun aMissingHandleSaysSo() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(404))
            try {
                client().resolve(ChannelRef.ByPath("/@nobody"))
                fail("expected YouTubeException")
            } catch (e: YouTubeException) {
                assertEquals(404, e.code)
                assertEquals("No channel at /@nobody", e.message)
            }
        }

    @Test
    fun resolvesAVideoThroughOEmbed() =
        runTest {
            server.enqueue(
                MockResponse().setBody("""{"author_url":"https://www.youtube.com/@mkbhd"}"""),
            )
            server.enqueue(MockResponse().setBody(page))

            val info = client().resolve(ChannelRef.ByVideo("rayrrXot17M"))

            assertEquals(id, info.id)
            val oembed = server.takeRequest().path!!
            assertTrue(
                oembed,
                oembed.startsWith(
                    "/oembed?url=https%3A%2F%2Fwww.youtube.com%2Fwatch%3Fv%3DrayrrXot17M",
                ),
            )
            assertEquals("/@mkbhd", server.takeRequest().path)
        }

    @Test
    fun aPrivateVideoIsReportedAsSuch() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(401))
            try {
                client().resolve(ChannelRef.ByVideo("rayrrXot17M"))
                fail("expected YouTubeException")
            } catch (e: YouTubeException) {
                assertEquals("That video is private or doesn't exist", e.message)
            }
        }

    @Test
    fun aServerErrorOnOEmbedIsNotBlamedOnTheVideo() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(503))
            try {
                client().resolve(ChannelRef.ByVideo("rayrrXot17M"))
                fail("expected YouTubeException")
            } catch (e: YouTubeException) {
                assertEquals(503, e.code)
                assertEquals("YouTube answered 503", e.message)
            }
        }

    @Test
    fun readsAWatchPage() =
        runTest {
            server.enqueue(
                MockResponse().setBody(
                    """{"videoDetails":{"videoId":"7ZiUbT1-djA",""" +
                        """"channelId":"$id","isUpcoming":true},""" +
                        """"microformat":{"startTimestamp":"2026-10-01T13:20:00+00:00"}}""",
                ),
            )
            val info = client().fetchWatchInfo("7ZiUbT1-djA")
            assertTrue(info.upcoming)
            assertEquals(Instant.parse("2026-10-01T13:20:00Z"), info.startsAt)
            assertEquals("/watch?v=7ZiUbT1-djA", server.takeRequest().path)
        }

    @Test
    fun aWatchPageThatIsNotAboutTheVideoIsAnError() =
        runTest {
            server.enqueue(MockResponse().setBody("<html>consent</html>"))
            try {
                client().fetchWatchInfo("7ZiUbT1-djA")
                fail("expected YouTubeException")
            } catch (e: YouTubeException) {
                assertTrue(e.message!!.contains("not readable"))
            }
        }

    @Test
    fun readsTheVideosTab() =
        runTest {
            server.enqueue(
                MockResponse().setBody(
                    """{"contentId":"rayrrXot17M"},{"contentId":"DkUuOr21v4s"}""",
                ),
            )
            assertEquals(listOf("rayrrXot17M", "DkUuOr21v4s"), client().fetchChannelVideoIds(id))
            assertEquals("/channel/$id/videos", server.takeRequest().path)
        }

    @Test
    fun theVideosTabCarriesYouTubesClockForFollowingWithoutAFeed() =
        runTest {
            server.enqueue(
                MockResponse()
                    .setBody("""{"contentId":"rayrrXot17M"}""")
                    .setHeader("Date", "Thu, 01 Oct 2026 04:54:06 GMT"),
            )
            val uploads = client().fetchUploads(id)
            assertEquals(listOf("rayrrXot17M"), uploads.videoIds)
            assertEquals(Instant.parse("2026-10-01T04:54:06Z"), uploads.fetchedAt)
        }

    @Test
    fun aChannelIdStillResolvesWhenThePageSaysNothing() =
        runTest {
            server.enqueue(MockResponse().setBody("<html></html>"))
            val info = client().resolve(ChannelRef.ById(id))
            assertEquals(ChannelInfo(id, null, null), info)
        }
}
