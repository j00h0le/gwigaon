package com.coral.gwigaon.trip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class TestClock(var now: Instant = Instant.parse("2026-11-11T09:00:00Z")) : Clock {
    override fun now(): Instant = now
    fun advance(by: Duration) {
        now += by
    }
}

class PinHasherTest {
    // Low cost keeps the suite fast; the default cost is checked once below.
    private val hasher = PinHasher(iterations = 1_000)

    @Test
    fun correctPinVerifiesAndWrongPinDoesNot() {
        val stored = hasher.hash("2580")
        assertTrue(hasher.verify("2580", stored))
        assertFalse(hasher.verify("2581", stored))
        assertFalse(hasher.verify("0852", stored))
    }

    @Test
    fun saltMakesEveryHashDifferent() {
        assertNotEquals(hasher.hash("1234"), hasher.hash("1234"))
    }

    @Test
    fun storedFormNamesSchemeAndCostAndNeverContainsThePin() {
        val stored = PinHasher().hash("1234")
        val parts = stored.split("$")
        assertEquals(4, parts.size)
        assertEquals("pbkdf2-sha256", parts[0])
        assertEquals(PinHasher.DEFAULT_ITERATIONS.toString(), parts[1])
        assertFalse(stored.contains("1234"))
        assertTrue(PinHasher().verify("1234", stored))
    }

    @Test
    fun hashUsesTheCostItWasStoredWith() {
        val stored = PinHasher(iterations = 500).hash("4321")
        assertTrue(PinHasher(iterations = 2_000).verify("4321", stored))
    }

    @Test
    fun onlyFourDigitPinsAreValid() {
        assertTrue(PinHasher.isValidPin("0000"))
        listOf("", "123", "12345", "12a4", "١٢٣٤", " 123").forEach { assertFalse(it, PinHasher.isValidPin(it)) }
        assertThrows { hasher.hash("123") }
        assertFalse(hasher.verify("123", hasher.hash("1234")))
    }

    @Test
    fun malformedStoredHashIsRejectedNotThrown() {
        val good = hasher.hash("1234").split("$")
        listOf(
            "",
            "plain",
            "md5$1000$${good[2]}$${good[3]}",
            "pbkdf2-sha256$0$${good[2]}$${good[3]}",
            "pbkdf2-sha256$99999999999$${good[2]}$${good[3]}",
            "pbkdf2-sha256$1000$!!!$${good[3]}",
            "pbkdf2-sha256$1000$$${good[3]}",
            "pbkdf2-sha256$1000$${good[2]}\$AAAA",
            "pbkdf2-sha256$1000$${good[2]}",
        ).forEach { assertFalse(it, hasher.verify("1234", it)) }
    }

    @Test
    fun costOutOfRangeIsRejected() {
        assertThrows { PinHasher(iterations = 0) }
    }
}

class PinGateTest {
    private val hasher = PinHasher(iterations = 1_000)
    private val stored = hasher.hash("2580")
    private val clock = TestClock()
    private val gate = PinGate(hasher, clock)

    @Test
    fun correctPinPassesAndClearsFailures() {
        val result = gate.check("2580", stored, PinAttempts(failures = 3))
        assertEquals(PinResult.Accepted(PinAttempts()), result)
    }

    @Test
    fun wrongPinCountsDown() {
        var attempts = PinAttempts()
        listOf(4, 3, 2, 1).forEach { left ->
            val r = gate.check("0000", stored, attempts) as PinResult.Wrong
            assertEquals(left, r.triesLeft)
            attempts = r.attempts
        }
        assertEquals(PinAttempts(failures = 4), attempts)
    }

    @Test
    fun fifthWrongPinLocksForThirtySeconds() {
        val r = gate.check("0000", stored, PinAttempts(failures = 4))
        val locked = r as PinResult.LockedOut
        assertEquals(clock.now + 30.seconds, locked.until)
        assertEquals(locked.until, locked.attempts.lockedUntil)
        assertEquals(locked.until, gate.lockedUntil(locked.attempts))
    }

    @Test
    fun evenTheCorrectPinIsRefusedDuringLockout() {
        val attempts = PinAttempts(lockedUntil = clock.now + 30.seconds)
        clock.advance(29.seconds)
        val r = gate.check("2580", stored, attempts)
        assertEquals(PinResult.LockedOut(attempts.lockedUntil!!, attempts), r)
    }

    @Test
    fun lockoutExpiresAndCountStartsFresh() {
        var attempts = (gate.check("0000", stored, PinAttempts(failures = 4)) as PinResult.LockedOut).attempts
        clock.advance(30.seconds)
        assertNull(gate.lockedUntil(attempts))

        val wrong = gate.check("0000", stored, attempts) as PinResult.Wrong
        assertEquals(4, wrong.triesLeft)
        attempts = wrong.attempts
        assertTrue(gate.check("2580", stored, attempts) is PinResult.Accepted)
    }

    @Test
    fun eachFurtherRoundOfWrongPinsLocksTwiceAsLongUpToTheCap() {
        var attempts = PinAttempts()
        val lockouts = mutableListOf<Duration>()
        repeat(5 * 8) {
            clock.advance(1.hours) // any lockout has run out
            val r = gate.check("0000", stored, attempts)
            assertFalse(r is PinResult.Accepted)
            if (r is PinResult.LockedOut) lockouts += r.until - clock.now
            attempts = r.attempts
        }
        assertEquals(
            listOf(30.seconds, 60.seconds, 2.minutes, 4.minutes, 8.minutes, 15.minutes, 15.minutes, 15.minutes),
            lockouts,
        )
    }

    @Test
    fun waitingOutALockoutDoesNotResetTheCount() {
        var attempts = (gate.check("0000", stored, PinAttempts(failures = 4)) as PinResult.LockedOut).attempts
        assertEquals(5, attempts.failures)
        clock.advance(30.seconds)
        repeat(4) { attempts = (gate.check("0000", stored, attempts) as PinResult.Wrong).attempts }
        val second = gate.check("0000", stored, attempts) as PinResult.LockedOut
        assertEquals(clock.now + 60.seconds, second.until)
    }

    @Test
    fun correctPinAfterLockoutsResetsEverything() {
        val attempts = PinAttempts(failures = 23, lockedUntil = clock.now - 1.seconds)
        assertEquals(PinResult.Accepted(PinAttempts()), gate.check("2580", stored, attempts))
    }

    @Test
    fun lockoutForStaysBoundedForAbsurdRounds() {
        assertEquals(30.seconds, gate.lockoutFor(round = 0))
        assertEquals(15.minutes, gate.lockoutFor(round = Int.MAX_VALUE))
    }

    @Test
    fun malformedPinCountsAsWrong() {
        val r = gate.check("12", stored, PinAttempts())
        assertEquals(PinResult.Wrong(4, PinAttempts(failures = 1)), r)
    }

    @Test
    fun limitsAreConfigurable() {
        val strict = PinGate(hasher, clock, maxTries = 1, lockout = 5.seconds)
        val r = strict.check("0000", stored, PinAttempts()) as PinResult.LockedOut
        assertEquals(clock.now + 5.seconds, r.until)
        assertThrows { PinGate(hasher, clock, maxTries = 0) }
        assertThrows { PinGate(hasher, clock, lockout = 10.minutes, maxLockout = 1.minutes) }
        assertThrows { PinGate(hasher, clock, lockout = Duration.ZERO) }
    }
}

private fun assertThrows(block: () -> Unit) {
    val thrown = runCatching(block).exceptionOrNull()
    assertTrue("expected an exception", thrown is IllegalArgumentException)
}
