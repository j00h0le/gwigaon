package com.coral.gwigaon.route

import com.coral.gwigaon.trip.MonitoringConfig
import com.coral.gwigaon.trip.TripEvent
import com.coral.gwigaon.trip.TripSessionRepository
import com.coral.gwigaon.trip.TripStatus
import com.coral.gwigaon.trip.TripTexts
import com.coral.gwigaon.vehicle.DrivingState
import com.coral.gwigaon.vehicle.GeoFix
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** Where the route monitor reports what it logged or failed to log; the app writes it to logcat. */
fun interface RouteLog {
    fun log(message: String, error: Throwable?)
}

/**
 * Runs [DeviationRule] and [LongStopRule] for the running trip (SPEC route-monitor) and logs their events through
 * the trip session, with Korean reasons. The rules start fresh for each trip; whether a deviation or long stop is
 * open always comes from the trip state, so after a restart a resumed trip can still clear what was open. The
 * deviation rule's best progress is rebuilt from the stored log, so a restart doesn't clear a deviation by itself.
 */
class RouteMonitor(
    private val sessions: TripSessionRepository,
    private val drivingState: StateFlow<DrivingState>,
    private val clock: Clock,
    private val config: MonitoringConfig,
    private val log: RouteLog = RouteLog { _, _ -> },
) {
    private val mutex = Mutex()
    private var tripId: String? = null
    private var deviation: DeviationRule? = null
    private var longStop: LongStopRule? = null
    private var lastAccepted: Pair<LatLng, Instant>? = null
    private var jumpsInARow = 0
    private var destination: LatLng? = null
    private var bestFromLog: Double? = null

    /** Follows [location] and re-checks every [MonitoringConfig.routeTick] for as long as [scope] lives. */
    fun watch(location: Flow<GeoFix>, scope: CoroutineScope): Job = scope.launch {
        launch { location.collect { onFix(it) } }
        while (isActive) {
            delay(config.routeTick)
            onTick()
        }
    }

    internal suspend fun onFix(fix: GeoFix) = mutex.withLock {
        val accuracy = fix.accuracyM
        if (accuracy != null && accuracy > config.maxFixAccuracyMeters) return@withLock
        val active = rulesForCurrentTrip() ?: return@withLock
        val at = LatLng(fix.lat, fix.lng)
        val now = clock.now()
        if (!plausible(at, now)) return@withLock
        val state = active.state
        val driving = drivingState.value == DrivingState.DRIVING
        handleDeviation(checkNotNull(deviation).onPosition(at, now, driving, state.deviating))
        handleLongStop(checkNotNull(longStop).onPosition(at, now, state.longStopped, state.status != TripStatus.ACTIVE))
    }

    internal suspend fun onTick() = mutex.withLock {
        val active = rulesForCurrentTrip() ?: return@withLock
        val state = active.state
        handleLongStop(checkNotNull(longStop).onTick(clock.now(), state.longStopped, state.status != TripStatus.ACTIVE))
    }

    private suspend fun rulesForCurrentTrip() = sessions.active.value?.also { active ->
        if (active.trip.id != tripId) {
            val dest = LatLng(active.trip.destination.lat, active.trip.destination.lng)
            tripId = active.trip.id
            val logged = sessions.events(active.trip.id)
                .onFailure { log.log("trip log unreadable; deviation progress starts fresh", it) }
                .getOrDefault(emptyList())
            destination = dest
            bestFromLog = DeviationRule.bestFromLog(logged, dest)
            lastAccepted = null
            jumpsInARow = 0
            newRules()
        }
    }

    /** Fresh rules for this trip; whether a deviation or long stop is open still comes from the trip state. */
    private fun newRules() {
        val dest = checkNotNull(destination)
        deviation = DeviationRule(dest, config, bestFromLog)
        longStop = LongStopRule(dest, config)
    }

    /**
     * False for a fix that implies moving faster than [MonitoringConfig.maxPlausibleSpeedMps] since the last accepted
     * one: a single GPS jump toward the destination would otherwise become the trip's best progress and read as a
     * deviation for the rest of the trip. After [MonitoringConfig.jumpReanchorAfter] such fixes in a row, the old
     * position was the outlier, so the new one is taken and the rules start over, since what they learned came
     * from the outlier.
     */
    private fun plausible(at: LatLng, now: Instant): Boolean {
        val prev = lastAccepted
        if (prev != null) {
            val seconds = (now - prev.second).inWholeMilliseconds.coerceAtLeast(1_000) / 1_000.0
            val distance = Geo.distanceM(prev.first, at)
            if (distance / seconds > config.maxPlausibleSpeedMps && ++jumpsInARow < config.jumpReanchorAfter) {
                log.log("GPS jump ignored: ${distance.toInt()} m in ${seconds.toInt()} s", null)
                return false
            }
            if (jumpsInARow > 0) {
                log.log("GPS re-anchored after $jumpsInARow jumps; route rules restarted", null)
                newRules()
            }
        }
        jumpsInARow = 0
        lastAccepted = at to now
        return true
    }

    private suspend fun handleDeviation(d: RouteDecision) = when (d) {
        RouteDecision.None -> Unit
        is RouteDecision.Open -> record(TripEvent.RouteDeviation(d.amount.toInt(), TripTexts.deviation(d.amount.toDouble())))
        is RouteDecision.Close -> record(TripEvent.DeviationCleared(TripTexts.DEVIATION_CLEARED))
    }

    private suspend fun handleLongStop(d: RouteDecision) = when (d) {
        RouteDecision.None -> Unit
        is RouteDecision.Open -> record(TripEvent.LongStop(d.amount.toInt(), TripTexts.longStop(d.amount.seconds)))
        is RouteDecision.Close -> record(TripEvent.LongStopCleared(TripTexts.longStopCleared(d.amount.seconds)))
    }

    private suspend fun record(event: TripEvent) {
        sessions.log(event)
            .onSuccess { log.log("${event.type.wire} seq=${it.seq}: ${event.reason}", null) }
            .onFailure { log.log("${event.type.wire} not stored", it) }
    }
}
