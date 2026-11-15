package com.coral.gwigaon.trip

import kotlin.time.Duration
import kotlin.time.Instant

/** What the PIN keypad does: end the trip, or the passenger closing the safe-arrival check. */
enum class PinPurpose { END_TRIP, SAFE_ARRIVAL }

/** The running lockout on the PIN screen (D6): how many wrong tries caused it and how long is left. */
data class PinLock(val failures: Int, val left: Duration)

/**
 * Pure rules for the passenger's PIN screen (D6, `design/d2/proto/arrival.html`). The passenger types on a keypad;
 * the 4th digit checks the PIN, so there is no confirm button. The lockout is read back from the trip's stored
 * attempts, so closing and reopening the screen shows the same countdown.
 */
object PinEntry {
    /** A typed digit, if there is room for it; the PIN never grows past [PinHasher.PIN_LENGTH]. */
    fun type(pin: String, digit: Char): String =
        if (digit in '0'..'9' && pin.length < PinHasher.PIN_LENGTH) pin + digit else pin

    fun delete(pin: String): String = pin.dropLast(1)

    fun complete(pin: String): Boolean = pin.length == PinHasher.PIN_LENGTH

    /**
     * Whether a complete PIN closes the safe-arrival check rather than ending the trip, decided from the trip's
     * status when the PIN is checked, not when the keypad opened: the check can open (D5) while the passenger is
     * still typing on a keypad opened from D4, and the same PIN must then close it (Checkpoint F, F1). Closing the
     * check with the passenger's PIN ends the trip as `safe_arrival`.
     */
    fun closesCheck(status: TripStatus?): Boolean = status == TripStatus.SAFE_ARRIVAL_CHECK

    /** What a complete PIN does, from the trip's status when it is checked ([closesCheck]). */
    fun purpose(status: TripStatus?): PinPurpose = if (closesCheck(status)) PinPurpose.SAFE_ARRIVAL else PinPurpose.END_TRIP

    fun lock(attempts: PinAttempts, now: Instant): PinLock? =
        attempts.lockedUntil?.takeIf { now < it }?.let { PinLock(attempts.failures, it - now) }

    /** "0:27", rounded up so it never shows 0:00 while still locked. */
    fun countdown(left: Duration): String {
        val s = ((left.inWholeMilliseconds + 999) / 1000).coerceAtLeast(1)
        return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
    }

    /** Under the dots after a wrong PIN. */
    fun wrong(triesLeft: Int): String = "PIN이 달라요 · ${triesLeft}번 더 틀리면 잠깐 잠겨요"

    /** Under the lockout title; the lockout is logged as `PIN_LOCKOUT`, so the 보호자 sees it. */
    fun locked(failures: Int): String = "${failures}번 틀려서 잠겼어요 · 비상 연락처 기록에 남았어요"
}
