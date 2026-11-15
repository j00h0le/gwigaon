package com.coral.gwigaon.vehicle

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlin.time.Instant

/**
 * Every car and platform source the app uses, behind one interface (SPEC: vehicle-signals).
 * Nothing outside this package touches the Pleos SDKs or LocationManager directly.
 */
interface VehicleSignals {
    /** Current gear; `null` while unknown (not yet reported, or the signal is unavailable). */
    val gear: StateFlow<Gear?>

    /** Derived by [DrivingStateRule]: gear first, GPS speed as the fallback. */
    val drivingState: StateFlow<DrivingState>

    /** Turn signal lever. Hazard lights are not exposed to third-party apps on Pleos Connect. */
    val turnSignal: Flow<TurnSignal>

    /** Any passenger seat (front passenger or rear) occupied; `null` while unknown. */
    val passengerSeatOccupied: StateFlow<Boolean?>

    val doorsOpen: StateFlow<Set<Door>>

    /** The app's own GPS fixes (the emulator's navigation is a mock that never moves). */
    val location: Flow<GeoFix>

    val navi: Flow<NaviEvent>

    /** Signals that can't be read right now (permission denied, SDK error). Never throws instead. */
    val unavailable: StateFlow<Set<Signal>>

    /** Hands the destination to the car's navigation. */
    suspend fun setDestination(dest: Destination): Result<Unit>

    /** The navigation's last answer to [pollNavi] (ADR 0018); null until one arrives. */
    val naviRemaining: StateFlow<NaviRemaining?>

    /** Asks the navigation for its remaining distance and time; the answer lands in [naviRemaining]. */
    fun pollNavi()
}

/** What the car's navigation says is left: road distance, time, and the route's total once known. */
data class NaviRemaining(val distanceM: Int, val timeS: Int, val routeTotalM: Int?, val at: Instant)

enum class Gear { PARK, REVERSE, NEUTRAL, DRIVE }

enum class DrivingState { PARKED, DRIVING }

enum class TurnSignal { OFF, LEFT, RIGHT }

enum class Door { FRONT_LEFT, FRONT_RIGHT, REAR_LEFT, REAR_RIGHT, TRUNK }

enum class Signal { GEAR, TURN_SIGNAL, SEATS, DOORS, LOCATION, NAVI }

data class GeoFix(
    val lat: Double,
    val lng: Double,
    val timeMillis: Long,
    val accuracyM: Float? = null,
    /** Speed reported by the provider, if any (the emulator reports 0). */
    val speedMps: Float? = null,
)

data class Destination(
    val name: String,
    val lat: Double,
    val lng: Double,
    val address: String? = null,
    /** Navigation POI id when the destination came from a navi search; the navi shows its own name for it. */
    val poiId: String? = null,
)

/** Combines per-seat occupancy into "a passenger is in the car". */
object SeatRule {
    /** true if any seat is occupied; false if none is and at least one is known empty; null if nothing is known. */
    fun anyOccupied(seats: Collection<Boolean?>): Boolean? = when {
        seats.any { it == true } -> true
        seats.any { it == false } -> false
        else -> null
    }

    /**
     * Pleos Connect Emulator 2.1.0 delivers seat occupancy inverted (Virtual Console "occupied" arrives as
     * false, and the rear-center seat it can't set arrives as true). See research/findings/spike-vehicle.md.
     */
    fun normalize(raw: Boolean?, invertedSource: Boolean): Boolean? = if (invertedSource) raw?.not() else raw

    /** The emulator image runs on the "ranchu" virtual hardware (product crp_avd_*); a real car doesn't. */
    fun isEmulator(hardware: String, product: String): Boolean = hardware == "ranchu" || "_avd" in product
}

sealed interface NaviEvent {
    data class DestinationSet(val destination: Destination) : NaviEvent
    data object Arrived : NaviEvent
}
