package com.coral.gwigaon.trip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TripStateMachineTest {
    private val started = TripStateMachine.apply(TripState.IDLE, TripEvent.TripStarted()).accepted()
    private val arriving = TripStateMachine.apply(started, TripEvent.NearDestination()).accepted()
    private val arrived = TripStateMachine.apply(arriving, TripEvent.Arrived()).accepted()
    private val checking = TripStateMachine.apply(arrived, TripEvent.SafeArrivalCheckOpened()).accepted()

    @Test
    fun happyPathWithSafeArrivalCheck() {
        assertEquals(TripStatus.ACTIVE, started.status)
        assertEquals(TripStatus.ARRIVING, arriving.status)
        assertTrue(arrived.arrived)
        assertEquals(TripStatus.SAFE_ARRIVAL_CHECK, checking.status)
        val confirmed = apply(checking, TripEvent.SafeArrivalConfirmed(SafeArrivalCloser.SEAT_EMPTY))
        assertEquals(TripStatus.ARRIVING, confirmed.status)
        assertTrue(confirmed.safeArrivalConfirmed)
        val ended = apply(confirmed, TripEvent.TripEnded(EndReason.SAFE_ARRIVAL))
        assertEquals(TripStatus.ENDED, ended.status)
        assertEquals(EndReason.SAFE_ARRIVAL, ended.endReason)
    }

    @Test
    fun seatEmptyBeforeTheCheckOpensConfirmsDirectly() {
        val confirmed = apply(arrived, TripEvent.SafeArrivalConfirmed(SafeArrivalCloser.SEAT_EMPTY))
        assertTrue(confirmed.safeArrivalConfirmed)
        assertEquals(TripStatus.ENDED, apply(confirmed, TripEvent.TripEnded(EndReason.SAFE_ARRIVAL)).status)
    }

    @Test
    fun arrivedWithoutNearDestinationStillArrives() {
        val s = apply(started, TripEvent.Arrived())
        assertEquals(TripStatus.ARRIVING, s.status)
        assertTrue(s.arrived)
    }

    @Test
    fun passengerCanEndWithPinBeforeArrival() {
        val ended = apply(started, TripEvent.TripEnded(EndReason.ENDED_BY_PASSENGER))
        assertEquals(TripStatus.ENDED, ended.status)
    }

    @Test
    fun cannotEndAsSafeArrivalWithoutConfirmation() {
        rejected(arrived, TripEvent.TripEnded(EndReason.SAFE_ARRIVAL))
        rejected(started, TripEvent.TripEnded(EndReason.SAFE_ARRIVAL))
    }

    @Test
    fun cannotEndWhileSafeArrivalCheckIsOpen() {
        rejected(checking, TripEvent.TripEnded(EndReason.ENDED_BY_PASSENGER))
        rejected(checking, TripEvent.TripEnded(EndReason.SAFE_ARRIVAL))
    }

    @Test
    fun checkOpensOnlyAfterArrival() {
        rejected(started, TripEvent.SafeArrivalCheckOpened())
        rejected(arriving, TripEvent.SafeArrivalCheckOpened())
        rejected(checking, TripEvent.SafeArrivalCheckOpened())
        rejected(arriving, TripEvent.SafeArrivalConfirmed(SafeArrivalCloser.SEAT_EMPTY))
        val confirmed = apply(checking, TripEvent.SafeArrivalConfirmed(SafeArrivalCloser.PASSENGER_PIN))
        rejected(confirmed, TripEvent.SafeArrivalCheckOpened())
        rejected(confirmed, TripEvent.SafeArrivalConfirmed(SafeArrivalCloser.SEAT_EMPTY))
    }

    @Test
    fun idleAcceptsOnlyTripStarted() {
        rejected(TripState.IDLE, TripEvent.NearDestination())
        rejected(TripState.IDLE, TripEvent.Sos(SosSource.ON_SCREEN))
        rejected(TripState.IDLE, TripEvent.TripEnded(EndReason.ENDED_BY_PASSENGER))
        rejected(started, TripEvent.TripStarted())
    }

    @Test
    fun endedAcceptsNothing() {
        val ended = apply(started, TripEvent.TripEnded(EndReason.ENDED_BY_PASSENGER))
        for (e in allEvents()) rejected(ended, e)
    }

    @Test
    fun nearDestinationOnlyOnceAndArrivedOnlyOnce() {
        rejected(arriving, TripEvent.NearDestination())
        rejected(arrived, TripEvent.Arrived())
    }

    @Test
    fun deviationOpensAndClearsInPairs() {
        val dev = apply(started, TripEvent.RouteDeviation(distanceM = 1200, reason = "목적지와 다른 방향으로 1.2km 이동"))
        assertTrue(dev.deviating)
        rejected(dev, TripEvent.RouteDeviation(distanceM = 1300))
        val cleared = apply(dev, TripEvent.DeviationCleared())
        assertEquals(false, cleared.deviating)
        rejected(cleared, TripEvent.DeviationCleared())
        assertTrue(apply(arriving, TripEvent.RouteDeviation(distanceM = 400)).deviating)
    }

    @Test
    fun longStopOnlyWhileActive() {
        val stopped = apply(started, TripEvent.LongStop(durationS = 420))
        assertTrue(stopped.longStopped)
        rejected(stopped, TripEvent.LongStop(durationS = 480))
        assertEquals(false, apply(stopped, TripEvent.LongStopCleared()).longStopped)
        rejected(started, TripEvent.LongStopCleared())
        rejected(arriving, TripEvent.LongStop(durationS = 420)) // SPEC: not raised while Arriving
    }

    @Test
    fun arrivingClearsAnOpenLongStop() {
        val stopped = apply(started, TripEvent.LongStop(durationS = 420))
        val near = apply(stopped, TripEvent.NearDestination())
        assertEquals(false, near.longStopped)
        assertEquals(false, apply(stopped, TripEvent.LongStopCleared()).longStopped)
    }

    @Test
    fun sosWorksInEveryLiveStateAndCancelsOnce() {
        for (s in listOf(started, arriving, arrived, checking)) {
            val sos = apply(s, TripEvent.Sos(SosSource.TURN_SIGNAL))
            assertTrue(sos.sosActive)
            assertEquals(s.status, sos.status)
            assertTrue(apply(sos, TripEvent.Sos(SosSource.ON_SCREEN)).sosActive) // a second press is still logged
            val cancelled = apply(sos, TripEvent.SosCancelled())
            assertEquals(false, cancelled.sosActive)
            rejected(cancelled, TripEvent.SosCancelled())
        }
    }

    @Test
    fun pinLockoutIsOnlyReportedInEveryLiveStateAndNowhereElse() {
        val lockout = TripEvent.PinLockout(failures = 5, lockedS = 30)
        for (s in listOf(started, arriving, arrived, checking)) assertEquals(s, apply(s, lockout))
        rejected(TripState.IDLE, lockout)
        rejected(apply(started, TripEvent.TripEnded(EndReason.ENDED_BY_PASSENGER)), lockout)
    }

    @Test
    fun cannotEndAsSafeArrivalDuringActiveSos() {
        val confirmed = apply(arrived, TripEvent.SafeArrivalConfirmed(SafeArrivalCloser.SEAT_EMPTY))
        val sos = apply(confirmed, TripEvent.Sos(SosSource.ON_SCREEN))
        rejected(sos, TripEvent.TripEnded(EndReason.SAFE_ARRIVAL))
        assertEquals(TripStatus.ENDED, apply(sos, TripEvent.TripEnded(EndReason.ENDED_BY_PASSENGER)).status)
    }

    @Test
    fun monitoringInterruptionPairs() {
        val gap = apply(started, TripEvent.MonitoringInterrupted(gapS = 120))
        assertTrue(gap.interrupted)
        rejected(gap, TripEvent.MonitoringInterrupted(gapS = 30))
        assertEquals(false, apply(gap, TripEvent.MonitoringResumed()).interrupted)
        rejected(started, TripEvent.MonitoringResumed())
    }

    @Test
    fun checkInPromptThenAnswer() {
        val prompted = apply(started, TripEvent.CheckInPrompted(level = 1, cue = CueKind.LOUD))
        assertTrue(prompted.checkInPending)
        rejected(started, TripEvent.CheckInAnswered(CheckInAnswer.OK))
        assertEquals(false, apply(prompted, TripEvent.CheckInAnswered(CheckInAnswer.OK)).checkInPending)
        rejected(prompted, TripEvent.CheckInPrompted(level = 2, cue = CueKind.DISTRESS))
    }

    @Test
    fun replayRebuildsStateAndStopsAtTheFirstIllegalEvent() {
        val events = listOf(
            TripEvent.TripStarted(),
            TripEvent.RouteDeviation(distanceM = 500),
            TripEvent.NearDestination(),
            TripEvent.Arrived(),
        )
        val state = TripStateMachine.replay(events).getOrThrow()
        assertEquals(TripStatus.ARRIVING, state.status)
        assertTrue(state.deviating && state.arrived)

        val bad = TripStateMachine.replay(listOf(TripEvent.TripStarted(), TripEvent.SosCancelled()))
        assertTrue(bad.isFailure)
        assertTrue(bad.exceptionOrNull()?.message.orEmpty().contains("#1 SOS_CANCELLED"))
    }

    @Test
    fun statusWireNamesMatchTheTripsTable() {
        assertEquals(
            listOf("idle", "active", "arriving", "safe_arrival_check", "ended"),
            TripStatus.entries.map { it.wire },
        )
    }

    private fun apply(state: TripState, event: TripEvent) = TripStateMachine.apply(state, event).accepted()

    private fun rejected(state: TripState, event: TripEvent) {
        val r = TripStateMachine.apply(state, event)
        assertTrue("expected $event to be rejected in $state, got $r", r is Transition.Rejected)
    }

    private fun Transition.accepted(): TripState = when (this) {
        is Transition.Accepted -> state
        is Transition.Rejected -> throw AssertionError("rejected: $why")
    }
}
