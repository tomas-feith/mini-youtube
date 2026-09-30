package com.miniyoutube.app.notify

import com.miniyoutube.app.data.NewVideo
import com.miniyoutube.app.data.VideoEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class NotificationTextTest {
    @Test
    fun namesTheChannelAndTheVideo() {
        val video =
            NewVideo(VideoEntity("abcdefghijk", "UCx", "A new video", 0, 0, null), "Some Channel")
        assertEquals(
            VideoNotificationText("Some Channel", "A new video"),
            videoNotificationText(video),
        )
    }

    @Test
    fun summaryCounts() {
        assertEquals("1 new video", summaryText(1))
        assertEquals("3 new videos", summaryText(3))
    }

    @Test
    fun aVideoNeverTakesTheSummaryId() {
        // Any id hashing to 0 would overwrite the summary; it is moved off it.
        val zeroHash = "" // "".hashCode() == 0
        assertNotEquals(0, notificationId(zeroHash))
        assertEquals("rayrrXot17M".hashCode(), notificationId("rayrrXot17M"))
    }
}
