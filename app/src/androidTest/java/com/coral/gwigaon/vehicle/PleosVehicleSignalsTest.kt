package com.coral.gwigaon.vehicle

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Runs the real Pleos SDKs on the Pleos Connect emulator (gear is P after boot). */
@RunWith(AndroidJUnit4::class)
class PleosVehicleSignalsTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var signals: PleosVehicleSignals

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        signals = PleosVehicleSignals(context, scope)
        InstrumentationRegistry.getInstrumentation().runOnMainSync { signals.start() }
    }

    @After
    fun tearDown() {
        signals.close()
        scope.cancel()
    }

    @Test
    fun gearAndSeatsAreReportedAndNothingCrashes() = runBlocking {
        val gear = withTimeout(10_000) { signals.gear.filterNotNull().first() }
        assertEquals(DrivingStateRule.decide(gear, null), withTimeout(5_000) {
            signals.drivingState.first { it == DrivingStateRule.decide(gear, null) }
        })
        withTimeout(10_000) { signals.passengerSeatOccupied.filterNotNull().first() }
        val missing = signals.unavailable.value - Signal.LOCATION // location depends on the runtime grant
        assertTrue("unavailable: $missing", missing.isEmpty())
    }

    @Test
    fun setDestinationReachesTheCarNavigation() = runBlocking {
        val dest = Destination("데모 목적지", 37.48544722, 127.03636666, address = "서울시 강남구 남부순환로 2621")
        val event = async { withTimeout(20_000) { signals.navi.filterIsInstance<NaviEvent.DestinationSet>().first() } }
        assertTrue(signals.setDestination(dest).isSuccess)
        assertEquals(dest, event.await().destination)
    }
}
