package com.coral.gwigaon.vehicle

import app.cash.turbine.test
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FakeVehicleSignalsTest {

    @Test
    fun startsParkedWithEverythingAvailable() = runTest {
        val car = FakeVehicleSignals(backgroundScope)
        assertEquals(Gear.PARK, car.gear.value)
        assertEquals(DrivingState.PARKED, car.drivingState.value)
        assertEquals(emptySet<Signal>(), car.unavailable.value)
        assertEquals(emptySet<Door>(), car.doorsOpen.value)
    }

    @Test
    fun gearDrivesDrivingState() = runTest(UnconfinedTestDispatcher()) {
        val car = FakeVehicleSignals(backgroundScope)
        car.setGear(Gear.DRIVE)
        assertEquals(DrivingState.DRIVING, car.drivingState.value)
        car.setGear(Gear.PARK)
        assertEquals(DrivingState.PARKED, car.drivingState.value)
    }

    @Test
    fun unknownGearUsesFixes() = runTest(UnconfinedTestDispatcher()) {
        val car = FakeVehicleSignals(backgroundScope, initialGear = null)
        assertEquals(DrivingState.DRIVING, car.drivingState.value) // fail-safe before any GPS
        car.emitFix(GeoFix(37.0, 127.0, 0))
        car.emitFix(GeoFix(37.0, 127.0, 1_000))
        assertEquals(DrivingState.PARKED, car.drivingState.value)
        car.emitFix(GeoFix(37.001, 127.0, 11_000))
        assertEquals(DrivingState.DRIVING, car.drivingState.value)
    }

    @Test
    fun turnSignalSeatsDoorsAndAvailabilityAreSettable() = runTest(UnconfinedTestDispatcher()) {
        val car = FakeVehicleSignals(backgroundScope)
        car.turnSignal.test {
            assertEquals(TurnSignal.OFF, awaitItem())
            car.setTurnSignal(TurnSignal.LEFT)
            assertEquals(TurnSignal.LEFT, awaitItem())
        }
        car.setPassengerSeatOccupied(true)
        assertEquals(true, car.passengerSeatOccupied.value)
        car.setDoorsOpen(setOf(Door.REAR_RIGHT))
        assertEquals(setOf(Door.REAR_RIGHT), car.doorsOpen.value)
        car.setUnavailable(setOf(Signal.LOCATION))
        assertEquals(setOf(Signal.LOCATION), car.unavailable.value)
    }

    @Test
    fun setDestinationRecordsItAndEmitsNaviEvent() = runTest(UnconfinedTestDispatcher()) {
        val car = FakeVehicleSignals(backgroundScope)
        val dest = Destination("데모 목적지", 37.485, 127.036)
        car.navi.test {
            assertTrue(car.setDestination(dest).isSuccess)
            assertEquals(NaviEvent.DestinationSet(dest), awaitItem())
            car.arrive()
            assertEquals(NaviEvent.Arrived, awaitItem())
        }
        assertEquals(listOf(dest), car.destinationsSet)
    }

    @Test
    fun setDestinationCanFail() = runTest {
        val car = FakeVehicleSignals(backgroundScope)
        car.failSetDestination = IllegalStateException("navi down")
        assertTrue(car.setDestination(Destination("x", 0.0, 0.0)).isFailure)
        assertEquals(emptyList<Destination>(), car.destinationsSet)
    }
}

class SeatRuleTest {
    @Test
    fun anyOccupiedSeatMeansPassengerInCar() {
        assertEquals(true, SeatRule.anyOccupied(listOf(false, null, true)))
        assertEquals(false, SeatRule.anyOccupied(listOf(false, null)))
        assertEquals(null, SeatRule.anyOccupied(listOf(null, null)))
        assertEquals(null, SeatRule.anyOccupied(emptyList()))
    }
}

class SeatNormalizeTest {
    @Test
    fun invertedSourceFlipsKnownValuesOnly() {
        assertEquals(false, SeatRule.normalize(true, invertedSource = true))
        assertEquals(true, SeatRule.normalize(false, invertedSource = true))
        assertEquals(null, SeatRule.normalize(null, invertedSource = true))
        assertEquals(true, SeatRule.normalize(true, invertedSource = false))
    }

    @Test
    fun detectsThePleosEmulatorOnly() {
        assertTrue(SeatRule.isEmulator("ranchu", "crp_avd_arm64"))
        assertTrue(SeatRule.isEmulator("goldfish", "crp_avd_x86_64"))
        assertEquals(false, SeatRule.isEmulator("qcom", "pleos_car"))
    }
}
