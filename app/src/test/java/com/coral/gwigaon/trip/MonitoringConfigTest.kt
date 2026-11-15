package com.coral.gwigaon.trip

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class MonitoringConfigTest {
    @Test
    fun defaultsMatchTheSpec() {
        val c = MonitoringConfig.DEFAULT
        assertEquals(300.0, c.deviationMeters, 0.0)
        assertEquals(60.seconds, c.deviationDuration)
        assertEquals(30.0, c.stationaryRadiusMeters, 0.0)
        assertEquals(5.minutes, c.longStopDuration)
        assertEquals(500.0, c.longStopMinDistanceFromDestinationMeters, 0.0)
        assertEquals(500.0, c.nearDestinationMeters, 0.0)
        assertEquals(100.0, c.arrivedMeters, 0.0)
        assertEquals(3.minutes, c.safeArrivalDelay)
        assertEquals(4, c.sosTurnSignalChanges)
        assertEquals(6.seconds, c.sosTurnSignalWindow)
        assertEquals(1500, c.sosHold.inWholeMilliseconds)
        assertEquals(30.seconds, c.heartbeatInterval)
        assertEquals(90.seconds, c.contactInterruptedAfter)
    }

    @Test
    fun demoOnlyShortensTheSafeArrivalDelay() {
        assertEquals(30.seconds, MonitoringConfig.DEMO.safeArrivalDelay)
        assertEquals(MonitoringConfig.DEFAULT.copy(safeArrivalDelay = 30.seconds), MonitoringConfig.DEMO)
    }

    @Test
    fun forPicksDemoOrDefault() {
        assertEquals(MonitoringConfig.DEMO, MonitoringConfig.forMode(demo = true))
        assertEquals(MonitoringConfig.DEFAULT, MonitoringConfig.forMode(demo = false))
    }
}
