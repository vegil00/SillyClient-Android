package com.sillyclient.runtime

/**
 * Decides when an in-progress tavern reply must keep running after the screen
 * locks or the user switches away.
 *
 * The device freezes this process, its Node server, and the tavern WebView as
 * soon as the window is covered. A reply that has already started still has to
 * receive tokens and write them out, so the hold covers three moments:
 * the stream itself, a short grace after the window is covered so a just-sent
 * reply is not frozen before its pulses arrive, and a short settle after the
 * stream so the finished message can be saved.
 */
class SessionContinuity(
    private val clock: () -> Long = System::nanoTime,
    private val pulseTtlNanos: Long = PULSE_TTL_NANOS,
    private val settleNanos: Long = SETTLE_NANOS,
) {
    private var hostVisible = true
    private var receiving = false
    private var lastPulse = 0L
    private var settleUntil = 0L

    fun isHostVisible(): Boolean = hostVisible

    fun onHostVisible(visible: Boolean, now: Long = clock()) {
        hostVisible = visible
        if (!visible && receiving) lastPulse = now
    }

    /** `active` is true while a reply is still being received. Repeated reports refresh the pulse. */
    fun onReceiving(active: Boolean, now: Long = clock()) {
        if (active) {
            receiving = true
            lastPulse = now
            settleUntil = 0L
            return
        }
        if (!receiving) return
        receiving = false
        settleUntil = if (hostVisible) 0L else now + settleNanos
    }

    fun onPulse(now: Long = clock()) {
        if (receiving) lastPulse = now
    }

    /** The page went away or the activity is gone. Do not keep a stale reply awake. */
    fun onSessionClosed() {
        receiving = false
        settleUntil = 0L
        lastPulse = 0L
    }

    fun shouldHoldProcess(now: Long = clock()): Boolean {
        // The deadline is exclusive so the scheduled tick releases the hold
        // instead of leaving a covered reply awake with no further pulse.
        if (receiving && (hostVisible || now - lastPulse < pulseTtlNanos)) return true
        return !hostVisible && now < settleUntil
    }

    fun shouldPinWebView(now: Long = clock()): Boolean =
        !hostVisible && shouldHoldProcess(now)

    /** Milliseconds until [shouldHoldProcess] may flip, or null when nothing is scheduled. */
    fun millisUntilChange(now: Long = clock()): Long? {
        val delays = ArrayList<Long>(2)
        if (receiving && !hostVisible) {
            val remaining = pulseTtlNanos - (now - lastPulse)
            if (remaining > 0L) delays.add(remaining)
        }
        if (!hostVisible && settleUntil > now) delays.add(settleUntil - now)
        val soonest = delays.minOrNull() ?: return null
        return (soonest / 1_000_000L).coerceAtLeast(1L)
    }

    companion object {
        const val PULSE_TTL_NANOS = 30L * 1_000_000_000L
        const val SETTLE_NANOS = 15L * 1_000_000_000L
    }
}
