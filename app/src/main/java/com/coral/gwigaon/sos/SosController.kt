package com.coral.gwigaon.sos

import com.coral.gwigaon.trip.EventRecord
import com.coral.gwigaon.trip.MonitoringConfig
import com.coral.gwigaon.trip.SosSource
import com.coral.gwigaon.trip.TripEvent
import com.coral.gwigaon.trip.TripSessionRepository
import com.coral.gwigaon.trip.TripTexts
import com.coral.gwigaon.vehicle.TurnSignal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlin.time.Clock

/** Where the SOS path reports what happened; the app writes it to logcat, tests stay silent. */
fun interface SosLog {
    fun log(message: String, error: Throwable?)
}

/**
 * Both SOS paths (SPEC sos): the on-screen hold ([fire]) and the turn-signal pattern ([watchTurnSignal]). Firing
 * only logs `SOS` through the trip session: stored locally with the last GPS fix and queued as urgent upload in the
 * same transaction, so it works offline and never waits for the network or AI. [fired] is the hook the cloud sync
 * (T16) listens to so it uploads at once instead of waiting for the next batch.
 */
class SosController(
    private val sessions: TripSessionRepository,
    private val clock: Clock,
    config: MonitoringConfig,
    private val log: SosLog = SosLog { _, _ -> },
) {
    private val rule = TurnSignalSosRule(config)
    private var ruleTripId: String? = null
    private val _fired = MutableSharedFlow<EventRecord>(extraBufferCapacity = 16)

    /** Every SOS stored, right after it is stored. */
    val fired: SharedFlow<EventRecord> = _fired.asSharedFlow()

    /** Logs SOS for the running trip. Fails only when no trip is running or the event can't be stored. */
    suspend fun fire(source: SosSource): Result<EventRecord> {
        val reason = when (source) {
            SosSource.TURN_SIGNAL -> TripTexts.SOS_TURN_SIGNAL
            SosSource.ON_SCREEN -> TripTexts.SOS_ON_SCREEN
        }
        return sessions.log(TripEvent.Sos(source, reason))
            .onSuccess {
                log.log("SOS fired (${source.wire}) seq=${it.seq}", null)
                _fired.tryEmit(it)
            }
            .onFailure { log.log("SOS (${source.wire}) not stored", it) }
    }

    /** "오작동": logs `SOS_CANCELLED`. The SOS itself stays in the log and on the contact's timeline. */
    suspend fun cancel(): Result<EventRecord> = sessions.log(TripEvent.SosCancelled(TripTexts.SOS_CANCELLED))
        .onSuccess { log.log("SOS cancelled seq=${it.seq}", null) }
        .onFailure { log.log("SOS cancel not stored", it) }

    /**
     * Feeds the turn signal to [TurnSignalSosRule] while a trip runs, for as long as [scope] lives. Values seen with
     * no trip running are dropped, and each new trip starts the pattern from scratch.
     */
    fun watchTurnSignal(turnSignal: Flow<TurnSignal>, scope: CoroutineScope): Job = scope.launch {
        turnSignal.collect { onTurnSignal(it) }
    }

    internal suspend fun onTurnSignal(signal: TurnSignal) {
        val tripId = sessions.active.value?.trip?.id
        if (tripId != ruleTripId) {
            rule.reset()
            ruleTripId = tripId
        }
        if (tripId == null) return
        if (rule.onSignal(signal, clock.now())) fire(SosSource.TURN_SIGNAL)
    }
}
