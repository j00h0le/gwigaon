package com.coral.gwigaon.route

import com.coral.gwigaon.trip.ActiveTrip
import com.coral.gwigaon.vehicle.GeoFix
import com.coral.gwigaon.vehicle.NaviRemaining
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** What is left of the way home: distance (m), time (s), the trip's total distance once known, and when measured. */
data class RouteEta(val remainM: Int, val remainS: Int, val totalM: Int?, val at: Instant) {
    /** Share of the way already covered, 0..1; null without a total. */
    val fraction: Float?
        get() = totalM?.takeIf { it > 0 }?.let { (1f - remainM.toFloat() / it).coerceIn(0f, 1f) }

    val arrival: Instant get() = at + remainS.seconds
}

/**
 * The arrival time for the driver panel (ADR 0018). On a car it asks the navigation every [every] and uses its
 * answer while fresh ([maxAge]); on the emulator, whose navigation is a mock that never moves, it estimates from the
 * app's own GPS: the straight line to the destination at [DEMO_SPEED_MPS], over the distance at the trip's first fix.
 */
class EtaTracker(
    private val active: StateFlow<ActiveTrip?>,
    private val lastFix: () -> GeoFix?,
    private val naviRemaining: StateFlow<NaviRemaining?>,
    private val pollNavi: () -> Unit,
    private val clock: Clock,
    private val demo: Boolean,
    private val every: Duration = 10.seconds,
    private val maxAge: Duration = 60.seconds,
) {
    private val _eta = MutableStateFlow<RouteEta?>(null)
    val eta: StateFlow<RouteEta?> = _eta.asStateFlow()

    private var tripId: String? = null
    private var demoTotalM: Int? = null

    fun run(scope: CoroutineScope): Job = scope.launch {
        while (isActive) {
            tick()
            delay(every)
        }
    }

    internal fun tick() {
        val trip = active.value
        if (trip == null) {
            tripId = null
            demoTotalM = null
            _eta.value = null
            return
        }
        if (trip.trip.id != tripId) {
            tripId = trip.trip.id
            demoTotalM = null
            _eta.value = null
        }
        val now = clock.now()
        _eta.value = if (demo) estimate(trip, now) else {
            pollNavi()
            naviRemaining.value?.takeIf { now - it.at <= maxAge }?.let { RouteEta(it.distanceM, it.timeS, it.routeTotalM, it.at) }
        }
    }

    private fun estimate(trip: ActiveTrip, now: Instant): RouteEta? {
        val fix = lastFix() ?: return _eta.value
        val dest = trip.trip.destination
        val remain = Geo.distanceM(LatLng(fix.lat, fix.lng), LatLng(dest.lat, dest.lng)).roundToInt()
        val total = demoTotalM ?: remain.also { demoTotalM = it }
        return RouteEta(remain, (remain / DEMO_SPEED_MPS).roundToInt(), total, now)
    }

    companion object {
        /** 30 km/h, a city average for the demo estimate. */
        const val DEMO_SPEED_MPS = 30_000.0 / 3600.0
    }
}
