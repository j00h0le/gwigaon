package com.coral.gwigaon.sos

import com.coral.gwigaon.trip.EndReason
import com.coral.gwigaon.trip.EventRecord
import com.coral.gwigaon.trip.FakeTripStore
import com.coral.gwigaon.trip.MonitoringConfig
import com.coral.gwigaon.trip.PinHasher
import com.coral.gwigaon.trip.SosSource
import com.coral.gwigaon.trip.TestClock
import com.coral.gwigaon.trip.TripEvent
import com.coral.gwigaon.trip.TripSessionRepository
import com.coral.gwigaon.trip.TripSetup
import com.coral.gwigaon.trip.TripTexts
import com.coral.gwigaon.vehicle.Destination
import com.coral.gwigaon.vehicle.GeoFix
import com.coral.gwigaon.vehicle.TurnSignal
import com.coral.gwigaon.vehicle.TurnSignal.LEFT
import com.coral.gwigaon.vehicle.TurnSignal.RIGHT
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.milliseconds

class SosControllerTest {
    private val store = FakeTripStore()
    private val clock = TestClock()
    private var ids = 0
    private val sessions = TripSessionRepository(store, PinHasher(iterations = 1_000), clock) { "trip-${++ids}" }
    private val logged = mutableListOf<Pair<String, Throwable?>>()
    private val sos = SosController(sessions, clock, MonitoringConfig.DEFAULT) { m, e -> logged += m to e }
    private val setup = TripSetup(Destination("집", 37.5, 127.0), "언니", "2580")

    private fun events(tripId: String = "trip-1") = store.events[tripId].orEmpty().map { it.event }

    private suspend fun wiggle(vararg signals: TurnSignal) {
        for (s in signals) {
            sos.onTurnSignal(s)
            clock.advance(500.milliseconds)
        }
    }

    @Test
    fun onScreenSosIsStoredWithSourceReasonAndLocation() = runTest {
        sessions.start(setup).getOrThrow()
        sessions.lastFix = GeoFix(37.51, 127.05, 0)

        val record = sos.fire(SosSource.ON_SCREEN).getOrThrow()

        assertEquals(TripEvent.Sos(SosSource.ON_SCREEN, TripTexts.SOS_ON_SCREEN), record.event)
        assertEquals(37.51, record.lat!!, 0.0)
        assertEquals(127.05, record.lng!!, 0.0)
        assertTrue(sessions.active.value!!.state.sosActive)
    }

    @Test
    fun firedAnnouncesEachStoredSosForImmediateUpload() = runTest {
        sessions.start(setup).getOrThrow()
        val seen = mutableListOf<EventRecord>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { sos.fired.collect { seen += it } }

        val first = sos.fire(SosSource.ON_SCREEN).getOrThrow()
        val second = sos.fire(SosSource.ON_SCREEN).getOrThrow() // a repeat is never dropped

        assertEquals(listOf(first, second), seen)
        assertEquals(2, events().count { it is TripEvent.Sos })
    }

    @Test
    fun sosWithoutATripFailsAndIsReported() = runTest {
        assertTrue(sos.fire(SosSource.ON_SCREEN).isFailure)
        assertTrue(logged.single().second != null)
    }

    @Test
    fun sosFailsLoudlyWhenItCantBeStored() = runTest {
        sessions.start(setup).getOrThrow()
        store.failAppend = true
        assertTrue(sos.fire(SosSource.ON_SCREEN).isFailure)
        assertTrue(logged.last().second != null)
    }

    @Test
    fun cancelLogsSosCancelledAndKeepsTheSos() = runTest {
        sessions.start(setup).getOrThrow()
        sos.fire(SosSource.ON_SCREEN).getOrThrow()

        sos.cancel().getOrThrow()

        assertEquals(
            listOf(TripEvent.Sos(SosSource.ON_SCREEN, TripTexts.SOS_ON_SCREEN), TripEvent.SosCancelled(TripTexts.SOS_CANCELLED)),
            events().drop(1),
        )
        assertFalse(sessions.active.value!!.state.sosActive)
    }

    @Test
    fun cancelWithoutAnActiveSosIsRefused() = runTest {
        sessions.start(setup).getOrThrow()
        assertTrue(sos.cancel().isFailure)
    }

    @Test
    fun activeSosBlocksEndingAsSafeArrival() = runTest {
        // The state machine rule this path relies on: an open SOS can't be closed by a safe-arrival end.
        sessions.start(setup).getOrThrow()
        sos.fire(SosSource.ON_SCREEN).getOrThrow()
        assertTrue(sessions.log(TripEvent.TripEnded(EndReason.SAFE_ARRIVAL)).isFailure)
    }

    @Test
    fun turnSignalPatternFiresSosDuringATrip() = runTest {
        sessions.start(setup).getOrThrow()
        wiggle(LEFT, RIGHT, LEFT, RIGHT, LEFT)
        assertEquals(TripEvent.Sos(SosSource.TURN_SIGNAL, TripTexts.SOS_TURN_SIGNAL), events().last())
    }

    @Test
    fun turnSignalPatternWithoutATripDoesNothing() = runTest {
        wiggle(LEFT, RIGHT, LEFT, RIGHT, LEFT)
        assertTrue(store.events.isEmpty())
        assertTrue(logged.isEmpty())
    }

    @Test
    fun eachTripStartsThePatternFromScratch() = runTest {
        sessions.start(setup).getOrThrow()
        wiggle(LEFT, RIGHT, LEFT, RIGHT) // three changes in trip 1
        sessions.endByPassenger("2580").getOrThrow()
        sessions.start(setup).getOrThrow()

        wiggle(LEFT) // trip 2: only a starting direction, not a fourth change
        assertTrue(events("trip-2").none { it is TripEvent.Sos })
        wiggle(RIGHT, LEFT, RIGHT, LEFT)
        assertEquals(1, events("trip-2").count { it is TripEvent.Sos })
        assertTrue(events("trip-1").none { it is TripEvent.Sos })
    }

    @Test
    fun watchTurnSignalFollowsTheFlow() = runTest {
        sessions.start(setup).getOrThrow()
        val lever = MutableSharedFlow<TurnSignal>()
        sos.watchTurnSignal(lever, CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler)))
        for (s in listOf(LEFT, RIGHT, LEFT, RIGHT, LEFT)) {
            lever.emit(s)
            clock.advance(500.milliseconds)
        }
        assertEquals(TripEvent.Sos(SosSource.TURN_SIGNAL, TripTexts.SOS_TURN_SIGNAL), events().last())
    }
}
