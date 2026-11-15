package com.coral.gwigaon.arrival

import com.coral.gwigaon.trip.EndReason
import com.coral.gwigaon.trip.FakeTripStore
import com.coral.gwigaon.trip.MonitoringConfig
import com.coral.gwigaon.trip.PinHasher
import com.coral.gwigaon.trip.PinResult
import com.coral.gwigaon.trip.SafeArrivalCloser
import com.coral.gwigaon.trip.SosSource
import com.coral.gwigaon.trip.TestClock
import com.coral.gwigaon.trip.TripEvent
import com.coral.gwigaon.trip.TripSessionRepository
import com.coral.gwigaon.trip.TripSetup
import com.coral.gwigaon.trip.TripState
import com.coral.gwigaon.trip.TripStatus
import com.coral.gwigaon.trip.TripTexts
import com.coral.gwigaon.vehicle.Destination
import com.coral.gwigaon.vehicle.Gear
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class SafeArrivalRuleTest {
    private val clock = TestClock()
    private val rule = SafeArrivalRule(MonitoringConfig.DEFAULT) // N = 3 min
    private val arrived = TripState(TripStatus.ARRIVING, arrived = true)
    private val checkOpen = arrived.copy(status = TripStatus.SAFE_ARRIVAL_CHECK)

    private fun at(state: TripState, gear: Gear?, seat: Boolean?, after: Duration = Duration.ZERO): SafeArrivalDecision {
        clock.advance(after)
        return rule.evaluate(state, gear, seat, clock.now())
    }

    @Test
    fun opensAfterNParkedWithThePassengerSeatedNotBefore() {
        assertEquals(SafeArrivalDecision.None, at(arrived, Gear.PARK, true))
        assertEquals(SafeArrivalDecision.None, at(arrived, Gear.PARK, true, 2.minutes + 59.seconds))
        assertEquals(SafeArrivalDecision.Open(3.minutes), at(arrived, Gear.PARK, true, 1.seconds))
    }

    @Test
    fun leavingParkOrAnUnknownSeatRestartsTheWait() {
        at(arrived, Gear.PARK, true)
        at(arrived, Gear.DRIVE, true, 2.minutes)
        at(arrived, Gear.PARK, true, 1.seconds)
        assertEquals(SafeArrivalDecision.None, at(arrived, Gear.PARK, true, 2.minutes))
        at(arrived, Gear.PARK, null, 30.seconds)
        at(arrived, Gear.PARK, true, 1.seconds)
        assertEquals(SafeArrivalDecision.None, at(arrived, Gear.PARK, true, 2.minutes + 59.seconds))
        assertEquals(SafeArrivalDecision.Open(3.minutes), at(arrived, Gear.PARK, true, 1.seconds))
    }

    @Test
    fun seatEmptyInParkConfirmsWithoutACheck() {
        assertEquals(SafeArrivalDecision.Confirm(SafeArrivalCloser.SEAT_EMPTY), at(arrived, Gear.PARK, false))
    }

    @Test
    fun seatEmptyWhileStillInDriveConfirmsNothing() {
        assertEquals(SafeArrivalDecision.None, at(arrived, Gear.DRIVE, false))
        assertEquals(SafeArrivalDecision.None, at(arrived, null, false))
    }

    @Test
    fun anOpenCheckClosesOnlyByAnEmptySeatHereWhateverTheGear() {
        assertEquals(SafeArrivalDecision.None, at(checkOpen, Gear.PARK, true, 10.minutes))
        assertEquals(SafeArrivalDecision.None, at(checkOpen, Gear.PARK, null))
        assertEquals(SafeArrivalDecision.Confirm(SafeArrivalCloser.SEAT_EMPTY), at(checkOpen, null, false))
    }

    @Test
    fun nothingBeforeArrivalOrAfterConfirmation() {
        assertEquals(SafeArrivalDecision.None, at(TripState(TripStatus.ACTIVE), Gear.PARK, false))
        assertEquals(SafeArrivalDecision.None, at(TripState(TripStatus.ARRIVING), Gear.PARK, false))
        assertEquals(SafeArrivalDecision.None, at(arrived.copy(safeArrivalConfirmed = true), Gear.PARK, false))
        at(TripState(TripStatus.ACTIVE), Gear.PARK, true, 10.minutes)
        assertEquals(SafeArrivalDecision.None, at(arrived, Gear.PARK, true)) // the wait starts at arrival
    }
}

