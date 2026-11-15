package com.coral.gwigaon.trip

/** Trip lifecycle; [wire] is `trips.status` (the trips migration in supabase/migrations). IDLE is never uploaded. */
enum class TripStatus(val wire: String) {
    IDLE("idle"),
    ACTIVE("active"),
    ARRIVING("arriving"),
    SAFE_ARRIVAL_CHECK("safe_arrival_check"),
    ENDED("ended"),
}

/**
 * Where a trip is (SPEC: Idle → Active → Arriving → SafeArrivalCheck? → Ended(reason)), plus the open/close
 * pairs that must alternate (deviation, long stop, SOS, monitoring gap, check-in).
 */
data class TripState(
    val status: TripStatus,
    val arrived: Boolean = false,
    val safeArrivalConfirmed: Boolean = false,
    /** Who closed the safe-arrival check; a driver's confirmation alone doesn't end the trip (ADR 0007). */
    val safeArrivalClosedBy: SafeArrivalCloser? = null,
    val deviating: Boolean = false,
    val longStopped: Boolean = false,
    val sosActive: Boolean = false,
    val interrupted: Boolean = false,
    val checkInPending: Boolean = false,
    val endReason: EndReason? = null,
) {
    companion object {
        val IDLE = TripState(TripStatus.IDLE)
    }
}

sealed interface Transition {
    data class Accepted(val state: TripState) : Transition
    data class Rejected(val why: String) : Transition
}

/** Pure: which events are legal in which state, and the state after them. Every logged event goes through here. */
object TripStateMachine {

    fun apply(state: TripState, event: TripEvent): Transition {
        val live = state.status in LIVE
        return when (event) {
            is TripEvent.TripStarted ->
                if (state.status == TripStatus.IDLE) ok(TripState(TripStatus.ACTIVE)) else no(state, event)

            is TripEvent.NearDestination ->
                if (state.status == TripStatus.ACTIVE) {
                    // A long stop near the destination isn't one (SPEC: not raised while Arriving).
                    ok(state.copy(status = TripStatus.ARRIVING, longStopped = false))
                } else {
                    no(state, event)
                }

            is TripEvent.Arrived ->
                if (state.status in setOf(TripStatus.ACTIVE, TripStatus.ARRIVING) && !state.arrived) {
                    ok(state.copy(status = TripStatus.ARRIVING, arrived = true, longStopped = false))
                } else {
                    no(state, event)
                }

            is TripEvent.SafeArrivalCheckOpened ->
                if (state.status == TripStatus.ARRIVING && state.arrived && !state.safeArrivalConfirmed) {
                    ok(state.copy(status = TripStatus.SAFE_ARRIVAL_CHECK))
                } else {
                    no(state, event)
                }

            is TripEvent.SafeArrivalConfirmed ->
                if (state.arrived && !state.safeArrivalConfirmed &&
                    state.status in setOf(TripStatus.ARRIVING, TripStatus.SAFE_ARRIVAL_CHECK)
                ) {
                    ok(state.copy(status = TripStatus.ARRIVING, safeArrivalConfirmed = true, safeArrivalClosedBy = event.closedBy))
                } else {
                    no(state, event)
                }

            is TripEvent.TripEnded -> when {
                state.status !in setOf(TripStatus.ACTIVE, TripStatus.ARRIVING) -> no(state, event)
                event.endReason == EndReason.SAFE_ARRIVAL && (!state.safeArrivalConfirmed || state.sosActive) ->
                    no(state, event, "safe arrival needs a confirmed arrival and no active SOS")
                else -> ok(state.copy(status = TripStatus.ENDED, endReason = event.endReason))
            }

            is TripEvent.RouteDeviation -> pair(state, event, live && !state.deviating) { it.copy(deviating = true) }
            is TripEvent.DeviationCleared -> pair(state, event, live && state.deviating) { it.copy(deviating = false) }
            is TripEvent.LongStop ->
                pair(state, event, state.status == TripStatus.ACTIVE && !state.longStopped) { it.copy(longStopped = true) }
            is TripEvent.LongStopCleared -> pair(state, event, live && state.longStopped) { it.copy(longStopped = false) }
            // A repeated SOS while one is active is still logged: never drop a call for help.
            is TripEvent.Sos -> pair(state, event, live) { it.copy(sosActive = true) }
            is TripEvent.SosCancelled -> pair(state, event, live && state.sosActive) { it.copy(sosActive = false) }
            is TripEvent.MonitoringInterrupted -> pair(state, event, live && !state.interrupted) { it.copy(interrupted = true) }
            is TripEvent.MonitoringResumed -> pair(state, event, live && state.interrupted) { it.copy(interrupted = false) }
            is TripEvent.CheckInPrompted -> pair(state, event, live && !state.checkInPending) { it.copy(checkInPending = true) }
            is TripEvent.CheckInAnswered -> pair(state, event, live && state.checkInPending) { it.copy(checkInPending = false) }
            // Only reported: a lockout changes nothing about where the trip is.
            is TripEvent.PinLockout -> pair(state, event, live) { it }
            is TripEvent.SettingsChanged -> pair(state, event, live) { it }
        }
    }

    /** Rebuilds the state from a logged event list (resume after restart). Fails at the first illegal event. */
    fun replay(events: List<TripEvent>): Result<TripState> {
        var state = TripState.IDLE
        events.forEachIndexed { i, e ->
            when (val t = apply(state, e)) {
                is Transition.Accepted -> state = t.state
                is Transition.Rejected -> return Result.failure(IllegalStateException("event #$i ${e.type.wire}: ${t.why}"))
            }
        }
        return Result.success(state)
    }

    private val LIVE = setOf(TripStatus.ACTIVE, TripStatus.ARRIVING, TripStatus.SAFE_ARRIVAL_CHECK)

    private fun ok(state: TripState) = Transition.Accepted(state)

    private fun no(state: TripState, event: TripEvent, detail: String? = null) =
        Transition.Rejected("${event.type.wire} not allowed in ${state.status.wire}" + (detail?.let { ": $it" } ?: ""))

    private inline fun pair(state: TripState, event: TripEvent, allowed: Boolean, next: (TripState) -> TripState) =
        if (allowed) ok(next(state)) else no(state, event)
}
