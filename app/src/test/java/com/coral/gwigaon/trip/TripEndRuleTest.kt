package com.coral.gwigaon.trip

import com.coral.gwigaon.vehicle.Destination
import com.coral.gwigaon.vehicle.GeoFix
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TripEndRuleTest {
    private val active = TripState(TripStatus.ACTIVE)
    private val arriving = TripState(TripStatus.ARRIVING, arrived = true)
    private val check = TripState(TripStatus.SAFE_ARRIVAL_CHECK, arrived = true)

    @Test
    fun drivingOffersNothingInEitherMode() {
        assertEquals(EndOption.NotOffered, TripEndRule.option(arriving, pinOn = false, parked = false, remainingM = null))
        assertEquals(EndOption.NotOffered, TripEndRule.option(arriving, pinOn = true, parked = false, remainingM = null))
    }

    @Test
    fun withThePinOnTodaysBehaviourStays() {
        assertEquals(EndOption.NeedsPin, TripEndRule.option(active, pinOn = true, parked = true, remainingM = 1200))
        assertEquals(EndOption.NeedsPin, TripEndRule.option(arriving, pinOn = true, parked = true, remainingM = 0))
        // The open check doesn't block a PIN trip: the keypad closes it (Checkpoint F review R6).
        assertEquals(EndOption.NeedsPin, TripEndRule.option(check, pinOn = true, parked = true, remainingM = 0))
    }

    @Test
    fun withoutAPinArrivingIsAPlainEnd() {
        assertEquals(EndOption.Plain, TripEndRule.option(arriving, pinOn = false, parked = true, remainingM = 30))
        assertEquals(EndOption.Plain, TripEndRule.option(TripState(TripStatus.ARRIVING), pinOn = false, parked = true, remainingM = 400))
        // After the check closed (driver confirmed), the trip is back in Arriving.
        val closed = arriving.copy(safeArrivalConfirmed = true, safeArrivalClosedBy = SafeArrivalCloser.DRIVER_CONFIRMED)
        assertEquals(EndOption.Plain, TripEndRule.option(closed, pinOn = false, parked = true, remainingM = null))
    }

    @Test
    fun withoutAPinAnOpenCheckBlocksTheEnd() {
        assertEquals(EndOption.Blocked, TripEndRule.option(check, pinOn = false, parked = true, remainingM = 0))
    }

    @Test
    fun withoutAPinAnywhereElseIsAnEarlyEndByHoldWithAReason() {
        assertEquals(
            EndOption.Hold("목적지 밖에서 운행 종료 · 1.2km 남음", "1.2km 남음"),
            TripEndRule.option(active, pinOn = false, parked = true, remainingM = 1200),
        )
        assertEquals(EndOption.Hold("목적지 밖에서 운행 종료"), TripEndRule.option(active, pinOn = false, parked = true, remainingM = null))
    }

    @Test
    fun theOneKilometreBoundaryDecidesByDistanceNotStatus() {
        // ADR 0020: within 1 km plain even if the trip never came "near"; beyond 1 km a hold even after it did.
        assertEquals(EndOption.Plain, TripEndRule.option(active, pinOn = false, parked = true, remainingM = 900))
        assertEquals(EndOption.Plain, TripEndRule.option(active, pinOn = false, parked = true, remainingM = 1000))
        assertEquals(
            EndOption.Hold("목적지 밖에서 운행 종료 · 1.6km 남음", "1.6km 남음"),
            TripEndRule.option(arriving, pinOn = false, parked = true, remainingM = 1600),
        )
        assertTrue(TripEndRule.near(arriving, remainingM = null))
        assertEquals(false, TripEndRule.near(active, remainingM = null))
        assertNull(TripTexts.left(null))
    }

    @Test
    fun anEndedTripOffersNothing() {
        assertEquals(EndOption.NotOffered, TripEndRule.option(TripState(TripStatus.ENDED), pinOn = false, parked = true, remainingM = null))
    }
}

class TripEndWithoutPinTest {
    private val store = FakeTripStore()
    private val clock = TestClock()
    private val hasher = PinHasher(iterations = 1_000)
    private val repo = TripSessionRepository(store, hasher, clock) { "trip-1" }
    private val home = Destination("집", 37.5, 127.0)
    private val noPin = TripSetup(home, "언니")
    private val withPin = TripSetup(home, "언니", "2580")

