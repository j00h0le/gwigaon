package com.coral.gwigaon.route

import com.coral.gwigaon.trip.EventRecord
import com.coral.gwigaon.trip.MonitoringConfig
import com.coral.gwigaon.trip.TripEvent
import kotlin.time.Instant

/** What a route rule wants logged after a step. */
sealed interface RouteDecision {
    data object None : RouteDecision
    data class Open(val amount: Long) : RouteDecision
    data class Close(val amount: Long) : RouteDecision
}

/**
 * Route deviation without a route line (SPEC route-monitor: NaviHelper exposes no polyline, T3). The car's closest
 * approach to the destination so far is its progress; ending up more than [MonitoringConfig.deviationMeters]
 * further away than that, continuously for [MonitoringConfig.deviationDuration] while driving, is a deviation. It
 * clears once the car is back within [MonitoringConfig.deviationClearMeters] of its best progress, so GPS noise
 * around the threshold doesn't flap it open and shut.
 *
 * [RouteDecision.Open.amount] is the excess distance in meters. Whether a deviation is open is the trip state's
 * call ([open]), so a resumed trip or a rejected log can't put the rule out of step with the log.
 *
 * After a restart the best progress so far comes from the stored log ([resumedBest], see [bestFromLog]). If the
 * log has no position and a deviation is open, the rule assumes the car was at least [MonitoringConfig.deviationMeters]
 * past its best when it first sees it, so the restart alone never reads as "back on route".
 */
class DeviationRule(
    private val destination: LatLng,
    config: MonitoringConfig,
    resumedBest: Double? = null,
) {
    private val threshold = config.deviationMeters
    private val clearBelow = config.deviationClearMeters
    private val sustain = config.deviationDuration

    private var best = resumedBest ?: Double.POSITIVE_INFINITY
    private var aboveSince: Instant? = null

    fun onPosition(at: LatLng, now: Instant, driving: Boolean, open: Boolean): RouteDecision {
        val distance = Geo.distanceM(at, destination)
        if (open && best == Double.POSITIVE_INFINITY) best = (distance - threshold).coerceAtLeast(0.0)
        best = minOf(best, distance)
        val excess = distance - best
        if (open) {
            aboveSince = null
            return if (excess < clearBelow) RouteDecision.Close(excess.toLong()) else RouteDecision.None
        }
        if (!driving || excess <= threshold) {
            aboveSince = null
            return RouteDecision.None
        }
        val since = aboveSince ?: now.also { aboveSince = it }
        return if (now - since >= sustain) RouteDecision.Open(excess.toLong()) else RouteDecision.None
    }

    companion object {
        /**
         * The closest the car is known to have come to [destination], from a trip's stored log: every position
         * there is one the car was at, and a `ROUTE_DEVIATION` also says how far past its best progress the car
         * was at that moment. Null when no event has a position.
         */
        fun bestFromLog(records: List<EventRecord>, destination: LatLng): Double? = records.mapNotNull { r ->
            val lat = r.lat ?: return@mapNotNull null
            val lng = r.lng ?: return@mapNotNull null
            val distance = Geo.distanceM(LatLng(lat, lng), destination)
            val excess = (r.event as? TripEvent.RouteDeviation)?.distanceM ?: 0
            (distance - excess).coerceAtLeast(0.0)
        }.minOrNull()
    }
}
