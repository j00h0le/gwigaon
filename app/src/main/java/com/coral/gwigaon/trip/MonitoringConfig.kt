package com.coral.gwigaon.trip

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** Every monitoring threshold in one place (SPEC: Shared defaults). Rules take this; nothing is inlined. */
data class MonitoringConfig(
    /** Moving away from the destination by more than this, for [deviationDuration], is a deviation. */
    val deviationMeters: Double = 300.0,
    val deviationDuration: Duration = 60.seconds,
    /** A deviation clears once the car is back within this much of its closest approach (hysteresis). */
    val deviationClearMeters: Double = 150.0,
    /** Fixes less accurate than this are ignored by the route rules. */
    val maxFixAccuracyMeters: Float = 100f,
    /**
     * An event only carries the latest fix if it arrived within this long. Location is only collected during a
     * trip, so without this a new trip's first events would carry where the previous trip ended (Checkpoint C F1).
     * Long enough for a tunnel, so an SOS after a short GPS gap still has a position.
     */
    val fixMaxAge: Duration = 2.minutes,
    /** Route rules ignore a fix that implies moving faster than this since the last accepted one (GPS jumps). */
    val maxPlausibleSpeedMps: Double = 70.0,
    /** After this many rejected jumps in a row the new position is taken as real (the old one was the outlier). */
    val jumpReanchorAfter: Int = 3,
    /** Route rules also re-check on this timer: a parked car may send no fixes at all. */
    val routeTick: Duration = 5.seconds,
    /** Moving less than this counts as standing still. */
    val stationaryRadiusMeters: Double = 30.0,
    val longStopDuration: Duration = 5.minutes,
    /** Long stops closer than this to the destination are parking, not a stop. */
    val longStopMinDistanceFromDestinationMeters: Double = 500.0,
    val nearDestinationMeters: Double = 500.0,
    val arrivedMeters: Double = 100.0,
    /** Parked within this of the destination a no-PIN end is plain; beyond it, the early-end hold (ADR 0020). */
    val plainEndMeters: Double = 1000.0,
    /** Passenger still seated this long after arrival (gear P) opens the safe-arrival check. */
    val safeArrivalDelay: Duration = 3.minutes,
    /** Turn-signal SOS: this many LEFT/RIGHT direction changes within [sosTurnSignalWindow]. */
    val sosTurnSignalChanges: Int = 4,
    val sosTurnSignalWindow: Duration = 6.seconds,
    /** On-screen SOS press-and-hold time. */
    val sosHold: Duration = 1500.milliseconds,
    val heartbeatInterval: Duration = 30.seconds,
    /** How often the local trip row records "still monitoring"; a restart's gap is accurate to this. */
    val livenessInterval: Duration = 10.seconds,
    /** The contact page shows "모니터링 중단됨" after this much silence. */
    val contactInterruptedAfter: Duration = 90.seconds,
) {
    companion object {
        val DEFAULT = MonitoringConfig()

        /** Demo: the safe-arrival check opens after 30 s instead of 3 min. The demo route's long stop is already > 5 min. */
        val DEMO = DEFAULT.copy(safeArrivalDelay = 30.seconds)

        fun forMode(demo: Boolean): MonitoringConfig = if (demo) DEMO else DEFAULT
    }
}
