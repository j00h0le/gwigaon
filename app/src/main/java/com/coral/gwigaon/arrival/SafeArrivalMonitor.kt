package com.coral.gwigaon.arrival

import com.coral.gwigaon.trip.EndReason
import com.coral.gwigaon.trip.MonitoringConfig
import com.coral.gwigaon.trip.SafeArrivalCloser
import com.coral.gwigaon.trip.TripEvent
import com.coral.gwigaon.trip.TripSessionRepository
import com.coral.gwigaon.trip.TripStatus
import com.coral.gwigaon.vehicle.Gear
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock

/**
 * Runs [SafeArrivalRule] for the running trip and logs what it decides, then ends the trip as `safe_arrival`
 * once the arrival is confirmed and no SOS is open (SPEC: the contact sees safe arrival). An empty seat or the
 * passenger's PIN ends it right away. The driver is asked nothing (ADR 0020); a trip stored with the old
 * `driver_confirmed` close still waits until the car is in P with the passenger seats empty (ADR 0007).
 * Ending follows the trip state rather than the rule.
 * Re-checks on every gear, seat or trip change and on a timer, since a parked car may report nothing for minutes.
 */
class SafeArrivalMonitor(
    private val sessions: TripSessionRepository,
    private val gear: StateFlow<Gear?>,
    private val seatOccupied: StateFlow<Boolean?>,
    private val clock: Clock,
    private val config: MonitoringConfig,
    private val log: ArrivalLog = ArrivalLog { _, _ -> },
) {
    private val mutex = Mutex()
    private var tripId: String? = null
    private var rule = SafeArrivalRule(config)

    fun watch(scope: CoroutineScope): Job = scope.launch {
        launch { combine(gear, seatOccupied, sessions.active) { _, _, _ -> }.collect { check() } }
        while (isActive) {
            delay(config.routeTick)
            check()
        }
    }

    internal suspend fun check() = mutex.withLock {
        val active = sessions.active.value ?: return@withLock
        if (active.trip.id != tripId) {
            tripId = active.trip.id
            rule = SafeArrivalRule(config)
        }
        when (val d = rule.evaluate(active.state, gear.value, seatOccupied.value, clock.now())) {
            SafeArrivalDecision.None -> Unit
            is SafeArrivalDecision.Open -> record(TripEvent.SafeArrivalCheckOpened(ArrivalTexts.checkOpened(d.seatedFor)))
            is SafeArrivalDecision.Confirm -> record(TripEvent.SafeArrivalConfirmed(d.closedBy, ArrivalTexts.confirmed(d.closedBy)))
        }
        val state = sessions.active.value?.state ?: return@withLock
        if (!state.safeArrivalConfirmed || state.sosActive || state.status != TripStatus.ARRIVING) return@withLock
        if (state.safeArrivalClosedBy != SafeArrivalCloser.DRIVER_CONFIRMED) {
            record(TripEvent.TripEnded(EndReason.SAFE_ARRIVAL, ArrivalTexts.SAFE_ARRIVAL_ENDED))
        } else if (gear.value == Gear.PARK && seatOccupied.value == false) {
            record(TripEvent.TripEnded(EndReason.SAFE_ARRIVAL, ArrivalTexts.SAFE_ARRIVAL_ENDED_AFTER_DRIVER))
        }
    }

    private suspend fun record(event: TripEvent) {
        sessions.log(event)
            .onSuccess { log.log("${event.type.wire} seq=${it.seq}: ${event.reason}", null) }
            .onFailure { log.log("${event.type.wire} not stored", it) }
    }
}
