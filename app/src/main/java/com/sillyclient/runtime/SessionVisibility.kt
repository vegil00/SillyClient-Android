package com.sillyclient.runtime

/**
 * What Chromium is told about a tavern page that must keep reading a reply
 * after the screen is locked or the app is switched away.
 *
 * A covered page stays shown, because a hidden page stops the reply. Chromium
 * only rebuilds the input connection when it actually sees the window again.
 * Leaving the message box focused the whole time means the next tap does not
 * ask for the keyboard, so the return to a real visible window restores input
 * once.
 */
internal class SessionVisibility {
    enum class Signal { SHOW, HIDE, RESUME, RESTORE_INPUT }

    private var covered = false
    private var toldVisible = false
    private var restoreOnNextShow = false
    private var hiddenWhileCovered = false

    fun onCoveredChanged(covered: Boolean, windowVisible: Boolean): List<Signal> {
        if (this.covered == covered) return emptyList()
        this.covered = covered
        if (covered) {
            restoreOnNextShow = true
            return showAndResume()
        }
        if (!windowVisible) return hideIfTold()
        return restoreIfNeeded()
    }

    fun onWindowVisibility(visible: Boolean): List<Signal> {
        if (!visible) {
            if (covered) {
                restoreOnNextShow = true
                hiddenWhileCovered = true
                return showAndResume()
            }
            return hideIfTold()
        }
        // A repeat show while the window is still up must not consume the
        // restore. The keyboard is rebuilt on the show that follows a real hide.
        if (covered && !hiddenWhileCovered) return showIfNeeded()
        if (covered || restoreOnNextShow) return restoreIfNeeded()
        return showIfNeeded()
    }

    private fun showAndResume(): List<Signal> {
        val signals = ArrayList<Signal>(2)
        if (!toldVisible) {
            toldVisible = true
            signals.add(Signal.SHOW)
        }
        signals.add(Signal.RESUME)
        return signals
    }

    private fun showIfNeeded(): List<Signal> {
        if (toldVisible) return emptyList()
        toldVisible = true
        return listOf(Signal.SHOW)
    }

    private fun hideIfTold(): List<Signal> {
        if (!toldVisible) return emptyList()
        toldVisible = false
        return listOf(Signal.HIDE)
    }

    private fun restoreIfNeeded(): List<Signal> {
        val restore = restoreOnNextShow
        restoreOnNextShow = false
        hiddenWhileCovered = false
        val signals = ArrayList<Signal>(3)
        if (!toldVisible) {
            toldVisible = true
            signals.add(Signal.SHOW)
        }
        if (restore) {
            signals.add(Signal.RESUME)
            signals.add(Signal.RESTORE_INPUT)
        }
        return signals
    }
}
