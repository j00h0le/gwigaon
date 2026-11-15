package com.coral.gwigaon.trip

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.time.Duration

/**
 * What [TripService] does once a trip ends (T22): it stays until the ended trip's events and "ended" are in the
 * cloud, or [grace] runs out, then calls [stop]. A new trip starting meanwhile cancels the wait, and [stop] is not
 * called. With no network at the end it stops at once (/ship Q5): nothing can go up, and waiting would keep the
 * "sending" notification and the location service on for the whole grace; the queue sends the rest at the next start.
 */
class EndedTripUploads(
    private val scope: CoroutineScope,
    private val settle: (suspend (tripId: String, timeout: Duration) -> Boolean)?,
    private val online: () -> Boolean,
    private val grace: Duration,
    private val onWaiting: () -> Unit,
    private val stop: (Outcome) -> Unit,
) {
    enum class Outcome { NOTHING_TO_SEND, OFFLINE, SETTLED, TIMED_OUT }

    private var lastTripId: String? = null
    private var finishing: Job? = null

    /** Feed every value of the active trip's id; null means no trip is running (it ended, or there was none). */
    fun onActive(tripId: String?) {
        finishing?.cancel()
        finishing = null
        if (tripId != null) {
            lastTripId = tripId
            return
        }
        val ended = lastTripId
        finishing = scope.launch { stop(finish(ended)) }
    }

    private suspend fun finish(tripId: String?): Outcome {
        if (tripId == null || settle == null) return Outcome.NOTHING_TO_SEND
        if (!online()) return Outcome.OFFLINE
        onWaiting()
        return if (settle(tripId, grace)) Outcome.SETTLED else Outcome.TIMED_OUT
    }
}
