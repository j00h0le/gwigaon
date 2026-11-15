package com.coral.gwigaon.trip

import com.coral.gwigaon.vehicle.DrivingState

/** Top-level screens. Every one except [DRIVING] is parked-only (SPEC trip-core). */
enum class Screen {
    /** Trip setup form. */
    SETUP,

    /** The running trip while parked: status plus end trip (PIN). */
    TRIP,

    /** Glanceable status, destination and SOS; nothing to type. */
    DRIVING,

    /** Parked at the destination with the safe-arrival check open: checklist, confirmations and SOS (D5). */
    SAFE_ARRIVAL_CHECK,

    /** Parked at the destination otherwise: arrived with the passenger seated (D4), or waiting for them to get out (D7). */
    ARRIVAL,

    /** The journey report of the trip that just ended: measured timeline and AI interpretation (T19). */
    REPORT,
}

/**
 * Parked-only gating (SPEC trip-core). The screen is derived from the driving state on every change, never
 * navigated to, so a parked-only screen can't be opened while DRIVING and one that is open leaves at once.
 */
object ScreenGate {
    fun screenFor(
        driving: DrivingState,
        tripActive: Boolean,
        safeArrivalCheck: Boolean = false,
        reportPending: Boolean = false,
        arrived: Boolean = false,
    ): Screen = when {
        driving == DrivingState.DRIVING -> Screen.DRIVING
        tripActive && safeArrivalCheck -> Screen.SAFE_ARRIVAL_CHECK
        tripActive && arrived -> Screen.ARRIVAL
        tripActive -> Screen.TRIP
        reportPending -> Screen.REPORT
        else -> Screen.SETUP
    }
}
