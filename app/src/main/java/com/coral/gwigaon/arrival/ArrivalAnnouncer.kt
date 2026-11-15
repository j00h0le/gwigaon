package com.coral.gwigaon.arrival

import com.coral.gwigaon.route.Geo
import com.coral.gwigaon.route.LatLng
import com.coral.gwigaon.trip.MonitoringConfig
import com.coral.gwigaon.trip.TripEvent
import com.coral.gwigaon.trip.TripSessionRepository
import com.coral.gwigaon.trip.TripStatus
import com.coral.gwigaon.vehicle.GeoFix
import com.coral.gwigaon.vehicle.NaviEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Logs `NEAR_DESTINATION` and later `ARRIVED` (≤ [MonitoringConfig.arrivedMeters], or the navigation's own
 * arrival while our last fix is within [MonitoringConfig.nearDestinationMeters]: the navigation reports arriving at
 * whatever it was routed to, which the driver can change). `NEAR_DESTINATION` comes the first time an active trip comes within [MonitoringConfig.nearDestinationMeters] of
 * its destination (our own GPS distance: the emulator's navigation is a mock, research/findings/spike-navi.md),
 * then speaks the arrival instructions once. Once per trip: the state machine only takes the event from ACTIVE,
 * and the announced trip's id is remembered as well. A log that fails is tried again on the next fix, and nothing
 * is spoken until one is stored.
 */
class ArrivalAnnouncer(
    private val sessions: TripSessionRepository,
    private val config: MonitoringConfig,
    private val speaker: Speaker,
    private val log: ArrivalLog = ArrivalLog { _, _ -> },
) {
    private val mutex = Mutex()
    private var announcedTripId: String? = null

    /** The last accepted fix's distance to the destination, for the trip it was measured on. */
    private var lastDistance: Pair<String, Double>? = null

    fun watch(location: Flow<GeoFix>, navi: Flow<NaviEvent>, scope: CoroutineScope): Job = scope.launch {
        launch { navi.collect { if (it is NaviEvent.Arrived) onNaviArrived() } }
        location.collect { onFix(it) }
    }

    internal suspend fun onFix(fix: GeoFix) = mutex.withLock {
        val accuracy = fix.accuracyM
        if (accuracy != null && accuracy > config.maxFixAccuracyMeters) return@withLock
        val active = sessions.active.value ?: return@withLock
        val dest = active.trip.destination
        val distance = Geo.distanceM(LatLng(fix.lat, fix.lng), LatLng(dest.lat, dest.lng))
        lastDistance = active.trip.id to distance
        if (distance <= config.nearDestinationMeters) announce(distance)
        if (distance <= config.arrivedMeters) arrive("${distance.toInt()} m")
    }

    /** The car's navigation says it arrived (a real car; the emulator's mock never does, spike-navi.md). */
    internal suspend fun onNaviArrived() = mutex.withLock {
        val active = sessions.active.value ?: return@withLock
        val distance = lastDistance?.takeIf { it.first == active.trip.id }?.second
        if (distance == null || distance > config.nearDestinationMeters) {
            log.log("navigation arrival ignored: " + (distance?.let { "${it.toInt()} m from the destination" } ?: "no fix yet"), null)
            return@withLock
        }
        announce(null)
        arrive("navigation")
    }

    /** `ARRIVED` once, after `NEAR_DESTINATION`: the GPS path always passes 500 m before 100 m. */
    private suspend fun arrive(how: String) {
        val active = sessions.active.value ?: return
        if (active.state.status != TripStatus.ARRIVING || active.state.arrived) return
        val event = TripEvent.Arrived(ArrivalTexts.ARRIVED)
        sessions.log(event)
            .onSuccess { log.log("${event.type.wire} seq=${it.seq} by $how", null) }
            .onFailure { log.log("${event.type.wire} not stored", it) }
    }

    private suspend fun announce(distance: Double?) {
        val active = sessions.active.value ?: return
        if (active.state.status != TripStatus.ACTIVE || active.trip.id == announcedTripId) return
        val event = TripEvent.NearDestination(ArrivalTexts.nearDestination(config.nearDestinationMeters))
        sessions.log(event)
            .onSuccess { record ->
                announcedTripId = active.trip.id
                log.log("${event.type.wire} seq=${record.seq} " + (distance?.let { "at ${it.toInt()} m" } ?: "by navigation"), null)
                // Speech must never stop the card or the trip: a failing speaker is logged and the card stays.
                runCatching { speaker.speak(ArrivalTexts.spoken(active.trip.arrival)) }
                    .onFailure { log.log("arrival instructions not spoken", it) }
            }
            .onFailure { log.log("${event.type.wire} not stored", it) }
    }
}
