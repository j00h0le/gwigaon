package com.coral.gwigaon.vehicle

import app.cash.turbine.test
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

class DrivingStateRuleTest {

    @Test
    fun gearParkIsParkedEvenWithGpsSpeed() {
        assertEquals(DrivingState.PARKED, DrivingStateRule.decide(Gear.PARK, null))
        assertEquals(DrivingState.PARKED, DrivingStateRule.decide(Gear.PARK, 40.0))
    }

    @Test
    fun gearDriveReverseNeutralAreDriving() {
        for (gear in listOf(Gear.DRIVE, Gear.REVERSE, Gear.NEUTRAL)) {
            assertEquals(gear.name, DrivingState.DRIVING, DrivingStateRule.decide(gear, 0.0))
            assertEquals(gear.name, DrivingState.DRIVING, DrivingStateRule.decide(gear, null))
        }
    }

    @Test
    fun unknownGearFallsBackToSpeed() {
        assertEquals(DrivingState.DRIVING, DrivingStateRule.decide(null, 5.1))
        assertEquals(DrivingState.PARKED, DrivingStateRule.decide(null, 5.0))
        assertEquals(DrivingState.PARKED, DrivingStateRule.decide(null, 0.0))
    }

    @Test
    fun unknownGearAndUnknownSpeedIsDrivingFailSafe() {
        assertEquals(DrivingState.DRIVING, DrivingStateRule.decide(null, null))
    }

    @Test
    fun speedFromTwoFixesUsesDistanceOverTime() {
        // ~111 m north in 10 s = ~40 km/h
        val a = fix(37.0, 127.0, 0)
        val b = fix(37.001, 127.0, 10_000)
        val kmh = SpeedEstimator.estimateKmh(a, b)!!
        assertEquals(40.0, kmh, 0.5)
    }

    @Test
    fun movementWithinGpsAccuracyCountsAsStationary() {
        val a = fix(37.0, 127.0, 0, accuracy = 10f)
        val b = fix(37.00005, 127.0, 1_000, accuracy = 10f) // ~5.6 m jitter in 1 s would be ~20 km/h
        assertEquals(0.0, SpeedEstimator.estimateKmh(a, b)!!, 0.0)
    }

    @Test
    fun reportedSpeedWinsWhenHigherThanDerived() {
        val a = fix(37.0, 127.0, 0)
        val b = fix(37.0, 127.0, 1_000, speedMps = 10f)
        assertEquals(36.0, SpeedEstimator.estimateKmh(a, b)!!, 0.01)
    }

    @Test
    fun nonIncreasingTimestampsGiveNoEstimate() {
        val a = fix(37.0, 127.0, 5_000)
        assertNull(SpeedEstimator.estimateKmh(a, fix(37.001, 127.0, 5_000)))
        assertNull(SpeedEstimator.estimateKmh(a, fix(37.001, 127.0, 4_000)))
    }

    @Test
    fun speedFlowEmitsOncePerFixPair() = runTest {
        val speeds = SpeedEstimator.speedKmh(
            flowOf(fix(37.0, 127.0, 0), fix(37.001, 127.0, 10_000), fix(37.001, 127.0, 20_000)),
        ).toList()
        assertEquals(2, speeds.size)
        assertEquals(40.0, speeds[0], 0.5)
        assertEquals(0.0, speeds[1], 0.0)
    }

    @Test
    fun drivingStateFlowFollowsGearThenSpeed() = runTest {
        val gear = MutableStateFlow<Gear?>(Gear.PARK)
        val fixes = MutableSharedFlow<GeoFix>(extraBufferCapacity = 8)
        DrivingStateRule.drivingState(gear, fixes).test {
            assertEquals(DrivingState.PARKED, awaitItem())
            gear.value = Gear.DRIVE
            assertEquals(DrivingState.DRIVING, awaitItem())
            gear.value = null // signal lost, no GPS yet -> fail-safe
            expectNoEvents()
            fixes.emit(fix(37.0, 127.0, 0))
            fixes.emit(fix(37.0, 127.0, 1_000)) // standing still
            assertEquals(DrivingState.PARKED, awaitItem())
            fixes.emit(fix(37.001, 127.0, 11_000))
            assertEquals(DrivingState.DRIVING, awaitItem())
            gear.value = Gear.PARK
            assertEquals(DrivingState.PARKED, awaitItem())
        }
    }

