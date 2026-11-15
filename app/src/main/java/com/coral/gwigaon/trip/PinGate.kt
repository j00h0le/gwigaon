package com.coral.gwigaon.trip

import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** Wrong-PIN bookkeeping, persisted with the trip so killing the app doesn't reset the lockout. */
data class PinAttempts(val failures: Int = 0, val lockedUntil: Instant? = null)

sealed interface PinResult {
    /** Store this back with the trip. */
    val attempts: PinAttempts

    data class Accepted(override val attempts: PinAttempts) : PinResult
    data class Wrong(val triesLeft: Int, override val attempts: PinAttempts) : PinResult
    data class LockedOut(val until: Instant, override val attempts: PinAttempts) : PinResult
}

/**
 * SPEC trip-core: 5 wrong tries → 30 s lockout, then each further 5 wrong tries locks twice as long, up to
 * [maxLockout] (ADR 0006). Failures only reset on the correct PIN, so waiting out a lockout doesn't buy a fresh,
 * cheap round: with a 4-digit PIN that would let someone try all 10 000 values in about 17 hours.
 * While locked out the PIN isn't even checked, so a correct guess during the lockout doesn't get through.
 */
class PinGate(
    private val hasher: PinHasher,
    private val clock: Clock,
    private val maxTries: Int = 5,
    private val lockout: Duration = 30.seconds,
    private val maxLockout: Duration = 15.minutes,
) {
    init {
        require(maxTries > 0) { "maxTries must be positive" }
        require(lockout.isPositive() && maxLockout >= lockout) { "lockout must be positive and at most maxLockout" }
    }

    fun check(pin: String, storedHash: String, attempts: PinAttempts): PinResult {
        val now = clock.now()
        lockedUntil(attempts, now)?.let { return PinResult.LockedOut(it, attempts) }
        if (hasher.verify(pin, storedHash)) return PinResult.Accepted(PinAttempts())

        val total = attempts.failures.coerceAtLeast(0) + 1
        val inRound = total % maxTries
        return if (inRound == 0) {
            val until = now + lockoutFor(round = total / maxTries)
            PinResult.LockedOut(until, PinAttempts(failures = total, lockedUntil = until))
        } else {
            PinResult.Wrong(maxTries - inRound, PinAttempts(failures = total))
        }
    }

    /** 30 s, 60 s, 2 min, 4 min, … capped at [maxLockout]; [round] counts from 1. */
    internal fun lockoutFor(round: Int): Duration {
        var d = lockout
        repeat((round - 1).coerceIn(0, MAX_DOUBLINGS)) { d = (d * 2).coerceAtMost(maxLockout) }
        return d.coerceAtMost(maxLockout)
    }

    private companion object {
        /** Enough to reach any sane cap from any sane base without overflowing. */
        const val MAX_DOUBLINGS = 32
    }

    /** When the current lockout ends, or null if the PIN can be tried now (for the countdown on screen). */
    fun lockedUntil(attempts: PinAttempts): Instant? = lockedUntil(attempts, clock.now())

    private fun lockedUntil(attempts: PinAttempts, now: Instant): Instant? =
        attempts.lockedUntil?.takeIf { now < it }
}
