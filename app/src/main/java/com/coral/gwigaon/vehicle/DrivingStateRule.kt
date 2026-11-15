package com.coral.gwigaon.vehicle

import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.runningFold
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * PARKED or DRIVING (SPEC: vehicle-signals). Gear first: P is parked; D, R and N are driving.
 * Unknown gear falls back to GPS-derived speed (> 5 km/h is driving).
 * Unknown gear with no speed yet is DRIVING: parked-only screens stay closed until we know (fail-safe).
 */
object DrivingStateRule {
    const val SPEED_THRESHOLD_KMH = 5.0

    fun decide(gear: Gear?, speedKmh: Double?): DrivingState = when (gear) {
        Gear.PARK -> DrivingState.PARKED
        Gear.DRIVE, Gear.REVERSE, Gear.NEUTRAL -> DrivingState.DRIVING
        null -> if (speedKmh == null || speedKmh > SPEED_THRESHOLD_KMH) DrivingState.DRIVING else DrivingState.PARKED
    }

    /** A speed no newer fix has confirmed for this long is unknown (GPS lost in a tunnel, updates stopped). */
    val SPEED_MAX_AGE: Duration = 5.seconds

    /**
     * A signal listed in [unavailable] counts as unknown, never as its last value: a gear stuck at P after the
     * SDK fails must not keep parked-only screens open while the car moves.
     */
    fun drivingState(
        gear: Flow<Gear?>,
        location: Flow<GeoFix>,
        unavailable: Flow<Set<Signal>>,
        speedMaxAge: Duration = SPEED_MAX_AGE,
    ): Flow<DrivingState> {
        val speed = SpeedEstimator.speedKmh(location).expiring(speedMaxAge).onStart { emit(null) }
        return combine(gear, speed, unavailable) { g, kmh, missing ->
            decide(g.takeUnless { Signal.GEAR in missing }, kmh.takeUnless { Signal.LOCATION in missing })
        }.distinctUntilChanged()
    }

    /** Gear and location both missing: the state can only ever be the fail-safe DRIVING, so say why. */
    fun cannotTell(unavailable: Set<Signal>): Boolean = Signal.GEAR in unavailable && Signal.LOCATION in unavailable

    /** Emits each value, then null once [after] passes without a newer one. */
    private fun <T : Any> Flow<T>.expiring(after: Duration): Flow<T?> = channelFlow {
        var timer: Job? = null
        collect { value ->
            timer?.cancel()
            send(value)
            timer = launch {
                delay(after)
                send(null)
            }
        }
    }
}

/** Speed from successive GPS fixes (the emulator's fixes carry speed = 0). */
object SpeedEstimator {
    /** km/h between two fixes; `null` when time doesn't move forward. Moves within GPS accuracy count as 0. */
    fun estimateKmh(prev: GeoFix, cur: GeoFix): Double? {
        val seconds = (cur.timeMillis - prev.timeMillis) / 1000.0
        if (seconds <= 0) return null
        val meters = GeoMath.distanceMeters(prev.lat, prev.lng, cur.lat, cur.lng)
        val noise = max(prev.accuracyM ?: 0f, cur.accuracyM ?: 0f)
        val derived = if (meters <= noise) 0.0 else meters / seconds * 3.6
        val reported = (cur.speedMps ?: 0f) * 3.6
        return max(derived, reported)
    }

    fun speedKmh(fixes: Flow<GeoFix>): Flow<Double> =
        fixes.runningFold(Pair<GeoFix?, Double?>(null, null)) { (prev, _), cur ->
            cur to prev?.let { estimateKmh(it, cur) }
        }.mapNotNull { it.second }
}

object GeoMath {
    private const val EARTH_RADIUS_M = 6_371_000.0

    /** Great-circle (haversine) distance in meters. */
    fun distanceMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2) * sin(dLng / 2)
        return 2 * EARTH_RADIUS_M * asin(sqrt(a))
    }
}
