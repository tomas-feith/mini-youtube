package com.miniyoutube.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChannelPageTest {
    private val id = "UCBJycsmduvYEL83R_U4JriQ"

    @Test
    fun readsIdTitleAndAvatarFromTheHead() {
        val html =
            """
            <html><head>
            <link rel="alternate" type="application/rss+xml" title="RSS" href="https://www.youtube.com/feeds/videos.xml?channel_id=$id">
            <meta property="og:title" content="Tom &amp; Jerry&#39;s &quot;Show&quot;">
            <meta property="og:image" content="https://yt3.googleusercontent.com/abc=s900">
            </head></html>
            """.trimIndent()
        assertEquals(
            ChannelInfo(id, "Tom & Jerry's \"Show\"", "https://yt3.googleusercontent.com/abc=s900"),
            parseChannelPage(html),
        )
    }

    @Test
    fun fallsBackToTheCanonicalUrl() {
        val html = """<link rel="canonical" href="https://www.youtube.com/channel/$id">"""
        assertEquals(ChannelInfo(id, null, null), parseChannelPage(html))
    }

    @Test
    fun aPageWithNoChannelIsNull() {
        assertNull(parseChannelPage("<html><title>Consent</title></html>"))
    }

    @Test
    fun oEmbedAuthorPath() {
        val json =
            """{"title":"x","author_name":"MKBHD","author_url":"https://www.youtube.com/@mkbhd"}"""
        assertEquals("/@mkbhd", parseOEmbedAuthorPath(json))
        val escaped = """{"author_url":"https:\/\/www.youtube.com\/@mkbhd"}"""
        assertEquals("/@mkbhd", parseOEmbedAuthorPath(escaped))
        assertNull(parseOEmbedAuthorPath("""{"author_url":"https://www.youtube.com/"}"""))
        assertNull(parseOEmbedAuthorPath("{}"))
    }

    @Test
    fun unescapesNumericEntitiesAndLeavesJunkAlone() {
        assertEquals("é & 😀", unescapeHtml("&#233; &amp; &#x1F600;"))
        assertEquals("&#xZZ; &unknown;", unescapeHtml("&#xZZ; &unknown;"))
        assertEquals("&#99999999;", unescapeHtml("&#99999999;"))
    }
}
