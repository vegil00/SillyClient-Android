package com.sillyclient.runtime

import android.content.Context
import android.util.AttributeSet
import android.webkit.WebView

/**
 * Tavern page that can keep executing while the activity window is covered.
 *
 * Locking the screen or switching apps marks the window hidden, and Chromium
 * then stops the page that is reading the reply. While a reply is still in
 * progress the page stays logically visible so tokens continue to land in the chat.
 */
class SessionWebView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : WebView(context, attrs, defStyleAttr) {
    private var runWhileCovered = false

    fun isRunWhileCovered(): Boolean = runWhileCovered

    fun setRunWhileCovered(keep: Boolean) {
        if (runWhileCovered == keep) return
        runWhileCovered = keep
        if (keep) {
            super.onWindowVisibilityChanged(VISIBLE)
            super.onVisibilityAggregated(true)
            onResume()
            resumeTimers()
        } else {
            super.onWindowVisibilityChanged(windowVisibility)
            super.onVisibilityAggregated(windowVisibility == VISIBLE && visibility == VISIBLE)
        }
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        val reported = if (runWhileCovered && visibility != VISIBLE) VISIBLE else visibility
        super.onWindowVisibilityChanged(reported)
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(if (runWhileCovered) true else isVisible)
    }
}
