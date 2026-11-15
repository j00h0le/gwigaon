package com.coral.gwigaon.route

import com.coral.gwigaon.trip.EventRecord
import com.coral.gwigaon.trip.MonitoringConfig
import com.coral.gwigaon.trip.TripEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** Points placed by meters east/north of 양재역 (the demo destination). */
private val DEST = LatLng(37.48460, 127.03420)
private const val M_PER_DEG_LAT = 111_194.9

internal fun offset(northM: Double, eastM: Double, from: LatLng = DEST) = LatLng(
    from.lat + northM / M_PER_DEG_LAT,
    from.lng + eastM / (M_PER_DEG_LAT * cos(Math.toRadians(from.lat))),
)

class GeoTest {
    @Test
    fun distanceMatchesKnownPoints() {
        // 강남역 → 양재역 is about 1.5 km.
        val d = Geo.distanceM(LatLng(37.49794, 127.02762), DEST)
        assertEquals(1590.0, d, 40.0)
        assertEquals(0.0, Geo.distanceM(DEST, DEST), 1e-9)
        assertEquals(500.0, Geo.distanceM(DEST, offset(300.0, 400.0)), 0.5)
    }

    @Test
    fun formatsDistancesLikeTheContactPage() {
        assertEquals("0m", Geo.formatDistance(-5.0))
        assertEquals("420m", Geo.formatDistance(427.0))
        assertEquals("1km", Geo.formatDistance(1000.0))
        assertEquals("1.2km", Geo.formatDistance(1234.0))
        assertEquals("12km", Geo.formatDistance(12_340.0))
    }
}

class DeviationResumeTest {
    private val t0 = Instant.parse("2026-11-11T09:00:00Z")

    private fun record(seq: Int, northM: Double?, event: TripEvent) =
        EventRecord(seq, t0, northM?.let { offset(it, 0.0).lat }, northM?.let { offset(it, 0.0).lng }, event)

    @Test
    fun bestFromLogTakesTheClosestKnownProgress() {
        val log = listOf(
            record(1, 2000.0, TripEvent.TripStarted()),
            record(2, 1500.0, TripEvent.RouteDeviation(500)), // opened 500 m past a best of 1000 m
            record(3, null, TripEvent.MonitoringResumed()),
        )
        assertEquals(1000.0, DeviationRule.bestFromLog(log, DEST)!!, 2.0)
    }

    @Test
    fun bestFromLogIsNullWithoutPositionsAndNeverNegative() {
        assertNull(DeviationRule.bestFromLog(listOf(record(1, null, TripEvent.TripStarted())), DEST))
        assertNull(DeviationRule.bestFromLog(emptyList(), DEST))
        assertEquals(0.0, DeviationRule.bestFromLog(listOf(record(1, 100.0, TripEvent.RouteDeviation(400))), DEST)!!, 0.0)
    }

    @Test
    fun aSeededRuleClearsOnlyOnRealProgress() {
        val rule = DeviationRule(DEST, MonitoringConfig.DEFAULT, resumedBest = 1000.0)
        assertEquals(RouteDecision.None, rule.onPosition(offset(1500.0, 0.0), t0, driving = true, open = true))
        assertEquals(RouteDecision.None, rule.onPosition(offset(1160.0, 0.0), t0, driving = true, open = true))
        assertTrue(rule.onPosition(offset(1140.0, 0.0), t0, driving = true, open = true) is RouteDecision.Close)
    }

    @Test
    fun withoutAPositionAnOpenDeviationIsAssumedAtLeastTheThreshold() {
        val rule = DeviationRule(DEST, MonitoringConfig.DEFAULT)
        // First fix after the restart: 1500 m away, so best is taken as 1200 m (300 m threshold).
        assertEquals(RouteDecision.None, rule.onPosition(offset(1500.0, 0.0), t0, driving = true, open = true))
        assertEquals(RouteDecision.None, rule.onPosition(offset(1360.0, 0.0), t0, driving = true, open = true))
        assertTrue(rule.onPosition(offset(1340.0, 0.0), t0, driving = true, open = true) is RouteDecision.Close)
    }

    @Test
    fun withoutAPositionNearTheDestinationBestStopsAtZero() {
        val rule = DeviationRule(DEST, MonitoringConfig.DEFAULT)
        assertEquals(RouteDecision.None, rule.onPosition(offset(200.0, 0.0), t0, driving = true, open = true))
        assertTrue(rule.onPosition(offset(100.0, 0.0), t0, driving = true, open = true) is RouteDecision.Close)
    }
}

class DeviationRuleTest {
    private val t0 = Instant.parse("2026-11-11T09:00:00Z")
    private val rule = DeviationRule(DEST, MonitoringConfig.DEFAULT)

    private fun at(northM: Double, t: Duration, driving: Boolean = true, open: Boolean = false) =
        rule.onPosition(offset(northM, 0.0), t0 + t, driving, open)

    @Test
    fun approachingTheDestinationIsNeverADeviation() {
        for (s in 0..120) assertEquals(RouteDecision.None, at(2000.0 - s * 10, s.seconds))
    }

