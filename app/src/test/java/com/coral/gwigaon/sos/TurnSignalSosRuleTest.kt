package com.coral.gwigaon.sos

import com.coral.gwigaon.trip.MonitoringConfig
import com.coral.gwigaon.vehicle.TurnSignal
import com.coral.gwigaon.vehicle.TurnSignal.LEFT
import com.coral.gwigaon.vehicle.TurnSignal.OFF
import com.coral.gwigaon.vehicle.TurnSignal.RIGHT
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

class TurnSignalSosRuleTest {
    private val t0 = Instant.parse("2026-11-11T09:00:00Z")

    /** Feeds (signal, ms since t0) pairs; returns the indexes that fired. */
    private fun fired(vararg steps: Pair<TurnSignal, Number>, rule: TurnSignalSosRule = TurnSignalSosRule(MonitoringConfig.DEFAULT)) =
        steps.withIndex().filter { (_, s) -> rule.onSignal(s.first, t0 + s.second.toLong().milliseconds) }.map { it.index }

    @Test
    fun fourQuickAlternationsFireOnTheFourthChange() {
        assertEquals(listOf(4), fired(LEFT to 0, RIGHT to 800, LEFT to 1600, RIGHT to 2400, LEFT to 3200))
    }

    @Test
    fun offBetweenDirectionsIsIgnored() {
        val steps = arrayOf(
            LEFT to 0, OFF to 300, RIGHT to 700, OFF to 1000, LEFT to 1400, OFF to 1700, RIGHT to 2100, OFF to 2400, LEFT to 2800,
        )
        assertEquals(listOf(8), fired(*steps))
    }

    @Test
    fun theWholePatternMustFitInSixSeconds() {
        // 4 changes over exactly 6 s still fires; 6.001 s doesn't.
        assertEquals(listOf(4), fired(LEFT to 0, RIGHT to 0, LEFT to 2000, RIGHT to 4000, LEFT to 6000))
        assertEquals(emptyList<Int>(), fired(LEFT to 0, RIGHT to 0, LEFT to 2000, RIGHT to 4000, LEFT to 6001))
    }

    @Test
    fun normalLaneChangesNeverFire() {
        // Left, left, left again, then right a while later: same direction is no change.
        val steps = arrayOf(LEFT to 0, OFF to 3000, LEFT to 5000, OFF to 8000, LEFT to 9000, OFF to 12_000, RIGHT to 20_000, OFF to 23_000)
        assertEquals(emptyList<Int>(), fired(*steps))
    }

    @Test
    fun oneLeftRightCorrectionDoesNotFire() {
        assertEquals(emptyList<Int>(), fired(LEFT to 0, OFF to 400, RIGHT to 900, OFF to 3000))
    }

    @Test
    fun threeQuickChangesAreNotEnough() {
        assertEquals(emptyList<Int>(), fired(LEFT to 0, RIGHT to 500, LEFT to 1000, RIGHT to 1500))
    }

    @Test
    fun slowAlternationDoesNotFire() {
        // A change every 2.5 s: never 4 inside 6 s, however long it goes on.
        val steps = (0..10).map { i -> (if (i % 2 == 0) LEFT else RIGHT) to i * 2500L }.toTypedArray()
        assertEquals(emptyList<Int>(), fired(*steps))
    }

    @Test
    fun signalLeftOnDoesNotFire() {
        // The lever reports the same direction over and over (or stays on): no change.
        val steps = (0..20).map { LEFT to it * 300L }.toTypedArray()
        assertEquals(emptyList<Int>(), fired(*steps))
    }

    @Test
    fun oldChangesFallOutOfTheWindow() {
        // Two changes, a long pause, then three more: only the last three are inside the window, so no fire;
        // one more change makes four inside the window.
        val steps = arrayOf(
            LEFT to 0, RIGHT to 500, LEFT to 1000,
            RIGHT to 10_000, LEFT to 10_500, RIGHT to 11_000,
            LEFT to 11_500,
        )
        assertEquals(listOf(6), fired(*steps))
    }

    @Test
    fun aPatternFiresOnceAndCountsAgainFromZero() {
        val rule = TurnSignalSosRule(MonitoringConfig.DEFAULT)
        val first = fired(LEFT to 0, RIGHT to 500, LEFT to 1000, RIGHT to 1500, LEFT to 2000, rule = rule)
        assertEquals(listOf(4), first)
        // Continuing to wiggle: three more changes don't fire again, the fourth does.
        val more = fired(RIGHT to 2500, LEFT to 3000, RIGHT to 3500, LEFT to 4000, rule = rule)
        assertEquals(listOf(3), more)
    }

    @Test
    fun resetForgetsTheLastDirectionAndChanges() {
        val rule = TurnSignalSosRule(MonitoringConfig.DEFAULT)
        assertEquals(emptyList<Int>(), fired(LEFT to 0, RIGHT to 500, LEFT to 1000, RIGHT to 1500, rule = rule))
        rule.reset()
        // After a reset the first signal is only a starting direction, so one more change can't complete the pattern.
        assertFalse(rule.onSignal(LEFT, t0 + 2000.milliseconds))
        assertFalse(rule.onSignal(RIGHT, t0 + 2500.milliseconds))
    }

    @Test
    fun thresholdsComeFromTheConfig() {
        val rule = TurnSignalSosRule(MonitoringConfig.DEFAULT.copy(sosTurnSignalChanges = 2))
        assertFalse(rule.onSignal(LEFT, t0))
        assertFalse(rule.onSignal(RIGHT, t0 + 100.milliseconds))
        assertTrue(rule.onSignal(LEFT, t0 + 200.milliseconds))
    }
}