    @Test
    fun gearFailureAfterParkIsNotParked() = runTest {
        val gear = MutableStateFlow<Gear?>(Gear.PARK)
        val missing = MutableStateFlow<Set<Signal>>(emptySet())
        DrivingStateRule.drivingState(gear, MutableSharedFlow<GeoFix>(), missing).test {
            assertEquals(DrivingState.PARKED, awaitItem())
            missing.value = setOf(Signal.GEAR) // SDK failed; the last value is still PARK
            assertEquals(DrivingState.DRIVING, awaitItem())
            missing.value = emptySet()
            assertEquals(DrivingState.PARKED, awaitItem())
        }
    }

    @Test
    fun staleSpeedExpiresToTheFailSafe() = runTest {
        val fixes = MutableSharedFlow<GeoFix>(extraBufferCapacity = 8)
        DrivingStateRule.drivingState(MutableStateFlow<Gear?>(null), fixes, MutableStateFlow(emptySet())).test {
            assertEquals(DrivingState.DRIVING, awaitItem())
            fixes.emit(fix(37.0, 127.0, 0))
            fixes.emit(fix(37.0, 127.0, 1_000)) // standing still
            assertEquals(DrivingState.PARKED, awaitItem())
            advanceTimeBy(DrivingStateRule.SPEED_MAX_AGE - 1.seconds)
            fixes.emit(fix(37.0, 127.0, 2_000)) // a fresh fix restarts the clock
            advanceTimeBy(DrivingStateRule.SPEED_MAX_AGE - 1.seconds)
            expectNoEvents()
            advanceTimeBy(2.seconds) // then GPS goes quiet (tunnel)
            assertEquals(DrivingState.DRIVING, awaitItem())
        }
    }

    @Test
    fun unavailableLocationDropsTheLastSpeed() = runTest {
        val fixes = MutableSharedFlow<GeoFix>(extraBufferCapacity = 8)
        val missing = MutableStateFlow<Set<Signal>>(emptySet())
        DrivingStateRule.drivingState(MutableStateFlow<Gear?>(null), fixes, missing).test {
            assertEquals(DrivingState.DRIVING, awaitItem())
            fixes.emit(fix(37.0, 127.0, 0))
            fixes.emit(fix(37.0, 127.0, 1_000))
            runCurrent()
            assertEquals(DrivingState.PARKED, expectMostRecentItem())
            missing.value = setOf(Signal.LOCATION) // permission revoked
            // Checked without letting virtual time pass, so the speed expiring can't be what turns this to DRIVING.
            runCurrent()
            assertEquals(DrivingState.DRIVING, expectMostRecentItem())
        }
    }

    @Test
    fun cannotTellOnlyWithoutGearAndLocation() {
        assertTrue(DrivingStateRule.cannotTell(setOf(Signal.GEAR, Signal.LOCATION, Signal.NAVI)))
        assertFalse(DrivingStateRule.cannotTell(setOf(Signal.GEAR)))
        assertFalse(DrivingStateRule.cannotTell(setOf(Signal.LOCATION)))
        assertFalse(DrivingStateRule.cannotTell(emptySet()))
    }

    @Test
    fun distanceIsSymmetricAndZeroForSamePoint() {
        val d = GeoMath.distanceMeters(37.0, 127.0, 37.001, 127.0)
        assertEquals(111.2, d, 0.5)
        assertEquals(d, GeoMath.distanceMeters(37.001, 127.0, 37.0, 127.0), 1e-9)
        assertTrue(GeoMath.distanceMeters(37.0, 127.0, 37.0, 127.0) < 1e-9)
    }

    private fun fix(lat: Double, lng: Double, t: Long, accuracy: Float? = null, speedMps: Float? = null) =
        GeoFix(lat = lat, lng = lng, timeMillis = t, accuracyM = accuracy, speedMps = speedMps)
}

/** The flow with every signal available (nothing reported missing). */
private fun DrivingStateRule.drivingState(gear: Flow<Gear?>, location: Flow<GeoFix>): Flow<DrivingState> =
    drivingState(gear, location, MutableStateFlow(emptySet()))
