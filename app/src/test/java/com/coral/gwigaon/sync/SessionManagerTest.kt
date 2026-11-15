package com.coral.gwigaon.sync

import com.coral.gwigaon.trip.TestClock
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class SessionManagerTest {
    private val clock = TestClock()
    private val auth = FakeAuth().also { a -> a.now = { clock.now } }
    private val stored = MemorySessionStore()
    private fun manager() = SessionManager(auth, stored, clock)

    @Test
    fun signsUpOnceAndReusesTheSessionAcrossRestarts() = runTest {
        assertEquals("access-s1", manager().accessToken())
        assertEquals("access-s1", manager().accessToken()) // new process, same stored session
        assertEquals(1, auth.signUps)
        assertEquals(1, stored.saves)
    }

    @Test
    fun refreshesAMinuteBeforeExpiryKeepingTheSameUser() = runTest {
        val m = manager()
        m.accessToken()
        clock.advance(58.minutes)
        assertEquals("access-s1", m.accessToken())
        clock.advance(1.minutes + 1.seconds)

        assertEquals("access-r1", m.accessToken())
        assertEquals(1, auth.signUps)
        assertEquals("refresh-r1", stored.session?.refreshToken)
    }

    @Test
    fun refusedRefreshSignsUpAgain() = runTest {
        val m = manager()
        m.accessToken()
        m.invalidate()
        auth.refreshFailures += CloudError.Http(400, "refresh_token_not_found")

        assertEquals("access-s2", m.accessToken())
    }

    @Test
    fun serverErrorOnRefreshIsNotAReasonForANewUser() = runTest {
        val m = manager()
        m.accessToken()
        m.invalidate()
        auth.refreshFailures += CloudError.Http(503, null)

        assertTrue(runCatching { m.accessToken() }.exceptionOrNull() is CloudError.Http)
        assertEquals(1, auth.signUps)
        assertEquals("access-r1", m.accessToken()) // next try refreshes
    }

    @Test
    fun offlineSignUpFailsWithoutStoringAnything() = runTest {
        auth.signUpFailure = offline
        assertTrue(runCatching { manager().accessToken() }.exceptionOrNull() is CloudError.Network)
        assertEquals(0, stored.saves)
    }

    @Test
    fun sessionNeverPrintsItsTokens() {
        val s = CloudSession("secret-access", "secret-refresh", clock.now)
        assertEquals(false, s.toString().contains("secret"))
    }
}
