package com.miniyoutube.app.ui.backlog

import com.miniyoutube.app.data.RefreshOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FailureMessageTest {
    private fun outcome(
        checked: Int,
        failed: Int,
        unreachable: Int = 0,
    ) = RefreshOutcome(emptyList(), checked, failed, unreachable)

    @Test
    fun saysNothingWhenEveryChannelWasChecked() {
        assertNull(BacklogViewModel.failureMessage(outcome(checked = 3, failed = 0)))
    }

    @Test
    fun countsThePartialFailures() {
        assertEquals(
            "2 channel(s) couldn't be checked",
            BacklogViewModel.failureMessage(outcome(checked = 1, failed = 2, unreachable = 2)),
        )
    }

    @Test
    fun blamesTheConnectionOnlyWhenNothingAnswered() {
        assertEquals(
            "Couldn't reach YouTube. Are you online?",
            BacklogViewModel.failureMessage(outcome(checked = 0, failed = 2, unreachable = 2)),
        )
        assertEquals(
            "YouTube isn't answering properly right now. Try again later.",
            BacklogViewModel.failureMessage(outcome(checked = 0, failed = 2, unreachable = 1)),
        )
    }
}
