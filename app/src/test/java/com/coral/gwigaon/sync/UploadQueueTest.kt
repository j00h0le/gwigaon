package com.coral.gwigaon.sync

import com.coral.gwigaon.trip.TestClock
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class UploadQueueTest {
    private val clock = TestClock()
    private val store = FakeSyncStore()
    private val rest = FakeRest()
    private val auth = FakeAuth().also { a -> a.now = { clock.now } }
    private val sessions = SessionManager(auth, MemorySessionStore(), clock)
    private val config = SyncConfig(batchSize = 3)
    private val queue = UploadQueue(store, rest, sessions, clock, config) { "fresh_share_token_0123456789" }

    @Test
    fun backoffDoublesFromTwoSecondsUpToOneMinute() {
        val c = SyncConfig()
        assertEquals(listOf(2, 4, 8, 16, 32, 60, 60), (1..7).map { c.backoff(it).inWholeSeconds.toInt() })
        assertEquals(2.seconds, c.backoff(0))
        assertEquals(60.seconds, c.backoff(Int.MAX_VALUE))
    }

    @Test
    fun uploadsTripRowFirstThenEventsInSeqOrderAndEmptiesTheQueue() = runTest {
        store.addTrip("a")
        listOf(3, 1, 2).forEach { store.enqueue("a", it) }

        val r = queue.drain()

        assertEquals(DrainResult(uploaded = 3, failed = 0), r)
        assertEquals(listOf(listOf("a" to 1, "a" to 2, "a" to 3)), rest.eventRequests)
        assertTrue("a" in rest.trips)
        assertTrue(store.trips.getValue("a").created)
        assertTrue(store.queue.isEmpty())
    }

    @Test
    fun sosGoesFirstAndAloneEvenWhenQueuedLast() = runTest {
        store.addTrip("a")
        (1..4).forEach { store.enqueue("a", it) }
        store.enqueue("a", 5, "SOS", urgent = true)

        queue.drain()

        assertEquals(listOf("a" to 5), rest.eventRequests.first())
        // The rest still go per trip in seq order, in batches of 3.
        assertEquals(listOf(listOf("a" to 1, "a" to 2, "a" to 3), listOf("a" to 4)), rest.eventRequests.drop(1))
    }

    @Test
    fun eachTripsEventsStayInOrderAcrossTrips() = runTest {
        store.addTrip("b")
        store.addTrip("a")
        store.enqueue("b", 2)
        store.enqueue("a", 1)
        store.enqueue("b", 1)

        queue.drain()

        assertEquals(listOf(listOf("a" to 1), listOf("b" to 1, "b" to 2)), rest.eventRequests)
    }

    @Test
    fun resendAfterALostResponseDoesNotDuplicate() = runTest {
        store.addTrip("a")
        store.enqueue("a", 1)
        store.enqueue("a", 2)
        // The server stored the rows but the answer never arrived: the queue keeps them.
        rest.trips["a"] = store.trips.getValue("a")
        rest.events["a" to 1] = store.queue.values.first().row
        rest.eventFailures += offline

        queue.drain()
        assertEquals(2, store.queue.size)
        clock.advance(2.seconds)
        queue.drain()

        assertTrue(store.queue.isEmpty())
        assertEquals(listOf("a" to 1, "a" to 2), rest.events.keys.toList())
    }

    @Test
    fun offlineBacksOffEveryEntryAndStopsThePass() = runTest {
        store.addTrip("a")
        store.addTrip("b")
        store.enqueue("a", 1)
        store.enqueue("b", 1)
        rest.eventFailures += offline

        val r = queue.drain()

        assertEquals(DrainResult(0, 2), r)
        assertEquals(1, rest.eventRequests.size) // trip b was not even tried
        assertTrue(store.queue.values.all { it.nextAt == clock.now + 2.seconds && it.row.attempts == 1 })
    }

    @Test
    fun retriesWaitForTheBackoffAndGrowWithEachFailure() = runTest {
        store.addTrip("a")
        store.enqueue("a", 1)
        repeat(2) { rest.eventFailures += offline }

        queue.drain()
        assertEquals(2.seconds, queue.nextWait())
        clock.advance(1.seconds)
        queue.drain() // not due yet: nothing sent
        assertEquals(1, rest.eventRequests.size)
        clock.advance(1.seconds)
        queue.drain() // second failure → 4 s
        assertEquals(clock.now + 4.seconds, store.queue.values.single().nextAt)
        clock.advance(4.seconds)
        queue.drain()

        assertTrue(store.queue.isEmpty())
        assertEquals(3, rest.eventRequests.size)
    }

    @Test
    fun networkBackSendsEverythingWithoutWaitingForTheBackoff() = runTest {
        store.addTrip("a")
        store.enqueue("a", 1)
        rest.eventFailures += offline
        queue.drain()

        queue.drain(ignoreBackoff = true)

        assertTrue(store.queue.isEmpty())
    }

    @Test
    fun oneRefusedRowDoesNotHoldBackTheRestOfTheBatch() = runTest {
        store.addTrip("a")
        store.enqueue("a", 1)
        store.enqueue("a", 2, "BROKEN")
        store.enqueue("a", 3)
        rest.refusedTypes = setOf("BROKEN")

        val r = queue.drain()

        assertEquals(DrainResult(2, 1), r)
        assertEquals(listOf("a" to 1, "a" to 3), rest.events.keys.toList())
        assertEquals(2, store.queue.values.single().row.seq)
    }

    @Test
    fun forbiddenEventsMeanTheTripRowIsMissingSoItIsInsertedAgain() = runTest {
        store.addTrip("a")
        store.trips["a"] = store.trips.getValue("a").copy(created = true) // we think it's there; the cloud lost it
        store.enqueue("a", 1)

        queue.drain()
        assertFalse(store.trips.getValue("a").created)
        clock.advance(2.seconds)
        queue.drain()

        assertTrue(store.queue.isEmpty())
        assertTrue("a" in rest.trips)
    }

    @Test
    fun unauthorizedRenewsTheSessionAndRetries() = runTest {
        store.addTrip("a")
        store.enqueue("a", 1)
        rest.eventFailures += CloudError.Http(401, "PGRST301")

        queue.drain()
        clock.advance(2.seconds)
        queue.drain()

        assertTrue(store.queue.isEmpty())
        assertEquals(1, auth.signUps)
        assertEquals(1, auth.refreshes)
        assertEquals("access-r1", rest.tokensSeen.last())
    }

    @Test
    fun unauthorizedOnTheTripRowRenewsTheSessionAndRetries() = runTest {
        store.addTrip("a")
        store.enqueue("a", 1)
        rest.tripFailures += CloudError.Http(401, "PGRST301")

        queue.drain()
        assertEquals(1, store.queue.size)
        clock.advance(2.seconds)
        queue.drain()

        assertTrue(store.queue.isEmpty())
        assertTrue("a" in rest.trips)
        assertEquals(1, auth.refreshes)
        assertEquals("access-r1", rest.tokensSeen.last())
    }

    @Test
    fun aTripRowRefusedForAGoneUserGetsANewSession() = runTest {
        store.addTrip("a")
        store.enqueue("a", 1)
        rest.tripFailures += CloudError.Http(409, "23503")
        auth.refreshFailures += CloudError.Http(400, "refresh_token_not_found")

        queue.drain()
        clock.advance(2.seconds)
        queue.drain()

        assertTrue(store.queue.isEmpty())
        assertEquals(2, auth.signUps)
    }

    @Test
    fun tripsLoggedBeforeShareTokensGetOneAtFirstUpload() = runTest {
        store.addTrip("old", token = "")
        store.enqueue("old", 1)

        queue.drain()

        assertEquals("fresh_share_token_0123456789", store.trips.getValue("old").shareToken)
        assertEquals("fresh_share_token_0123456789", rest.trips.getValue("old").shareToken)
    }

    @Test
    fun statusFollowsTheEventsAndIsResentUntilItGoesThrough() = runTest {
        store.addTrip("a", status = "ended")
        store.enqueue("a", 1, "TRIP_ENDED")
        rest.patchFailures += offline

        queue.drain()
        assertEquals(null, store.trips.getValue("a").pushedStatus)
        queue.drain() // nothing queued, but the status is still behind

        assertEquals("ended", rest.patches.single().status)
        assertEquals("ended", store.trips.getValue("a").pushedStatus)
        queue.drain()
        assertEquals(1, rest.patches.size)
    }

    @Test
    fun pushTripSkipsTripsTheCloudDoesNotHaveYet() = runTest {
        store.addTrip("a")
        assertFalse(queue.pushTrip("a"))
        assertFalse(queue.pushTrip("missing"))
        assertTrue(rest.patches.isEmpty())
    }

    @Test
    fun noSessionMeansEverythingWaitsForTheNextTry() = runTest {
        store.addTrip("a")
        store.enqueue("a", 1)
        auth.signUpFailure = offline

        val r = queue.drain()

        assertEquals(DrainResult(0, 1), r)
        assertTrue(rest.eventRequests.isEmpty())
        assertEquals(clock.now + 2.seconds, store.queue.values.single().nextAt)
    }

    @Test
    fun idleWaitIsCappedAndNeverBusyLoops() = runTest {
        assertEquals(30.seconds, queue.nextWait())
        store.addTrip("a")
        val id = store.enqueue("a", 1)
        store.retryLater(id, clock.now - 5.seconds)
        assertEquals(1.seconds, queue.nextWait())
        store.retryLater(id, clock.now + 10.minutes)
        assertEquals(30.seconds, queue.nextWait())
    }

    @Test
    fun loopDrainsOnWakeAndOnNetworkBack() = runTest {
        store.addTrip("a")
        val wakes = MutableSharedFlow<Unit>()
        val networkBack = MutableSharedFlow<Unit>()
        val job = queue.run(backgroundScope, wakes, networkBack)
        runCurrent()

        store.enqueue("a", 1)
        wakes.emit(Unit)
        runCurrent()
        assertTrue(store.queue.isEmpty())

        rest.eventFailures += offline
        store.enqueue("a", 2)
        wakes.emit(Unit)
        runCurrent()
        assertEquals(1, store.queue.size)
        networkBack.emit(Unit)
        runCurrent()
        assertTrue(store.queue.isEmpty())
        assertTrue(job.isActive)
    }

    @Test
    fun loopRetriesOnItsOwnWhenTheBackoffEnds() = runTest {
        store.addTrip("a")
        store.enqueue("a", 1)
        rest.eventFailures += offline
        queue.run(backgroundScope, MutableSharedFlow(), MutableSharedFlow())
        runCurrent()
        assertEquals(1, store.queue.size)

        clock.advance(2.seconds)
        advanceTimeBy(2.seconds + 1.milliseconds)
        runCurrent()

        assertTrue(store.queue.isEmpty())
    }

    // ── Checkpoint D review: "ended" waits for the last events; refusals for good are not retried ──

    @Test
    fun endedIsNotSentWhileTheTripsLastEventsAreStillQueued() = runTest {
        store.addTrip("a", status = "ended")
        store.trips["a"] = store.trips.getValue("a").copy(created = true, pushedStatus = "active")
        store.enqueue("a", 7, "TRIP_ENDED")
        rest.trips["a"] = store.trips.getValue("a")
        rest.eventFailures += offline

        queue.drain()
        assertFalse(queue.pushTrip("a")) // the heartbeat can't send it either
        assertTrue(rest.patches.isEmpty())

        queue.drain(ignoreBackoff = true)
        assertEquals(listOf("a" to 7), rest.events.keys.toList())
        assertEquals("ended", rest.patches.single().status)
        assertTrue(queue.settled("a")) // the refused row doesn't keep TripService waiting (/ship Q5)
    }

    @Test
    fun anEventOverTheTripLimitIsGivenUpAndTheRestStillGoUp() = runTest {
        store.addTrip("a")
        (1..3).forEach { store.enqueue("a", it) }
        rest.eventCap = 2

        val r = queue.drain()

        assertEquals(DrainResult(uploaded = 2, failed = 1), r)
        assertEquals(listOf("a" to 3), store.failed())
        val requests = rest.eventRequests.size
        queue.drain(ignoreBackoff = true)
        assertEquals(requests, rest.eventRequests.size) // never sent again
        assertEquals(null, store.nextAttemptAt())
    }

    @Test
    fun aRefusedRowIsNotRetriedAndDoesNotHoldBackTheEndedStatus() = runTest {
        store.addTrip("a", status = "ended")
        store.enqueue("a", 1, "BROKEN")
        store.enqueue("a", 2, "TRIP_ENDED")
        rest.refusedTypes = setOf("BROKEN")

        queue.drain()
        queue.drain(ignoreBackoff = true)

        assertEquals(listOf("a" to 1), store.failed())
        assertEquals(1, rest.eventRequests.count { ("a" to 1) in it && it.size == 1 })
        assertEquals("ended", rest.patches.single().status)
    }

    @Test
    fun aServerErrorWithoutALimitCodeIsRetriedNotGivenUp() = runTest {
        store.addTrip("a")
        store.enqueue("a", 1)
        store.enqueue("a", 2)
        rest.eventFailures += CloudError.Http(503, null)

        val r = queue.drain()

        assertEquals(DrainResult(0, 2), r)
        assertEquals(1, rest.eventRequests.size) // not split into one request per row
        assertTrue(store.failed().isEmpty())
        clock.advance(2.seconds)
        queue.drain()
        assertTrue(store.queue.isEmpty())
    }

    @Test
    fun eventsOfATripRowOwnedByAnotherUserAreGivenUp() = runTest {
        store.addTrip("a")
        rest.trips["a"] = store.trips.getValue("a")
        rest.foreignTrips += "a"
        store.enqueue("a", 1)
        store.addTrip("b")
        store.enqueue("b", 1)

        queue.drain()

        assertEquals(listOf("a" to 1), store.failed())
        assertTrue(("b" to 1) in rest.events)
    }

    @Test
    fun anUnreadablePayloadIsGivenUp() = runTest {
        store.addTrip("a")
        store.enqueue("a", 1)
        rest.eventFailures += kotlinx.serialization.SerializationException("not JSON")

        queue.drain()

        assertEquals(listOf("a" to 1), store.failed())
    }

    @Test
    fun aHeartbeatStillInFlightCannotLandAfterEnded() = runTest {
        store.addTrip("a")
        store.trips["a"] = store.trips.getValue("a").copy(created = true, pushedStatus = "active")
        rest.trips["a"] = store.trips.getValue("a")
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        rest.patchGate = gate

        val heartbeat = launch { queue.pushTrip("a") } // reads "active", then its request hangs
        runCurrent()
        store.trips["a"] = store.trips.getValue("a").copy(status = "ended")
        store.enqueue("a", 9, "TRIP_ENDED")
        val pass = launch { queue.drain() }
        runCurrent()
        assertTrue(rest.events.isEmpty()) // the pass waits for the heartbeat

        gate.complete(Unit)
        heartbeat.join()
        pass.join()

        assertEquals(listOf("active", "ended"), rest.patches.map { it.status })
        assertEquals("ended", store.trips.getValue("a").pushedStatus)
    }

    // ── T22: TripService stays until an ended trip has gone up ──

    @Test
    fun anEndedTripIsSettledOnlyOnceItsEventsAndItsEndAreInTheCloud() = runTest {
        store.addTrip("a", status = "ended")
        store.enqueue("a", 1, "TRIP_ENDED")
        assertFalse(queue.settled("a"))

        rest.patchFailures += offline
        queue.drain() // events up, "ended" not
        assertFalse(queue.settled("a"))

        queue.drain()
        assertTrue(queue.settled("a"))
        assertTrue(queue.settled("unknown"))
    }

    @Test
    fun anEndedTripWhoseRowNeverReachedTheCloudIsNotSettled() = runTest {
        store.addTrip("a", status = "ended") // offline the whole trip: created = false, nothing queued
        assertFalse(queue.settled("a"))
    }

    @Test
    fun awaitSettledReturnsWhenTheLoopUploadsAndGivesUpAtTheTimeout() = runTest {
        store.addTrip("a", status = "ended")
        store.enqueue("a", 1, "TRIP_ENDED")
        var settled: Boolean? = null
        val waiter = launch { settled = queue.awaitSettled("a", timeout = 1.minutes) }
        advanceTimeBy(3.seconds)
        assertEquals(null, settled)
        queue.drain()
        advanceTimeBy(1.seconds)
        runCurrent()
        assertEquals(true, settled)
        waiter.join()

        rest.eventFailures += offline
        store.addTrip("b", status = "ended")
        store.enqueue("b", 1, "TRIP_ENDED")
        queue.drain() // offline: stays queued
        assertFalse(queue.awaitSettled("b", timeout = 10.seconds))
    }
}
