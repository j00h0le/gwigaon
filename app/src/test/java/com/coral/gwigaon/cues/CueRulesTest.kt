package com.coral.gwigaon.cues

import com.coral.gwigaon.trip.CueKind
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class LoudnessTest {
    private val config = CueConfig.DEFAULT

    @Test
    fun dbfsIsZeroAtFullScaleAndClampedForSilence() {
        assertEquals(0.0, Loudness.dbfs(ShortArray(100) { Short.MIN_VALUE }), 0.01)
        assertEquals(-6.02, Loudness.dbfs(ShortArray(100) { 16384 }), 0.01)
        assertEquals(Loudness.SILENCE_DBFS, Loudness.dbfs(ShortArray(100)), 0.0)
        assertEquals(Loudness.SILENCE_DBFS, Loudness.dbfs(0.0, 0), 0.0)
    }

    @Test
    fun meterCutsChunksOfAnySizeIntoWindows() {
        val meter = LoudnessMeter(10)
        assertEquals(emptyList<Double>(), meter.add(ShortArray(7) { 1000 }))
        val done = meter.add(ShortArray(15) { 1000 }) // finishes the first window, then a second one
        assertEquals(2, done.size)
        assertEquals(Loudness.dbfs(ShortArray(10) { 1000 }), done[0], 1e-9)
        meter.reset()
        assertEquals(emptyList<Double>(), meter.add(ShortArray(9) { 1000 }))
    }

    @Test
    fun nothingIsLoudUntilTheBaselineHasEnoughWindows() {
        val baseline = LoudnessBaseline(config)
        repeat(config.minBaselineWindows) { assertFalse(baseline.onWindow(-10.0)) }
        assertEquals(-10.0, baseline.median()!!, 0.0)
    }

    @Test
    fun loudNeedsTheDeltaOverTheMedianAndTheAbsoluteFloor() {
        val baseline = LoudnessBaseline(config)
        repeat(10) { baseline.onWindow(-56.0) }
        assertFalse("14 dB over", baseline.onWindow(-42.0))
        assertTrue("16 dB over and at the floor", baseline.onWindow(-40.0))
        val quiet = LoudnessBaseline(config)
        repeat(10) { quiet.onWindow(-80.0) }
        assertFalse("20 dB over but under the -40 dBFS floor", quiet.onWindow(-60.0))
        assertTrue(quiet.onWindow(-30.0))
    }

    @Test
    fun steadyNoiseRaisesTheBaselineSoItStopsCounting() {
        val baseline = LoudnessBaseline(config)
        repeat(10) { baseline.onWindow(-56.0) }
        assertTrue(baseline.onWindow(-30.0))
        repeat(config.baselineWindows) { baseline.onWindow(-30.0) } // music on for two minutes
        assertFalse(baseline.onWindow(-30.0))
        assertEquals(-30.0, baseline.median()!!, 0.0)
        baseline.reset()
        assertNull(baseline.median())
    }

    @Test
    fun medianOfAnEvenCountIsTheMiddlePair() {
        val baseline = LoudnessBaseline(config.copy(minBaselineWindows = 2))
        listOf(-50.0, -40.0, -60.0, -30.0).forEach { baseline.onWindow(it) }
        assertEquals(-45.0, baseline.median()!!, 0.0)
    }
}

class CueLevelRuleTest {
    private val config = CueConfig.DEFAULT
    private val rule = CueLevelRule(config)
    private var now = Instant.parse("2026-11-12T21:00:00Z")

    private fun windows(loud: Boolean, count: Int): List<CheckInPrompt> = (1..count).mapNotNull {
        now += 1.seconds
        rule.onWindow(loud, now)
    }

    @Test
    fun tenLoudWindowsInARowAreLevelOne() {
        assertEquals(emptyList<CheckInPrompt>(), windows(true, 9))
        assertEquals(listOf(CheckInPrompt(1, CueKind.LOUD)), windows(true, 1))
    }

    @Test
    fun aQuietWindowBreaksTheRun() {
        windows(true, 9)
        windows(false, 1)
        assertEquals(emptyList<CheckInPrompt>(), windows(true, 9))
    }

    @Test
    fun aSecondLevelOneWithinTwoMinutesIsLevelTwo() {
        windows(true, 10)
        rule.shown(now)
        rule.answered(now) // ok: cooldown starts, level 2 may still show
        windows(false, 30)
        assertEquals(listOf(CheckInPrompt(2, CueKind.LOUD)), windows(true, 10))
    }

    @Test
    fun aLevelOneMoreThanTwoMinutesLaterIsLevelOneAgain() {
        windows(true, 10)
        now += 3.minutes
        assertEquals(listOf(CheckInPrompt(1, CueKind.LOUD)), windows(true, 10))
    }

    @Test
    fun oneWordHitIsLevelOneAndDuringALoudStretchLevelTwo() {
        assertEquals(CheckInPrompt(1, CueKind.DISTRESS), rule.onWordHit(CueKind.DISTRESS, now))
        val other = CueLevelRule(config)
        other.onWindow(true, now)
        assertEquals(CheckInPrompt(2, CueKind.PROFANITY), other.onWordHit(CueKind.PROFANITY, now))
    }