    @Test
    fun movingAwayMoreThan300mFor60sOpens() {
        at(1000.0, 0.seconds) // best progress: 1000 m
        assertEquals(RouteDecision.None, at(1301.0, 10.seconds)) // 301 m over: timer starts
        assertEquals(RouteDecision.None, at(1400.0, 69.seconds))
        val d = at(1450.0, 70.seconds)
        assertTrue(d is RouteDecision.Open)
        assertEquals(450.0, (d as RouteDecision.Open).amount.toDouble(), 2.0)
    }

    @Test
    fun exactly300mOverIsNotEnough() {
        at(1000.0, 0.seconds)
        assertEquals(RouteDecision.None, at(1299.0, 10.seconds))
        assertEquals(RouteDecision.None, at(1299.0, 200.seconds))
    }

    @Test
    fun dippingBackUnderTheThresholdRestartsTheTimer() {
        at(1000.0, 0.seconds)
        at(1400.0, 10.seconds)
        at(1250.0, 50.seconds) // back under 300 m over
        assertEquals(RouteDecision.None, at(1400.0, 60.seconds))
        assertEquals(RouteDecision.None, at(1400.0, 119.seconds))
        assertTrue(at(1400.0, 120.seconds) is RouteDecision.Open)
    }

    @Test
    fun onlyWhileDriving() {
        at(1000.0, 0.seconds)
        at(1400.0, 10.seconds, driving = false)
        assertEquals(RouteDecision.None, at(1400.0, 100.seconds, driving = false))
        // Driving again: the 60 s start now, not when the car was parked.
        assertEquals(RouteDecision.None, at(1400.0, 110.seconds))
        assertTrue(at(1400.0, 170.seconds) is RouteDecision.Open)
    }

    @Test
    fun clearsBackNearTheBestProgressWithHysteresis() {
        at(1000.0, 0.seconds)
        assertEquals(RouteDecision.None, at(1400.0, 10.seconds, open = true)) // still 400 m over
        assertEquals(RouteDecision.None, at(1200.0, 20.seconds, open = true)) // 200 m over: under 300 but not 150
        assertTrue(at(1100.0, 30.seconds, open = true) is RouteDecision.Close)
    }

    @Test
    fun clearsEvenWhileParked() {
        at(1000.0, 0.seconds)
        assertTrue(at(1000.0, 30.seconds, driving = false, open = true) is RouteDecision.Close)
    }
}

class LongStopRuleTest {
    private val t0 = Instant.parse("2026-11-11T09:00:00Z")
    private val rule = LongStopRule(DEST, MonitoringConfig.DEFAULT)
    private val far = 2000.0 // m north of the destination

    private fun pos(northM: Double, t: Duration, open: Boolean = false, arriving: Boolean = false) =
        rule.onPosition(offset(northM, 0.0), t0 + t, open, arriving)

    private fun tick(t: Duration, open: Boolean = false, arriving: Boolean = false) = rule.onTick(t0 + t, open, arriving)

    /** Drives 100 m, then stands at [northM] from [t]. */
    private fun moveThenStop(northM: Double = far, t: Duration = 0.seconds) {
        pos(northM + 100, t)
        pos(northM, t + 10.seconds)
    }

    @Test
    fun standingStillFiveMinutesFarFromTheDestinationOpens() {
        moveThenStop()
        assertEquals(RouteDecision.None, tick(5.minutes + 9.seconds))
        assertEquals(RouteDecision.Open(300), tick(5.minutes + 10.seconds))
    }

    @Test
    fun smallGpsWanderStillCountsAsStanding() {
        moveThenStop()
        pos(far + 20, 2.minutes)
        pos(far - 25, 4.minutes)
        assertEquals(RouteDecision.Open(310), pos(far + 10, 5.minutes + 20.seconds))
    }

    @Test
    fun movingResetsTheStop() {
        moveThenStop()
        pos(far + 40, 4.minutes) // moved 40 m: a new stop starts here
        assertEquals(RouteDecision.None, tick(8.minutes + 59.seconds))
        assertEquals(RouteDecision.Open(300), tick(9.minutes))
    }

    @Test
    fun waitingAtThePickupBeforeMovingIsNotAStop() {
        pos(far, 0.seconds)
        assertEquals(RouteDecision.None, tick(20.minutes))
        assertEquals(RouteDecision.None, pos(far + 5, 21.minutes))
    }

    @Test
    fun notNearTheDestination() {
        moveThenStop(northM = 400.0)
        assertEquals(RouteDecision.None, tick(30.minutes))
    }

    @Test
    fun notWhileArriving() {
        moveThenStop()
        assertEquals(RouteDecision.None, tick(10.minutes, arriving = true))
    }

    @Test
    fun noRepeatWhileOpenAndClosesWithTheTimeStopped() {
        moveThenStop()
        assertEquals(RouteDecision.None, tick(7.minutes, open = true))
        assertEquals(RouteDecision.Close(430), pos(far + 200, 7.minutes + 20.seconds, open = true))
    }

    @Test
    fun drivingOnWithoutAnOpenStopClosesNothing() {
        moveThenStop()
        assertEquals(RouteDecision.None, pos(far + 200, 2.minutes))
    }

    @Test
    fun tickBeforeAnyFixDoesNothing() {
        assertEquals(RouteDecision.None, tick(10.minutes))
    }
}
