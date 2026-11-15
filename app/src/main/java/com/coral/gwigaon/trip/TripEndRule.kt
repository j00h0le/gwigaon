package com.coral.gwigaon.trip

import com.coral.gwigaon.route.Geo
import com.coral.gwigaon.route.LatLng
import com.coral.gwigaon.vehicle.Destination
import com.coral.gwigaon.vehicle.GeoFix

/** What 운행 종료 does right now (SPEC trip-core, ADR 0015). */
sealed interface EndOption {
    /** Driving, or no live trip: the end isn't offered. */
    data object NotOffered : EndOption

    /** The trip has a PIN: ending needs it (`ended_by_passenger`). */
    data object NeedsPin : EndOption

    /** No PIN and the safe-arrival check is open: it closes by seat empty or 승객 반응 있음 first. */
    data object Blocked : EndOption

    /** No PIN, parked within [MonitoringConfig.plainEndMeters] of the destination: one press ends it (`ended_at_arrival`). */
    data object Plain : EndOption

    /**
     * No PIN, farther away: a press-and-hold ends it early (`ended_early`) with [reason] (logged); [left] is what the
     * dialog shows ("1.6 km 남음"), null without a fix.
     */
    data class Hold(val reason: String, val left: String? = null) : EndOption
}

/**
 * Pure: which end the trip allows. Parked only; with a PIN, today's PIN end. Without a PIN the current distance
 * decides (ADR 0020, 1 km), not a status set once; with no fix, having come near the destination counts as near.
 */
object TripEndRule {
    fun option(
        state: TripState,
        pinOn: Boolean,
        parked: Boolean,
        remainingM: Int?,
        plainEndM: Double = MonitoringConfig.DEFAULT.plainEndMeters,
    ): EndOption = when {
        !parked || state.status !in LIVE -> EndOption.NotOffered
        pinOn -> EndOption.NeedsPin
        state.status == TripStatus.SAFE_ARRIVAL_CHECK -> EndOption.Blocked
        near(state, remainingM, plainEndM) -> EndOption.Plain
        else -> EndOption.Hold(TripTexts.endedEarly(remainingM), TripTexts.left(remainingM))
    }

    /** Within [plainEndM] now; without a distance, the trip having reached "목적지 근처". */
    fun near(state: TripState, remainingM: Int?, plainEndM: Double = MonitoringConfig.DEFAULT.plainEndMeters): Boolean =
        remainingM?.let { it <= plainEndM } ?: (state.status == TripStatus.ARRIVING)

    /** Straight-line distance left, in whole meters. */
    fun remainingM(from: GeoFix?, to: Destination): Int? =
        from?.let { Geo.distanceM(LatLng(it.lat, it.lng), LatLng(to.lat, to.lng)).toInt() }

    private val LIVE = setOf(TripStatus.ACTIVE, TripStatus.ARRIVING, TripStatus.SAFE_ARRIVAL_CHECK)
}