    private fun ended() = store.events.getValue("trip-1").last().event as TripEvent.TripEnded

    @Test
    fun midTripEndIsEarlyWithTheDistanceLeft() = runTest {
        repo.start(noPin).getOrThrow()
        repo.lastFix = GeoFix(37.5108, 127.0, 0) // about 1.2 km north of home
        assertEquals(EndOption.Hold("목적지 밖에서 운행 종료 · 1.2km 남음", "1.2km 남음"), repo.endOption(parked = true))
        assertEquals(EndReason.ENDED_EARLY, repo.endWithoutPin(parked = true).getOrThrow())
        assertEquals(TripEvent.TripEnded(EndReason.ENDED_EARLY, "목적지 밖에서 운행 종료 · 1.2km 남음"), ended())
        assertNull(repo.active.value)
        assertEquals(TripStatus.ENDED, store.trips.getValue("trip-1").status)
    }

    @Test
    fun arrivingEndIsPlain() = runTest {
        repo.start(noPin).getOrThrow()
        repo.log(TripEvent.NearDestination()).getOrThrow()
        assertEquals(EndOption.Plain, repo.endOption(parked = true))
        assertEquals(EndReason.ENDED_AT_ARRIVAL, repo.endWithoutPin(parked = true).getOrThrow())
        assertEquals(TripEvent.TripEnded(EndReason.ENDED_AT_ARRIVAL, TripTexts.ENDED_AT_ARRIVAL), ended())
    }

    @Test
    fun drivingOrAnOpenCheckRefuses() = runTest {
        repo.start(noPin).getOrThrow()
        assertTrue(repo.endWithoutPin(parked = false).isFailure)
        repo.log(TripEvent.Arrived()).getOrThrow()
        repo.log(TripEvent.SafeArrivalCheckOpened()).getOrThrow()
        assertEquals(EndOption.Blocked, repo.endOption(parked = true))
        assertTrue(repo.endWithoutPin(parked = true).isFailure)
        assertTrue(repo.active.value != null)
    }

    @Test
    fun aTripWithAPinCannotEndWithoutIt() = runTest {
        repo.start(withPin).getOrThrow()
        assertEquals(EndOption.NeedsPin, repo.endOption(parked = true))
        assertTrue(repo.endWithoutPin(parked = true).isFailure)
        assertTrue(repo.endByPassenger("2580").getOrThrow() is PinResult.Accepted)
        assertEquals(EndReason.ENDED_BY_PASSENGER, ended().endReason)
    }

    @Test
    fun aTripWithoutAPinHasNoPinPaths() = runTest {
        repo.start(noPin).getOrThrow()
        assertTrue(repo.checkPin("0000").isFailure)
        assertTrue(repo.endByPassenger("0000").isFailure)
        repo.log(TripEvent.Arrived()).getOrThrow()
        repo.log(TripEvent.SafeArrivalCheckOpened()).getOrThrow()
        // No passenger_pin close without a PIN: only seat empty or the driver's 승객 반응 있음.
        assertTrue(repo.confirmSafeArrivalByPin("0000", "PIN 확인").isFailure)
        assertEquals(0, store.trips.getValue("trip-1").pinAttempts.failures)
        assertTrue(repo.log(TripEvent.SafeArrivalConfirmed(SafeArrivalCloser.DRIVER_CONFIRMED)).isSuccess)
        assertEquals(EndOption.Plain, repo.endOption(parked = true))
    }

    @Test
    fun turningMonitoringOffMidTripFollowsTheSameRule() = runTest {
        // There is no other switch: the no-PIN reasons only go through endWithoutPin, never through log().
        repo.start(noPin).getOrThrow()
        assertTrue(repo.log(TripEvent.TripEnded(EndReason.ENDED_EARLY, "x")).isFailure)
        assertTrue(repo.log(TripEvent.TripEnded(EndReason.ENDED_AT_ARRIVAL, "x")).isFailure)
        assertTrue(repo.active.value != null)
        assertEquals(EndReason.ENDED_EARLY, repo.endWithoutPin(parked = true).getOrThrow())
    }

    @Test
    fun noTripNoOption() = runTest {
        assertNull(repo.endOption(parked = true))
        assertTrue(repo.endWithoutPin(parked = true).isFailure)
    }
}
