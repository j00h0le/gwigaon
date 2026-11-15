package com.coral.gwigaon.sos

import com.coral.gwigaon.trip.MonitoringConfig
import com.coral.gwigaon.vehicle.TurnSignal
import kotlin.time.Instant

/**
 * Turn-signal SOS (SPEC sos): the lever alternates LEFT↔RIGHT [MonitoringConfig.sosTurnSignalChanges] times within
 * [MonitoringConfig.sosTurnSignalWindow]. OFF in between is ignored, so L → OFF → R counts as one change, and the
 * same direction again (a second lane change, a blinker left on) is no change at all. Hazard lights would be the
 * natural signal, but Pleos Connect doesn't expose them (research/findings/spike-vehicle.md).
 *
 * Pure and single-threaded: feed it every turn-signal value with the time it arrived.
 */
class TurnSignalSosRule(config: MonitoringConfig) {
    private val needed = config.sosTurnSignalChanges
    private val window = config.sosTurnSignalWindow

    private var lastDirection: TurnSignal? = null
    private val changes = ArrayDeque<Instant>()

    /** true when this value completes the pattern; the count then starts over, so one pattern fires once. */
    fun onSignal(signal: TurnSignal, at: Instant): Boolean {
        if (signal == TurnSignal.OFF) return false
        val previous = lastDirection
        lastDirection = signal
        if (previous == null || previous == signal) return false

        changes.addLast(at)
        while (changes.isNotEmpty() && at - changes.first() > window) changes.removeFirst()
        if (changes.size < needed) return false
        changes.clear()
        return true
    }

    /** Forget everything seen so far (a new trip starts with a clean slate). */
    fun reset() {
        lastDirection = null
        changes.clear()
    }
}
