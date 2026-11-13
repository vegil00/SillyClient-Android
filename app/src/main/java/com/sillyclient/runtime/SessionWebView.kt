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
 * The page is created with the platform WebView style so a tap can focus the
 * message box and raise the keyboard on the first launch. While a covered reply
 * is kept logically visible, the focused node is cleared the next time the real
 * window is shown, because a tap on that same node would not ask for the keyboard.
 */
class SessionWebView : WebView {
    constructor(context: Context) : super(context)
    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)
    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : super(context, attrs, defStyleAttr)

    private val visibilitySignals = SessionVisibility()
    private var runWhileCovered = false
    private var pendingInputRestore = false

    init {
        // WebView(context) applies the platform webView style, which is what makes a
        // tap move focus into the page and open the keyboard. A default style of 0
        // skips that, so the message box can be tapped from the first launch and
        // still never raise the keyboard.
        isFocusable = true
        isFocusableInTouchMode = true
    }

    fun isRunWhileCovered(): Boolean = runWhileCovered

    fun setRunWhileCovered(keep: Boolean) {
        if (runWhileCovered == keep) return
        runWhileCovered = keep
        apply(visibilitySignals.onCoveredChanged(keep, windowVisibility == VISIBLE))
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        val signals = visibilitySignals.onWindowVisibility(visibility == VISIBLE)
        if (!runWhileCovered) {
            super.onWindowVisibilityChanged(visibility)
            apply(signals.filter { it == SessionVisibility.Signal.RESUME || it == SessionVisibility.Signal.RESTORE_INPUT })
            return
        }
        apply(signals)
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
                SessionVisibility.Signal.SHOW -> super.onWindowVisibilityChanged(VISIBLE)
                SessionVisibility.Signal.HIDE -> super.onWindowVisibilityChanged(GONE)
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
