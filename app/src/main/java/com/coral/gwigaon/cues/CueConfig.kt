package com.coral.gwigaon.cues

import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Every conflict-cue threshold in one place (SPEC conflict-cues, ADR 0012), with the defaults the owner approved
 * on 2026-11-12. Rules take this; nothing is inlined.
 */
data class CueConfig(
    /** Recorder format: 16 kHz, 16-bit mono (also what the Pleos SpeechToText SDK takes in stage 2). */
    val sampleRate: Int = 16_000,
    /** Loudness is judged per window of this length. */
    val window: Duration = 1.seconds,
    /** The trip's own baseline: the median loudness of the windows in this span. */
    val baselineSpan: Duration = 2.minutes,
    /** Fewer windows than this and there is no baseline yet, so nothing counts as loud (the first seconds). */
    val minBaselineWindows: Int = 5,
    /** "Loud" = at least this much above the baseline... */
    val loudDeltaDb: Double = 15.0,
    /**
     * ...and above this absolute level, so a near-silent cabin doesn't make a cough count. The host mic in a quiet
     * room reads about -58 to -53 dBFS on the emulator (research/findings/spike-tts-mic-net.md).
     */
    val loudFloorDbfs: Double = -40.0,
    /** Level 1: this many loud windows in a row. */
    val loudRunForLevel1: Int = 10,
    /** Level 2: a second level 1 within this span of the previous one. */
    val repeatWindow: Duration = 2.minutes,
    /** An unanswered card closes as `no_response` after this. */
    val checkInTimeout: Duration = 30.seconds,
    /** After an answered card, no new level-1 card for this long (level 2 still shows). */
    val checkInCooldown: Duration = 2.minutes,
    /** Stage 2: streaming goes back to sampling after this long without a hit (a word or a loud window). */
    val calmPeriod: Duration = 60.seconds,
    /** Stage 2 sampling: a sample of [sampleLength] starts every [sampleEvery] while nothing is happening. */
    val sampleEvery: Duration = 60.seconds,
    val sampleLength: Duration = 10.seconds,
    /**
     * Stage 2 wake-up: the audio just before the loud window is sent first, so a word shouted in the window that woke
     * stage 2 is still heard.
     */
    val preRoll: Duration = 2.seconds,
    /** Raw audio kept in memory, written as a clip only when SOS fires. */
    val clipLength: Duration = 30.seconds,
    /** SOS clips are deleted this long after they were written (same span as the cloud purge, ADR 0011/0013). */
    val clipRetention: Duration = 30.days,
    /** How often the card's timeout is checked (also while the mic is off). */
    val tick: Duration = 1.seconds,
    /** Debug builds log each window's level and the baseline (numbers only), for tuning on the emulator. */
    val logLevels: Boolean = false,
) {
    val samplesPerWindow: Int get() = (sampleRate * window.inWholeMilliseconds / 1000).toInt()
    val baselineWindows: Int get() = (baselineSpan / window).toInt()
    val clipSamples: Int get() = (sampleRate * clipLength.inWholeMilliseconds / 1000).toInt()
    val preRollSamples: Int get() = (sampleRate * preRoll.inWholeMilliseconds / 1000).toInt()

    companion object {
        val DEFAULT = CueConfig()
    }
}
