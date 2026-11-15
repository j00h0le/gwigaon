package com.coral.gwigaon.sync

import com.coral.gwigaon.trip.TestClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

class HeartbeatTest {
    private val clock = TestClock()
    private val store = FakeSyncStore()
    private val rest = FakeRest()
    private val queue = UploadQueue(store, rest, SessionManager(FakeAuth(), MemorySessionStore(), clock), clock)

    @Test
    fun sendsEvery30SecondsWhileATripRunsAndStopsAfter() = runTest {
        store.addTrip("a")
        store.trips["a"] = store.trips.getValue("a").copy(created = true)
        val active = MutableStateFlow<String?>("a")
        Heartbeat(queue, 30.seconds).run(backgroundScope, active)

        runCurrent()
        assertEquals(1, rest.patches.size)
        advanceTimeBy(30.seconds)
        runCurrent()
        assertEquals(2, rest.patches.size)

        active.value = null
        advanceTimeBy(150.seconds)
        runCurrent()
        assertEquals(2, rest.patches.size)
    }
}
