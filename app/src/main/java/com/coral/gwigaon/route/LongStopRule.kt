package com.coral.gwigaon.route

import com.coral.gwigaon.trip.MonitoringConfig
import kotlin.time.Instant

/**
 * Long stop (SPEC route-monitor): the car stays within [MonitoringConfig.stationaryRadiusMeters] of one spot for
 * [MonitoringConfig.longStopDuration], more than [MonitoringConfig.longStopMinDistanceFromDestinationMeters] from
 * the destination, while the trip isn't arriving. Gear doesn't matter: parking in an alley is exactly the case.
 * Nothing counts before the car has first moved, so waiting at the pickup isn't a stop.
 *
 * Fixes may stop coming while the car stands still, so [onTick] re-checks with the time alone.
 * [RouteDecision.Open.amount] and [RouteDecision.Close.amount] are the seconds stopped.
 */
class LongStopRule(private val destination: LatLng, config: MonitoringConfig) {
    private val radius = config.stationaryRadiusMeters
    private val duration = config.longStopDuration
    private val minFromDestination = config.longStopMinDistanceFromDestinationMeters

    private var anchor: LatLng? = null
    private var since: Instant? = null
    private var hasMoved = false

    fun onPosition(at: LatLng, now: Instant, open: Boolean, arriving: Boolean): RouteDecision {
        val a = anchor
        if (a == null) {
            anchor = at
            since = now
            return RouteDecision.None
        }
        if (Geo.distanceM(a, at) > radius) {
            val stoppedFor = now - checkNotNull(since)
            anchor = at
            since = now
            hasMoved = true
            return if (open) RouteDecision.Close(stoppedFor.inWholeSeconds) else RouteDecision.None
        }
        return onTick(now, open, arriving)
    }

    fun onTick(now: Instant, open: Boolean, arriving: Boolean): RouteDecision {
        val a = anchor ?: return RouteDecision.None
        val start = since ?: return RouteDecision.None
        if (open || arriving || !hasMoved) return RouteDecision.None
        val stoppedFor = now - start
        if (stoppedFor < duration || Geo.distanceM(a, destination) <= minFromDestination) return RouteDecision.None
        return RouteDecision.Open(stoppedFor.inWholeSeconds)
    }
}
