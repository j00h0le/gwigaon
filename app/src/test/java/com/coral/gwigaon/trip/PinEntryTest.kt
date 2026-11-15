package com.coral.gwigaon.trip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** The passenger's PIN keypad (D6): typing, the lockout read back from the trip, and its lines. */
class PinEntryTest {
    private val now = Instant.parse("2026-11-13T16:40:00Z")

    @Test
    fun typingStopsAtFourDigitsAndIgnoresNonDigits() {
        var pin = ""
        for (c in "12a345") pin = PinEntry.type(pin, c)
        assertEquals("1234", pin)
        assertTrue(PinEntry.complete(pin))
        assertEquals("123", PinEntry.delete(pin))
        assertFalse(PinEntry.complete("123"))
        assertEquals("", PinEntry.delete(""))
    }

    @Test
    fun aCompletePinClosesTheCheckOnlyWhileTheCheckIsOpen() {
        assertTrue(PinEntry.closesCheck(TripStatus.SAFE_ARRIVAL_CHECK))
        assertFalse(PinEntry.closesCheck(TripStatus.ARRIVING)) // e.g. the driver closed it meanwhile: the PIN ends the trip
        assertFalse(PinEntry.closesCheck(TripStatus.ACTIVE))
        assertFalse(PinEntry.closesCheck(null))
    }

    @Test
    fun theStatusAtCheckTimePicksWhatThePinDoes() {
        // F1: a keypad opened from D4 (to end the trip) closes the check once it has opened underneath.
        assertEquals(PinPurpose.SAFE_ARRIVAL, PinEntry.purpose(TripStatus.SAFE_ARRIVAL_CHECK))
        assertEquals(PinPurpose.END_TRIP, PinEntry.purpose(TripStatus.ARRIVING))
        assertEquals(PinPurpose.END_TRIP, PinEntry.purpose(null))
    }

    @Test
    fun theLockRunsUntilItsEnd() {
        val attempts = PinAttempts(failures = 5, lockedUntil = now + 27.seconds)
        assertEquals(PinLock(5, 27.seconds), PinEntry.lock(attempts, now))
        assertNull(PinEntry.lock(attempts, now + 27.seconds))
        assertNull(PinEntry.lock(PinAttempts(failures = 3), now))
    }

    @Test
    fun countdownRoundsUpAndNeverShowsZero() {
        assertEquals("0:27", PinEntry.countdown(26_100.milliseconds))
        assertEquals("0:01", PinEntry.countdown(1.milliseconds))
        assertEquals("0:01", PinEntry.countdown(0.seconds))
        assertEquals("2:00", PinEntry.countdown(120.seconds))
    }

    @Test
    fun lines() {
        assertEquals("PIN이 달라요 · 2번 더 틀리면 잠깐 잠겨요", PinEntry.wrong(2))
        assertEquals("5번 틀려서 잠겼어요 · 비상 연락처 기록에 남았어요", PinEntry.locked(5))
    }
}
