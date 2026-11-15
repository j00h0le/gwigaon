package com.coral.gwigaon.route

import com.coral.gwigaon.trip.FakeTripStore
import com.coral.gwigaon.trip.MonitoringConfig
import com.coral.gwigaon.trip.PinHasher
import com.coral.gwigaon.trip.TestClock
import com.coral.gwigaon.trip.TripEvent
import com.coral.gwigaon.trip.TripSessionRepository
import com.coral.gwigaon.trip.TripSetup
import com.coral.gwigaon.vehicle.Destination
import com.coral.gwigaon.vehicle.DrivingState
import com.coral.gwigaon.vehicle.GeoFix
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class RouteMonitorTest {
    private val store = FakeTripStore()
    private val clock = TestClock()
    private var ids = 0
    private val sessions = TripSessionRepository(store, PinHasher(iterations = 1_000), clock) { "trip-${++ids}" }
    private val driving = MutableStateFlow(DrivingState.DRIVING)
    private val logged = mutableListOf<Pair<String, Throwable?>>()
    private val monitor = RouteMonitor(sessions, driving, clock, MonitoringConfig.DEFAULT) { m, e -> logged += m to e }
    private val setup = TripSetup(Destination("양재역", 37.48460, 127.03420), "언니", "2580")

    private fun events(trip: String = "trip-1") = store.events[trip].orEmpty().map { it.event }

    private suspend fun fixAt(northM: Double, accuracy: Float? = 5f) {
        val p = offset(northM, 0.0)
        monitor.onFix(GeoFix(p.lat, p.lng, 0, accuracyM = accuracy))
    }

    /** One fix per second for [d], standing at [northM]. */
    private suspend fun stand(northM: Double, d: Duration) {
        repeat(d.inWholeSeconds.toInt()) {
            clock.advance(1.seconds)
            fixAt(northM)
        }
    }

    /** Drives from [fromM] to [toM] (meters north of the destination) at 10 m/s, one fix per second. */
    private suspend fun drive(fromM: Double, toM: Double) {
        val steps = (kotlin.math.abs(toM - fromM) / 10).toInt()
        for (i in 1..steps) {
            clock.advance(1.seconds)
            fixAt(fromM + (toM - fromM) * i / steps)
        }
    }

    @Test
    fun demoShapedRouteLogsOneDeviationAndOneLongStopAndClearsBoth() = runTest {
        sessions.start(setup).getOrThrow()
        drive(1600.0, 800.0) // toward the destination
        drive(800.0, 1500.0) // away: 700 m over the best progress
        stand(1500.0, 6.minutes) // long stop on the detour
        drive(1500.0, 300.0) // back toward the destination

        val route = events().drop(1)
        assertEquals(
            listOf("ROUTE_DEVIATION", "LONG_STOP", "DEVIATION_CLEARED", "LONG_STOP_CLEARED").sorted(),
            route.map { it.type.wire }.sorted(),
        )
        val dev = route.filterIsInstance<TripEvent.RouteDeviation>().single()
        assertTrue(dev.distanceM in 301..700)
        assertTrue(dev.reason!!.startsWith("목적지와 다른 방향으로 "))
        val stop = route.filterIsInstance<TripEvent.LongStop>().single()
        assertEquals(300, stop.durationS)
        assertEquals("목적지 외 장소에서 5분 정차", stop.reason)
        assertEquals("6분 정차 후 다시 출발", route.filterIsInstance<TripEvent.LongStopCleared>().single().reason)
        assertEquals("경로로 복귀", route.filterIsInstance<TripEvent.DeviationCleared>().single().reason)
    }

    @Test
    fun timerAloneRaisesALongStopWhenFixesStop() = runTest {
        sessions.start(setup).getOrThrow()
        fixAt(1600.0)
        clock.advance(10.seconds)
        fixAt(1500.0) // moved; then no more fixes
        clock.advance(5.minutes)
        monitor.onTick()
        assertTrue(events().last() is TripEvent.LongStop)
    }

    @Test
    fun watchRunsTheTimer() = runTest {
        sessions.start(setup).getOrThrow()
        fixAt(1600.0)
        clock.advance(10.seconds)
        fixAt(1500.0)
        clock.advance(5.minutes)
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler))
        val job = monitor.watch(MutableSharedFlow(), scope)
        advanceTimeBy(MonitoringConfig.DEFAULT.routeTick + 1.seconds)
        runCurrent()
        job.cancel()
        assertTrue(events().last() is TripEvent.LongStop)
    }

    @Test
    fun inaccurateFixesAreIgnored() = runTest {
        sessions.start(setup).getOrThrow()
        fixAt(800.0)
        repeat(120) {
            clock.advance(1.seconds)
            fixAt(1500.0, accuracy = 500f)
        }
        assertEquals(1, events().size)
    }

    @Test
    fun noTripNoEvents() = runTest {
        drive(800.0, 1500.0)
        stand(1500.0, 6.minutes)
        monitor.onTick()
        assertTrue(store.events.isEmpty())
    }

    @Test
    fun eachTripStartsFresh() = runTest {
        sessions.start(setup).getOrThrow()
        fixAt(300.0) // best progress 300 m in trip 1
        sessions.endByPassenger("2580").getOrThrow()
        sessions.start(setup).getOrThrow()
        fixAt(1500.0) // trip 2 starts far away: that's its starting point, not a deviation
        stand(1500.0, 2.minutes)
        assertEquals(listOf(TripEvent.TripStarted::class.simpleName), events("trip-2").map { it::class.simpleName })
    }

    @Test
    fun aResumedTripCanClearWhatWasOpen() = runTest {
        sessions.start(setup).getOrThrow()
        sessions.log(TripEvent.RouteDeviation(500, "x")).getOrThrow()
        // A fresh rule instance (as after a restart) sees the open deviation in the trip state. With no stored
        // position, the restart alone doesn't clear it...
        fixAt(1500.0)
        assertFalse(events().last() is TripEvent.DeviationCleared)
        // ...coming back toward the destination does.
        clock.advance(1.minutes)
        fixAt(900.0)
        assertTrue(events().last() is TripEvent.DeviationCleared)
    }

    @Test
    fun aRestartDoesNotClearADeviationThatIsStillOpen() = runTest {
        sessions.start(setup).getOrThrow()
        // Like TripService: every stored event carries the latest fix.
        suspend fun fix(northM: Double) {
            val p = offset(northM, 0.0)
            sessions.lastFix = GeoFix(p.lat, p.lng, 0, accuracyM = 5f)
            fixAt(northM)
        }
        fix(1000.0)
        for (m in 1010..1500 step 10) {
            clock.advance(2.seconds)
            fix(m.toDouble())
        }
        repeat(35) {
            clock.advance(2.seconds)
            fix(1500.0)
        }
        assertTrue(sessions.active.value!!.state.deviating)

        // Process restart: a new monitor, same stored trip, car still 500 m past its best.
        val restarted = RouteMonitor(sessions, driving, clock, MonitoringConfig.DEFAULT) { m, e -> logged += m to e }
        val p = offset(1450.0, 0.0)
        restarted.onFix(GeoFix(p.lat, p.lng, 0, accuracyM = 5f))
        assertTrue(sessions.active.value!!.state.deviating)
        // 200 m past the stored best (1000 m) is still a deviation; the threshold fallback alone would clear it here.
        val halfway = offset(1200.0, 0.0)
        clock.advance(30.seconds)
        restarted.onFix(GeoFix(halfway.lat, halfway.lng, 0, accuracyM = 5f))
        assertTrue(sessions.active.value!!.state.deviating)
        val back = offset(1100.0, 0.0)
        clock.advance(30.seconds)
        restarted.onFix(GeoFix(back.lat, back.lng, 0, accuracyM = 5f))
        assertTrue(events().last() is TripEvent.DeviationCleared)
    }

    @Test
    fun aSingleGpsJumpTowardTheDestinationIsIgnored() = runTest {
        sessions.start(setup).getOrThrow()
        drive(1000.0, 1100.0)
        clock.advance(1.seconds)
        fixAt(10.0) // 1.1 km in 1 s: a jump, not progress
        assertEquals("GPS jump ignored: 1090 m in 1 s", logged.single().first)
        drive(1100.0, 1300.0) // 200 m past the real best (1000 m): no deviation
        stand(1300.0, 2.minutes)
        assertEquals(listOf(TripEvent.TripStarted::class.simpleName), events().map { it::class.simpleName })
    }

    @Test
    fun aWrongFirstPositionIsReplacedAfterThreeJumpsInARow() = runTest {
        sessions.start(setup).getOrThrow()
        fixAt(10.0) // the trip's first fix is the outlier
        repeat(3) {
            clock.advance(1.seconds)
            fixAt(1000.0)
        }
        assertEquals(
            listOf("GPS jump ignored: 990 m in 1 s", "GPS jump ignored: 990 m in 2 s", "GPS re-anchored after 3 jumps; route rules restarted"),
            logged.map { it.first },
        )
        drive(1000.0, 1200.0)
        stand(1200.0, 2.minutes)
        // Had the outlier stuck, best progress would be 10 m and this a 1.2 km deviation; replaced, it's 200 m over.
        assertFalse(sessions.active.value!!.state.deviating)
    }

    @Test
    fun anUnreadableLogIsReportedAndTheRuleStillRuns() = runTest {
        sessions.start(setup).getOrThrow()
        sessions.log(TripEvent.RouteDeviation(500, "x")).getOrThrow()
        store.corruptEvents = true
        fixAt(1500.0)
        assertEquals("trip log unreadable; deviation progress starts fresh", logged.single().first)
        assertTrue(sessions.active.value!!.state.deviating)
    }

    @Test
    fun aRejectedEventIsReported() = runTest {
        sessions.start(setup).getOrThrow()
        store.failAppend = true
        drive(800.0, 1500.0)
        stand(1500.0, 70.seconds)
        assertTrue(logged.isNotEmpty() && logged.all { it.second != null })
    }
}