    @Test
    fun aWordHitAfterALoudLevelOneIsARepeat() {
        windows(true, 10)
        windows(false, 1)
        assertEquals(CheckInPrompt(2, CueKind.PROFANITY), rule.onWordHit(CueKind.PROFANITY, now))
    }

    @Test(expected = IllegalArgumentException::class)
    fun loudIsNotAWordHit() {
        rule.onWordHit(CueKind.LOUD, now)
    }

    @Test
    fun nothingNewWhileACardShows() {
        windows(true, 10)
        rule.shown(now)
        assertTrue(rule.pending)
        assertEquals(emptyList<CheckInPrompt>(), windows(true, 20))
        assertNull(rule.onWordHit(CueKind.DISTRESS, now))
    }

    @Test
    fun cooldownBlocksLevelOneButNotLevelTwo() {
        rule.shown(now)
        rule.answered(now)
        now += 1.minutes
        assertNull("level 1 inside the cooldown", rule.onWordHit(CueKind.DISTRESS, now))
        now += 30.seconds
        assertEquals("the repeat is level 2", CheckInPrompt(2, CueKind.DISTRESS), rule.onWordHit(CueKind.DISTRESS, now))
    }

    @Test
    fun levelOneShowsAgainOnceTheCooldownIsOver() {
        rule.shown(now)
        rule.answered(now)
        now += config.checkInCooldown
        assertEquals(CheckInPrompt(1, CueKind.PROFANITY), rule.onWordHit(CueKind.PROFANITY, now))
    }

    @Test
    fun anUnansweredCardTimesOutAfterThirtySeconds() {
        assertFalse(rule.timedOut(now))
        rule.shown(now)
        assertFalse(rule.timedOut(now + 29.seconds))
        assertTrue(rule.timedOut(now + 30.seconds))
        rule.answered(now + 30.seconds)
        assertFalse(rule.pending)
        assertFalse(rule.timedOut(now + 60.seconds))
    }

    @Test
    fun resetForgetsEverythingAndResetLoudnessOnlyTheRun() {
        windows(true, 9)
        rule.resetLoudness()
        assertEquals(emptyList<CheckInPrompt>(), windows(true, 9))
        rule.shown(now)
        rule.reset()
        assertFalse(rule.pending)
        assertEquals(listOf(CheckInPrompt(1, CueKind.LOUD)), windows(true, 10))
    }
}

class RingBufferTest {
    @Test
    fun keepsTheNewestSamplesOldestFirst() {
        val ring = PcmRingBuffer(5)
        ring.write(shortArrayOf(1, 2, 3))
        assertArrayEquals(shortArrayOf(1, 2, 3), ring.snapshot())
        ring.write(shortArrayOf(4, 5, 6, 7))
        assertArrayEquals(shortArrayOf(3, 4, 5, 6, 7), ring.snapshot())
        assertEquals(5, ring.size)
    }

    @Test
    fun tailGivesTheNewestSamplesAcrossTheWrap() {
        val ring = PcmRingBuffer(5)
        assertArrayEquals(shortArrayOf(), ring.tail(3))
        ring.write(shortArrayOf(1, 2))
        assertArrayEquals("fewer written than asked", shortArrayOf(1, 2), ring.tail(3))
        ring.write(shortArrayOf(3, 4, 5, 6, 7))
        assertArrayEquals(shortArrayOf(5, 6, 7), ring.tail(3))
        assertArrayEquals(shortArrayOf(), ring.tail(-1))
    }

    @Test
    fun aChunkBiggerThanTheBufferKeepsItsTail() {
        val ring = PcmRingBuffer(3)
        ring.write(shortArrayOf(1, 2, 3, 4, 5, 6), count = 5)
        assertArrayEquals(shortArrayOf(3, 4, 5), ring.snapshot())
    }

    @Test
    fun clearForgetsEverything() {
        val ring = PcmRingBuffer(3)
        ring.write(shortArrayOf(1, 2))
        ring.clear()
        assertEquals(0, ring.size)
        assertArrayEquals(ShortArray(0), ring.snapshot())
    }

    @Test
    fun thirtySecondsAtSixteenKilohertz() {
        assertEquals(480_000, CueConfig.DEFAULT.clipSamples)
        assertEquals(16_000, CueConfig.DEFAULT.samplesPerWindow)
        assertEquals(120, CueConfig.DEFAULT.baselineWindows)
    }

    @Test
    fun wavHeaderDescribes16BitMono() {
        val wav = Wav.encode(shortArrayOf(1, -2), 16_000)
        assertEquals(48, wav.size)
        assertEquals("RIFF", String(wav, 0, 4, Charsets.US_ASCII))
        assertEquals("WAVE", String(wav, 8, 4, Charsets.US_ASCII))
        assertEquals("data", String(wav, 36, 4, Charsets.US_ASCII))
        fun int32(at: Int) = (0 until 4).sumOf { (wav[at + it].toInt() and 0xFF) shl (8 * it) }
        assertEquals(40, int32(4))
        assertEquals(16_000, int32(24))
        assertEquals(32_000, int32(28))
        assertEquals(4, int32(40))
        assertEquals(1, wav[44].toInt())
        assertEquals(-2, (wav[46].toInt() and 0xFF or (wav[47].toInt() shl 8)).toShort().toInt())
    }
}
