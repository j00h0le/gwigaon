package com.coral.gwigaon.cues

import com.coral.gwigaon.trip.CueKind
import kotlin.time.Instant

/** A check-in card to show: its level (1 or 2) and the cue that raised it. */
data class CheckInPrompt(val level: Int, val cue: CueKind)

/**
 * Cue levels and the check-in card's life (SPEC conflict-cues), as a pure state machine fed by the caller's clock:
 * - **Level 1:** [CueConfig.loudRunForLevel1] loud windows in a row, or one word hit.
 * - **Level 2:** a level 1 within [CueConfig.repeatWindow] of the previous level 1, or a word hit while the latest
 *   window was loud.
 * - While a card is showing nothing new is prompted (a level 1 still counts towards the next level 2).
 * - After an answered card (`ok`, `sos` or `no_response`), no level-1 card for [CueConfig.checkInCooldown];
 *   level 2 still shows.
 * - An unanswered card times out after [CueConfig.checkInTimeout] ([timedOut]).
 *
 * The caller logs `CHECK_IN_PROMPTED` for each returned prompt and calls [shown]; if logging fails it doesn't,
 * and the rule stays free to prompt again.
 */
class CueLevelRule(private val config: CueConfig) {
    private var loudRun = 0
    private var lastLevel1At: Instant? = null
    private var shownAt: Instant? = null
    private var cooldownUntil: Instant? = null

    /** True while a card is showing. */
    val pending: Boolean get() = shownAt != null

    /** One loudness window ended. Returns the card to show, if any. */
    fun onWindow(loud: Boolean, now: Instant): CheckInPrompt? {
        if (!loud) {
            loudRun = 0
            return null
        }
        if (++loudRun < config.loudRunForLevel1) return null
        loudRun = 0 // the next level 1 needs another full run
        return level1(CueKind.LOUD, now)
    }

    /** Stage 2 heard a distress phrase or profanity (once per utterance). */
    fun onWordHit(cue: CueKind, now: Instant): CheckInPrompt? {
        require(cue != CueKind.LOUD) { "a word hit is distress or profanity" }
        if (loudRun > 0) {
            lastLevel1At = now
            return offer(CheckInPrompt(2, cue))
        }
        return level1(cue, now)
    }

    private fun level1(cue: CueKind, now: Instant): CheckInPrompt? {
        val previous = lastLevel1At
        lastLevel1At = now
        val repeat = previous != null && now - previous <= config.repeatWindow
        if (repeat) return offer(CheckInPrompt(2, cue))
        val cooling = cooldownUntil?.let { now < it } == true
        return if (cooling) null else offer(CheckInPrompt(1, cue))
    }

    private fun offer(prompt: CheckInPrompt): CheckInPrompt? = if (pending) null else prompt

    /** The card was logged and is showing since [at]. */
    fun shown(at: Instant) {
        shownAt = at
    }

    /** The card was answered (or timed out) at [at]: it closes and the cooldown starts. */
    fun answered(at: Instant) {
        shownAt = null
        cooldownUntil = at + config.checkInCooldown
    }

    /** True once a showing card has waited [CueConfig.checkInTimeout]; the caller logs `no_response`. */
    fun timedOut(now: Instant): Boolean = shownAt?.let { now - it >= config.checkInTimeout } == true

    /** A new trip, or the mic turned off: forget the loud run (the cooldown and a showing card stay). */
    fun resetLoudness() {
        loudRun = 0
    }

    /** A new trip: everything starts from scratch. */
    fun reset() {
        loudRun = 0
        lastLevel1At = null
        shownAt = null
        cooldownUntil = null
    }
}
