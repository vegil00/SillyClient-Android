package com.sillyclient.runtime

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.webkit.WebView

/**
 * Tavern page that can keep executing while the activity window is covered.
 *
 * Locking the screen or switching apps marks the window hidden, and Chromium
 * then stops the page that is reading the reply. While a reply is still in
 * progress the page stays logically visible so tokens continue to land in the chat.
 *
 * That lie leaves the message box focused after the keyboard has already been
 * dismissed. A later tap on the same box does not ask for the keyboard again,
 * so the first time the real window is on screen the focused node is cleared
 * and the input connection is rebuilt.
 */
class SessionWebView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : WebView(context, attrs, defStyleAttr) {
    private val visibilitySignals = SessionVisibility()
    private var runWhileCovered = false
    private var pendingInputRestore = false

    fun isRunWhileCovered(): Boolean = runWhileCovered

    fun setRunWhileCovered(keep: Boolean) {
        if (runWhileCovered == keep) return
        runWhileCovered = keep
        apply(visibilitySignals.onCoveredChanged(keep, windowVisibility == VISIBLE))
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        apply(visibilitySignals.onWindowVisibility(visibility == VISIBLE))
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(if (runWhileCovered) true else isVisible)
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (hasWindowFocus) post { deliverInputRestore() }
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == VISIBLE) post { deliverInputRestore() }
    }

    private fun apply(signals: List<SessionVisibility.Signal>) {
        for (signal in signals) {
            when (signal) {
                SessionVisibility.Signal.SHOW -> {
                    super.onWindowVisibilityChanged(VISIBLE)
                    super.onVisibilityAggregated(true)
                }
                SessionVisibility.Signal.HIDE -> {
                    super.onWindowVisibilityChanged(GONE)
                    super.onVisibilityAggregated(false)
                }
                SessionVisibility.Signal.RESUME -> {
                    onResume()
                    resumeTimers()
                }
                SessionVisibility.Signal.RESTORE_INPUT -> {
                    pendingInputRestore = true
                    post { deliverInputRestore() }
                }
            }
        }
    }

    private fun deliverInputRestore() {
        if (!pendingInputRestore || !hasWindowFocus() || !isShown) return
        pendingInputRestore = false
        evaluateJavascript(BLUR_ACTIVE_ELEMENT, null)
        requestFocus()
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.restartInput(this)
    }

    private companion object {
        /**
         * The box stayed focused while the screen was covered, so Chromium will
         * not request a keyboard for another tap on it until the node is blurred.
         */
        const val BLUR_ACTIVE_ELEMENT =
            "(function(){var a=document.activeElement;if(a&&a!==document.body&&a!==document.documentElement&&typeof a.blur==='function')a.blur();})()"
    }
}
