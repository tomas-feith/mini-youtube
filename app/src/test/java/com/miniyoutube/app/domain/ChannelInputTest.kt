package com.miniyoutube.app.domain

import com.miniyoutube.app.domain.ChannelRef.ById
import com.miniyoutube.app.domain.ChannelRef.ByPath
import com.miniyoutube.app.domain.ChannelRef.ByVideo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChannelInputTest {
    private val id = "UCBJycsmduvYEL83R_U4JriQ"

    @Test
    fun bareChannelId() = assertEquals(ById(id), parseChannelInput(id))

    @Test
    fun handleWithAndWithoutAt() {
        assertEquals(ByPath("/@mkbhd"), parseChannelInput("@mkbhd"))
        assertEquals(ByPath("/@mkbhd"), parseChannelInput("  mkbhd  "))
    }

    @Test
    fun channelUrls() {
        assertEquals(ById(id), parseChannelInput("https://www.youtube.com/channel/$id"))
        assertEquals(ById(id), parseChannelInput("https://m.youtube.com/channel/$id/videos"))
        assertEquals(ByPath("/@mkbhd"), parseChannelInput("https://youtube.com/@mkbhd?si=abc"))
        assertEquals(ByPath("/@mkbhd"), parseChannelInput("youtube.com/@mkbhd/videos"))
        assertEquals(ByPath("/c/mkbhd"), parseChannelInput("https://www.youtube.com/c/mkbhd"))
        assertEquals(
            ByPath("/user/marquesbrownlee"),
            parseChannelInput("https://www.youtube.com/user/marquesbrownlee"),
        )
    }

    @Test
    fun videoUrls() {
        val v = ByVideo("rayrrXot17M")
        assertEquals(v, parseChannelInput("https://youtu.be/rayrrXot17M?si=xyz"))
        assertEquals(v, parseChannelInput("https://www.youtube.com/watch?v=rayrrXot17M&t=42"))
        assertEquals(
            v,
            parseChannelInput("https://www.youtube.com/watch?feature=share&v=rayrrXot17M"),
        )
        assertEquals(v, parseChannelInput("https://youtube.com/shorts/rayrrXot17M"))
        assertEquals(v, parseChannelInput("https://www.youtube.com/live/rayrrXot17M"))
    }

    @Test
    fun aHandleThatLooksLikeADomainIsStillAHandle() {
        assertEquals(ByPath("/@youtube.fan"), parseChannelInput("@youtube.fan"))
    }

    @Test
    fun videoIdShape() {
        assertEquals(true, isVideoId("rayrrXot17M"))
        assertEquals(false, isVideoId("rayrrXot17"))
        assertEquals(false, isVideoId("../channels"))
        assertEquals(false, isVideoId("abc');alert"))
    }

    @Test
    fun linkInsideSharedText() {
        assertEquals(
            ByVideo("rayrrXot17M"),
            parseChannelInput("Watch this!\nhttps://youtu.be/rayrrXot17M"),
        )
    }

    @Test
    fun nonAsciiHandleStaysEncoded() {
        assertEquals(
            ByPath("/@%E3%83%86%E3%82%B9%E3%83%88"),
            parseChannelInput("https://www.youtube.com/@%E3%83%86%E3%82%B9%E3%83%88"),
        )
    }

    @Test
    fun rejectsWhatCannotNameAChannel() {
        assertNull(parseChannelInput(""))
        assertNull(parseChannelInput("   "))
        assertNull(parseChannelInput("https://example.com/@mkbhd"))
        assertNull(parseChannelInput("https://www.youtube.com/"))
        assertNull(parseChannelInput("https://www.youtube.com/feed/subscriptions"))
        assertNull(parseChannelInput("https://www.youtube.com/watch?v=short"))
        assertNull(parseChannelInput("two words"))
        assertNull(parseChannelInput("@"))
    }
}
