package com.coral.gwigaon.arrival

import com.coral.gwigaon.trip.MonitoringConfig
import com.coral.gwigaon.trip.SafeArrivalCloser
import com.coral.gwigaon.trip.TripState
import com.coral.gwigaon.trip.TripStatus
import com.coral.gwigaon.vehicle.Gear
import kotlin.time.Duration
import kotlin.time.Instant

sealed interface SafeArrivalDecision {
    data object None : SafeArrivalDecision

    /** Parked at the destination with a passenger still seated for [seatedFor] (≥ the configured delay). */
    data class Open(val seatedFor: Duration) : SafeArrivalDecision

    data class Confirm(val closedBy: SafeArrivalCloser) : SafeArrivalDecision
}

/**
 * SPEC arrival, safe-arrival check, for one trip. After `ARRIVED`:
 * - gear P and the passenger seats empty → confirmed (`seat_empty`);
 * - gear P and a passenger still seated for [MonitoringConfig.safeArrivalDelay] → the check opens;
 * - while the check is open, only an empty seat closes it here (gear doesn't matter: the passenger got out).
 *   On a PIN trip the passenger's PIN closes it from the screen; the driver is asked nothing (ADR 0020).
 * An unknown seat or gear neither opens nor confirms anything, and restarts the seated timer: the app can't tell.
 */
class SafeArrivalRule(private val config: MonitoringConfig) {
    private var seatedSince: Instant? = null

    fun evaluate(state: TripState, gear: Gear?, seatOccupied: Boolean?, now: Instant): SafeArrivalDecision {
        if (!state.arrived || state.safeArrivalConfirmed || state.status !in WATCHED) return reset()
        val parked = gear == Gear.PARK
        val checkOpen = state.status == TripStatus.SAFE_ARRIVAL_CHECK
        if (seatOccupied == false && (parked || checkOpen)) {
            seatedSince = null
            return SafeArrivalDecision.Confirm(SafeArrivalCloser.SEAT_EMPTY)
        }
        if (checkOpen) return SafeArrivalDecision.None
        if (!parked || seatOccupied != true) return reset()
        val since = seatedSince ?: now.also { seatedSince = it }
        val seated = now - since
        return if (seated >= config.safeArrivalDelay) SafeArrivalDecision.Open(seated) else SafeArrivalDecision.None
    }

    private fun reset(): SafeArrivalDecision {
        seatedSince = null
        return SafeArrivalDecision.None
    }

    private companion object {
        val WATCHED = setOf(TripStatus.ARRIVING, TripStatus.SAFE_ARRIVAL_CHECK)
    }
}
