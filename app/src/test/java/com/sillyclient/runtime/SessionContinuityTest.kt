package com.sillyclient.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionContinuityTest {
    private var now = 0L
    private val continuity = SessionContinuity(
        clock = { now },
        pulseTtlNanos = 30_000_000_000L,
        settleNanos = 15_000_000_000L,
    )

    @Test
    fun anIdleSessionDoesNotStayAwakeWhenTheScreenIsCovered() {
        continuity.onHostVisible(false, now)
        assertFalse(continuity.shouldHoldProcess(now))
        assertFalse(continuity.shouldPinWebView(now))
        assertNull(continuity.millisUntilChange(now))
    }

    @Test
    fun aReplyInTheForegroundKeepsTheProcessAwakeWithoutPinningThePage() {
        continuity.onReceiving(true, now)
        assertTrue(continuity.shouldHoldProcess(now))
        assertFalse(continuity.shouldPinWebView(now))
        assertNull(continuity.millisUntilChange(now))
    }

    @Test
    fun lockingOrSwitchingAwayKeepsReceivingAndWritingTheReply() {
        continuity.onReceiving(true, now)
        continuity.onHostVisible(false, now)
        assertTrue(continuity.shouldHoldProcess(now))
        assertTrue(continuity.shouldPinWebView(now))
        assertEquals(30_000L, continuity.millisUntilChange(now))
    }

    @Test
    fun pulsesExtendTheCoveredReplyAndAMissedPulseLetsItSleep() {
        continuity.onReceiving(true, now)
        continuity.onHostVisible(false, now)
        now += 20_000_000_000L
        continuity.onPulse(now)
        assertTrue(continuity.shouldHoldProcess(now))
        now += 30_000_000_000L
        assertFalse(continuity.shouldHoldProcess(now))
        assertFalse(continuity.shouldPinWebView(now))
    }

    @Test
    fun returningToTheAppUnpinsThePageWhileTheReplyContinues() {
        continuity.onReceiving(true, now)
        continuity.onHostVisible(false, now)
        continuity.onHostVisible(true, now + 1_000_000_000L)
        assertTrue(continuity.shouldHoldProcess(now))
        assertFalse(continuity.shouldPinWebView(now))
    }

    @Test
    fun finishingInTheBackgroundSettlesLongEnoughToSaveTheReply() {
        continuity.onReceiving(true, now)
        continuity.onHostVisible(false, now)
        continuity.onReceiving(false, now + 5_000_000_000L)
        assertTrue(continuity.shouldHoldProcess(now + 5_000_000_000L))
        assertTrue(continuity.shouldPinWebView(now + 19_000_000_000L))
        assertFalse(continuity.shouldHoldProcess(now + 20_000_000_000L))
    }

    @Test
    fun finishingInTheForegroundReleasesImmediately() {
        continuity.onReceiving(true, now)
        continuity.onReceiving(false, now)
        assertFalse(continuity.shouldHoldProcess(now))
        assertNull(continuity.millisUntilChange(now))
    }

    @Test
    fun aNewReplyDuringSettleKeepsTheCoveredSessionAwake() {
        continuity.onReceiving(true, now)
        continuity.onHostVisible(false, now)
        continuity.onReceiving(false, now)
        continuity.onReceiving(true, now + 1_000_000_000L)
        now += 20_000_000_000L
        assertTrue(continuity.shouldHoldProcess(now))
        assertTrue(continuity.shouldPinWebView(now))
    }

    @Test
    fun closingThePageDropsACoveredReply() {
        continuity.onReceiving(true, now)
        continuity.onHostVisible(false, now)
        continuity.onSessionClosed()
        assertFalse(continuity.shouldHoldProcess(now))
        assertFalse(continuity.shouldPinWebView(now))
    }

    @Test
    fun repeatedReceivingReportsRefreshTheCoveredDeadline() {
        continuity.onReceiving(true, now)
        continuity.onHostVisible(false, now)
        now += 25_000_000_000L
        continuity.onReceiving(true, now)
        assertTrue(continuity.shouldHoldProcess(now + 20_000_000_000L))
        assertFalse(continuity.shouldHoldProcess(now + 31_000_000_000L))
    }
}
