package com.coral.gwigaon.route

import com.coral.gwigaon.trip.ActiveTrip
import com.coral.gwigaon.trip.PinAttempts
import com.coral.gwigaon.trip.StoredTrip
import com.coral.gwigaon.trip.TripState
import com.coral.gwigaon.trip.TripStatus
import com.coral.gwigaon.vehicle.Destination
import com.coral.gwigaon.vehicle.GeoFix
import com.coral.gwigaon.vehicle.NaviRemaining
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** ADR 0018: arrival time from the navigation on a car, a straight-line estimate on the emulator. */
class EtaTrackerTest {
    private var now = Instant.parse("2026-11-13T15:58:00Z")
    private val clock = object : Clock {
        override fun now() = now
    }
    private val home = Destination("집", 37.500, 127.036)
    private fun trip(id: String = "t1") = ActiveTrip(
        StoredTrip(id, TripStatus.ACTIVE, home, "언니", "", PinAttempts(), now, null, now),
        TripState(TripStatus.ACTIVE),
    )

    private val active = MutableStateFlow<ActiveTrip?>(null)
    private var fix: GeoFix? = null
    private val navi = MutableStateFlow<NaviRemaining?>(null)
    private var polls = 0

    private fun tracker(demo: Boolean) =
        EtaTracker(active, { fix }, navi, { polls++ }, clock, demo = demo)

    /** [km] south of home along the meridian (1° ≈ 111.2 km). */
    private fun fixKmAway(km: Double) = GeoFix(home.lat - km / 111.2, home.lng, 0L)

    @Test
    fun noTripNoArrivalTime() = runTest {
        val t = tracker(demo = false)
        t.tick()
        assertNull(t.eta.value)
        assertEquals(0, polls)
    }

    @Test
    fun onACarItAsksTheNavigationAndUsesItsAnswer() = runTest {
        val t = tracker(demo = false)
        active.value = trip()
        t.tick()
        assertEquals(1, polls)
        assertNull(t.eta.value)

        navi.value = NaviRemaining(distanceM = 6200, timeS = 1560, routeTotalM = 10_000, at = now)
        t.tick()
        assertEquals(RouteEta(6200, 1560, 10_000, now), t.eta.value)
    }

    @Test
    fun aStaleNavigationAnswerIsDropped() = runTest {
        val t = tracker(demo = false)
        active.value = trip()
        navi.value = NaviRemaining(6200, 1560, 10_000, at = now)
        now += 61.seconds
        t.tick()
        assertNull(t.eta.value)
    }

    @Test
    fun onTheEmulatorItEstimatesFromGpsAtThirtyKmH() = runTest {
        val t = tracker(demo = true)
        active.value = trip()
        fix = fixKmAway(10.0)
        t.tick()
        val first = t.eta.value!!
        assertEquals(10_000.0, first.remainM.toDouble(), 20.0)
        assertEquals(1200.0, first.remainS.toDouble(), 3.0) // 10 km at 30 km/h = 20 min
        assertEquals(first.remainM, first.totalM)
        assertEquals(0, polls)

        fix = fixKmAway(4.0)
        t.tick()
        val later = t.eta.value!!
        assertEquals(4_000.0, later.remainM.toDouble(), 20.0)
        assertEquals(first.totalM, later.totalM) // the total is the first fix's distance
    }

    @Test
    fun aNewTripStartsANewTotal() = runTest {
        val t = tracker(demo = true)
        active.value = trip("t1")
        fix = fixKmAway(10.0)
        t.tick()
        active.value = trip("t2")
        fix = fixKmAway(3.0)
        t.tick()
        assertEquals(3_000.0, t.eta.value!!.totalM!!.toDouble(), 20.0)
    }

    @Test
    fun theEstimateWaitsForAFix() = runTest {
        val t = tracker(demo = true)
        active.value = trip()
        t.tick()
        assertNull(t.eta.value)
    }

    @Test
    fun theTripEndingClearsIt() = runTest {
        val t = tracker(demo = true)
        active.value = trip()
        fix = fixKmAway(2.0)
        t.tick()
        active.value = null
        t.tick()
        assertNull(t.eta.value)
    }

    @Test
    fun fractionAndArrival() {
        val eta = RouteEta(remainM = 2500, remainS = 600, totalM = 10_000, at = now)
        assertEquals(0.75f, eta.fraction!!, 0.0001f)
        assertEquals(now + 600.seconds, eta.arrival)
        assertNull(RouteEta(2500, 600, null, now).fraction)
        assertEquals(0f, RouteEta(12_000, 600, 10_000, now).fraction)
    }
}