class SafeArrivalMonitorTest {
    private val store = FakeTripStore()
    private val clock = TestClock()
    private var ids = 0
    private val sessions = TripSessionRepository(store, PinHasher(iterations = 1_000), clock) { "trip-${++ids}" }
    private val gear = MutableStateFlow<Gear?>(Gear.DRIVE)
    private val seat = MutableStateFlow<Boolean?>(true)
    private val logged = mutableListOf<Pair<String, Throwable?>>()
    private val monitor = SafeArrivalMonitor(sessions, gear, seat, clock, MonitoringConfig.DEMO) { m, e -> logged += m to e }
    private val setup = TripSetup(Destination("양재역", 37.48460, 127.03420), "언니", "2580")

    private fun events(trip: String = "trip-1") = store.events[trip].orEmpty().map { it.event }
    private fun types(trip: String = "trip-1") = events(trip).map { it.type.wire }

    private suspend fun arrive() {
        sessions.start(setup).getOrThrow()
        sessions.log(TripEvent.NearDestination("목적지 500m 이내")).getOrThrow()
        sessions.log(TripEvent.Arrived("목적지 도착")).getOrThrow()
    }

    private suspend fun park(seated: Boolean?) {
        gear.value = Gear.PARK
        seat.value = seated
        monitor.check()
    }

    private suspend fun wait(d: Duration) {
        clock.advance(d)
        monitor.check()
    }

    @Test
    fun passengerGetsOutAtTheDestinationAndTheTripEndsAsSafeArrival() = runTest {
        arrive()
        park(seated = true)
        wait(10.seconds)
        seat.value = false
        monitor.check()

        assertEquals(listOf("SAFE_ARRIVAL_CONFIRMED", "TRIP_ENDED"), types().takeLast(2))
        assertEquals(TripEvent.SafeArrivalConfirmed(SafeArrivalCloser.SEAT_EMPTY, "승객 하차 확인"), events()[3])
        assertEquals(TripEvent.TripEnded(EndReason.SAFE_ARRIVAL, "안전 귀가"), events().last())
        assertNull(sessions.active.value)
    }

    @Test
    fun stillSeatedAfterNOpensTheCheckAndTheTripCantEndWhileItIsOpen() = runTest {
        arrive()
        park(seated = true)
        wait(29.seconds)
        assertEquals("ARRIVED", types().last())
        wait(1.seconds)
        assertEquals(TripEvent.SafeArrivalCheckOpened("도착 후 30초째 승객이 하차하지 않음"), events().last())
        assertEquals(TripStatus.SAFE_ARRIVAL_CHECK, sessions.active.value!!.state.status)

        wait(5.minutes)
        assertEquals(1, types().count { it == "SAFE_ARRIVAL_CHECK_OPENED" })
        assertTrue(sessions.log(TripEvent.TripEnded(EndReason.SAFE_ARRIVAL)).isFailure)
        assertTrue(sessions.endByPassenger("2580").isFailure)

        seat.value = false
        monitor.check()
        assertEquals(listOf("SAFE_ARRIVAL_CHECK_OPENED", "SAFE_ARRIVAL_CONFIRMED", "TRIP_ENDED"), types().takeLast(3))
    }

    @Test
    fun theDriverConfirmingClosesTheCheckAndTheTripEndsWhenThePassengerGetsOut() = runTest {
        arrive()
        park(seated = true)
        wait(30.seconds)
        sessions.log(TripEvent.SafeArrivalConfirmed(SafeArrivalCloser.DRIVER_CONFIRMED, "기사가 승객 반응 확인")).getOrThrow()
        monitor.check()
        // ADR 0007: the driver's tap closes the check but doesn't end monitoring.
        assertEquals("SAFE_ARRIVAL_CONFIRMED", types().last())
        assertEquals(TripStatus.ARRIVING, sessions.active.value!!.state.status)
        seat.value = false
        monitor.check()

        val confirmed = events().filterIsInstance<TripEvent.SafeArrivalConfirmed>().single()
        assertEquals(SafeArrivalCloser.DRIVER_CONFIRMED, confirmed.closedBy)
        assertEquals(EndReason.SAFE_ARRIVAL, (events().last() as TripEvent.TripEnded).endReason)
    }

