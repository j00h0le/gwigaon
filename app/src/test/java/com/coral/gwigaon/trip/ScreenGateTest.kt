package com.coral.gwigaon.trip

import com.coral.gwigaon.vehicle.DrivingState
import org.junit.Assert.assertEquals
import org.junit.Test

class ScreenGateTest {
    @Test
    fun drivingAlwaysShowsTheDrivingScreen() {
        assertEquals(Screen.DRIVING, ScreenGate.screenFor(DrivingState.DRIVING, tripActive = true))
        assertEquals(Screen.DRIVING, ScreenGate.screenFor(DrivingState.DRIVING, tripActive = false))
    }

    @Test
    fun parkedShowsTheTripOrSetup() {
        assertEquals(Screen.TRIP, ScreenGate.screenFor(DrivingState.PARKED, tripActive = true))
        assertEquals(Screen.SETUP, ScreenGate.screenFor(DrivingState.PARKED, tripActive = false))
    }

    @Test
    fun anOpenSafeArrivalCheckTakesTheParkedTripScreenButNeverTheDrivingOne() {
        assertEquals(Screen.SAFE_ARRIVAL_CHECK, ScreenGate.screenFor(DrivingState.PARKED, tripActive = true, safeArrivalCheck = true))
        assertEquals(Screen.DRIVING, ScreenGate.screenFor(DrivingState.DRIVING, tripActive = true, safeArrivalCheck = true))
    }

    @Test
    fun aPendingReportShowsOnlyWhileParkedAndNoTripRuns() {
        assertEquals(Screen.REPORT, ScreenGate.screenFor(DrivingState.PARKED, tripActive = false, reportPending = true))
        assertEquals(Screen.DRIVING, ScreenGate.screenFor(DrivingState.DRIVING, tripActive = false, reportPending = true))
        assertEquals(Screen.TRIP, ScreenGate.screenFor(DrivingState.PARKED, tripActive = true, reportPending = true))
    }

    @Test
    fun arrivedTakesTheParkedTripScreenButNotTheCheckOrTheDrivingOne() {
        assertEquals(Screen.ARRIVAL, ScreenGate.screenFor(DrivingState.PARKED, tripActive = true, arrived = true))
        assertEquals(Screen.SAFE_ARRIVAL_CHECK, ScreenGate.screenFor(DrivingState.PARKED, tripActive = true, safeArrivalCheck = true, arrived = true))
        assertEquals(Screen.DRIVING, ScreenGate.screenFor(DrivingState.DRIVING, tripActive = true, arrived = true))
        assertEquals(Screen.SETUP, ScreenGate.screenFor(DrivingState.PARKED, tripActive = false, arrived = true))
    }
}
