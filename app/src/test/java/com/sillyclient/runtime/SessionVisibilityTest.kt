package com.sillyclient.runtime

import org.junit.Assert.assertEquals
import org.junit.Test

class SessionVisibilityTest {
    private val visibility = SessionVisibility()

    @Test
    fun anOrdinaryHideAndShowDoesNotTouchTheKeyboard() {
        assertEquals(listOf(SessionVisibility.Signal.SHOW), visibility.onWindowVisibility(true))
        assertEquals(listOf(SessionVisibility.Signal.HIDE), visibility.onWindowVisibility(false))
        assertEquals(listOf(SessionVisibility.Signal.SHOW), visibility.onWindowVisibility(true))
    }

    @Test
    fun aCoveredReplyStaysShownWhenTheWindowIsHidden() {
        visibility.onWindowVisibility(true)
        assertEquals(
            listOf(SessionVisibility.Signal.RESUME),
            visibility.onCoveredChanged(covered = true, windowVisible = false)
        )
        assertEquals(
            listOf(SessionVisibility.Signal.RESUME),
            visibility.onWindowVisibility(false)
        )
    }

    @Test
    fun returningWhileTheReplyIsStillArrivingRestoresTheKeyboardWithoutHidingThePage() {
        visibility.onWindowVisibility(true)
        visibility.onCoveredChanged(covered = true, windowVisible = false)
        visibility.onWindowVisibility(false)
        assertEquals(
            listOf(SessionVisibility.Signal.RESUME, SessionVisibility.Signal.RESTORE_INPUT),
            visibility.onWindowVisibility(true)
        )
        assertEquals(emptyList<SessionVisibility.Signal>(), visibility.onCoveredChanged(covered = false, windowVisible = true))
    }

    @Test
    fun finishingInTheBackgroundHidesThePageAndTheNextShowRestoresTheKeyboard() {
        visibility.onWindowVisibility(true)
        visibility.onCoveredChanged(covered = true, windowVisible = false)
        visibility.onWindowVisibility(false)
        assertEquals(
            listOf(SessionVisibility.Signal.HIDE),
            visibility.onCoveredChanged(covered = false, windowVisible = false)
        )
        assertEquals(
            listOf(
                SessionVisibility.Signal.SHOW,
                SessionVisibility.Signal.RESUME,
                SessionVisibility.Signal.RESTORE_INPUT
            ),
            visibility.onWindowVisibility(true)
        )
    }

    @Test
    fun aRepeatShowWhileTheWindowIsStillUpDoesNotConsumeTheKeyboardRestore() {
        visibility.onWindowVisibility(true)
        visibility.onCoveredChanged(covered = true, windowVisible = true)
        assertEquals(emptyList<SessionVisibility.Signal>(), visibility.onWindowVisibility(true))
        assertEquals(listOf(SessionVisibility.Signal.RESUME), visibility.onWindowVisibility(false))
        assertEquals(
            listOf(SessionVisibility.Signal.RESUME, SessionVisibility.Signal.RESTORE_INPUT),
            visibility.onWindowVisibility(true)
        )
    }

    @Test
    fun aSecondCoveredReplyRestoresTheKeyboardAgain() {
        visibility.onWindowVisibility(true)
        visibility.onCoveredChanged(covered = true, windowVisible = false)
        visibility.onWindowVisibility(false)
        visibility.onCoveredChanged(covered = false, windowVisible = false)
        visibility.onWindowVisibility(true)
        visibility.onCoveredChanged(covered = true, windowVisible = false)
        visibility.onWindowVisibility(false)
        assertEquals(
            listOf(SessionVisibility.Signal.RESUME, SessionVisibility.Signal.RESTORE_INPUT),
            visibility.onWindowVisibility(true)
        )
    }
}
