package com.coral.gwigaon.cues

import kotlin.time.Instant

/** What stage 2 is doing at a moment: nothing, a scheduled sample, or streaming after a hit. */
enum class StageTwoMode { IDLE, SAMPLING, STREAMING }

/**
 * When stage 2 listens (SPEC conflict-cues, ADR 0012), as a pure state machine on the caller's clock:
 * - **Sampling:** a [CueConfig.sampleLength] sample every [CueConfig.sampleEvery], the first one as listening starts.
 * - **Wake-up:** a loud window ([onLoud]) starts streaming at once, whatever the schedule.
 * - **Persist:** every hit (a loud window or a word, [onHit]) keeps streaming; after [CueConfig.calmPeriod] without
 *   one it goes back to sampling, the next sample a full [CueConfig.sampleEvery] later.
 */
class StageTwoScheduler(private val config: CueConfig) {
    private var nextSampleAt: Instant? = null
    private var sampleUntil: Instant? = null
    private var lastHitAt: Instant? = null

    /** Listening starts (or starts again): sample now, nothing remembered. */
    fun reset(now: Instant) {
        nextSampleAt = now
        sampleUntil = null
        lastHitAt = null
    }

    /** A loud window: wake up (or stay awake). */
    fun onLoud(now: Instant) = onHit(now)

    /** A word hit or a loud window: stream from now until a calm period passes. */
    fun onHit(now: Instant) {
        lastHitAt = now
    }

    /** What stage 2 should be doing at [now]; moves the schedule along as time passes. */
    fun mode(now: Instant): StageTwoMode {
        val hit = lastHitAt
        if (hit != null) {
            if (now - hit < config.calmPeriod) return StageTwoMode.STREAMING
            lastHitAt = null
            sampleUntil = null
            nextSampleAt = now + config.sampleEvery
            return StageTwoMode.IDLE
        }
        if (sampleUntil?.let { now < it } == true) return StageTwoMode.SAMPLING
        val next = nextSampleAt ?: return StageTwoMode.IDLE
        if (now < next) return StageTwoMode.IDLE
        sampleUntil = now + config.sampleLength
        nextSampleAt = now + config.sampleEvery
        return StageTwoMode.SAMPLING
    }
}
