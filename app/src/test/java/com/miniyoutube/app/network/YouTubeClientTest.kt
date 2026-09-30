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
            server.enqueue(MockResponse().setBody(xml))

            val feed = client().fetchFeed(id)

            assertEquals(3, feed.entries.size)
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
    fun aChannelIdStillResolvesWhenThePageSaysNothing() =
        runTest {
            server.enqueue(MockResponse().setBody("<html></html>"))
            val info = client().resolve(ChannelRef.ById(id))
            assertEquals(ChannelInfo(id, null, null), info)
        }
}