    @Test
    fun afterTheDriverConfirmsOnlyAnEmptySeatInParkEndsTheTrip() = runTest {
        arrive()
        park(seated = true)
        wait(30.seconds)
        sessions.log(TripEvent.SafeArrivalConfirmed(SafeArrivalCloser.DRIVER_CONFIRMED)).getOrThrow()
        wait(10.minutes) // the passenger stays seated: no end, no second check
        assertEquals("SAFE_ARRIVAL_CONFIRMED", types().last())
        assertEquals("승객 하차를 기다리는 중", TripTexts.notificationStatus(sessions.active.value!!.state))

        gear.value = Gear.DRIVE // the driver drives off: still monitored
        seat.value = false
        monitor.check()
        assertEquals("SAFE_ARRIVAL_CONFIRMED", types().last())

        gear.value = Gear.PARK
        monitor.check()
        assertEquals(TripEvent.TripEnded(EndReason.SAFE_ARRIVAL, "승객 하차 후 안전 귀가"), events().last())
        assertNull(sessions.active.value)
    }

    @Test
    fun afterTheDriverConfirmsThePassengerCanStillEndItWithThePin() = runTest {
        arrive()
        park(seated = true)
        wait(30.seconds)
        sessions.log(TripEvent.SafeArrivalConfirmed(SafeArrivalCloser.DRIVER_CONFIRMED)).getOrThrow()
        monitor.check()
        assertTrue(sessions.endByPassenger("2580").getOrThrow() is PinResult.Accepted)
        assertEquals(EndReason.ENDED_BY_PASSENGER, (events().last() as TripEvent.TripEnded).endReason)
    }

    @Test
    fun thePassengerPinClosesTheCheckWithTheLockout() = runTest {
        arrive()
        assertTrue(sessions.confirmSafeArrivalByPin("2580", "x").isFailure) // no check open yet
        park(seated = true)
        wait(30.seconds)

        assertTrue(sessions.confirmSafeArrivalByPin("0000", "승객이 PIN으로 상태 확인").getOrThrow() is PinResult.Wrong)
        assertEquals(TripStatus.SAFE_ARRIVAL_CHECK, sessions.active.value!!.state.status)
        assertTrue(sessions.confirmSafeArrivalByPin("2580", "승객이 PIN으로 상태 확인").getOrThrow() is PinResult.Accepted)
        monitor.check()

        assertEquals(
            TripEvent.SafeArrivalConfirmed(SafeArrivalCloser.PASSENGER_PIN, "승객이 PIN으로 상태 확인"),
            events().filterIsInstance<TripEvent.SafeArrivalConfirmed>().single(),
        )
        assertEquals("TRIP_ENDED", types().last())
    }

    @Test
    fun anOpenSosKeepsAConfirmedTripRunning() = runTest {
        arrive()
        sessions.log(TripEvent.Sos(SosSource.ON_SCREEN)).getOrThrow()
        park(seated = false)

        assertEquals("SAFE_ARRIVAL_CONFIRMED", types().last())
        assertTrue(sessions.active.value!!.state.safeArrivalConfirmed)

        sessions.log(TripEvent.SosCancelled()).getOrThrow()
        monitor.check()
        assertEquals("TRIP_ENDED", types().last())
    }

    @Test
    fun failedLogsAreReportedAndRetried() = runTest {
        arrive()
        store.failAppend = true
        park(seated = false)
        assertEquals("SAFE_ARRIVAL_CONFIRMED not stored", logged.last().first)
        store.failAppend = false
        monitor.check()
        assertEquals("TRIP_ENDED", types().last())
        assertTrue(logged.last().first.startsWith("TRIP_ENDED seq="))
    }

    @Test
    fun aNewTripStartsWithAFreshWait() = runTest {
        arrive()
        park(seated = true)
        wait(20.seconds)
        sessions.log(TripEvent.SafeArrivalConfirmed(SafeArrivalCloser.DRIVER_CONFIRMED)).getOrThrow()
        seat.value = false // the passenger gets out (ADR 0007)
        monitor.check()
        assertNull(sessions.active.value)
        seat.value = true

        sessions.start(setup).getOrThrow()
        sessions.log(TripEvent.NearDestination()).getOrThrow()
        sessions.log(TripEvent.Arrived()).getOrThrow()
        monitor.check()
        wait(20.seconds)
        assertEquals("ARRIVED", types("trip-2").last())
        wait(10.seconds)
        assertEquals("SAFE_ARRIVAL_CHECK_OPENED", types("trip-2").last())
    }

    @Test
    fun watchReactsToSignalsAndTicks() = runTest {
        arrive()
        val job = monitor.watch(backgroundScope)
        gear.value = Gear.PARK
        runCurrent()
        clock.advance(30.seconds)
        advanceTimeBy(MonitoringConfig.DEMO.routeTick + 1.seconds)
        assertEquals("SAFE_ARRIVAL_CHECK_OPENED", types().last())
        seat.value = false
        runCurrent()
        assertEquals("TRIP_ENDED", types().last())
        job.cancel()
    }
}
