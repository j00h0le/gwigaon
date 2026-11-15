package com.coral.gwigaon.trip

import com.coral.gwigaon.trip.EndedTripUploads.Outcome
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class EndedTripUploadsTest {
    private val stops = mutableListOf<Outcome>()
    private var waitingShown = 0
    private val settledFor = mutableListOf<String>()
    private var cloudDone = CompletableDeferred<Unit>()
    private var online = true

    /** Settles when [cloudDone] completes, or gives up at the timeout, like [com.coral.gwigaon.sync.UploadQueue.awaitSettled]. */
    private val settle: suspend (String, Duration) -> Boolean = { tripId, timeout ->
        settledFor += tripId
        withTimeoutOrNull(timeout) { cloudDone.await(); true } ?: false
    }

    private fun TestScope.uploads(withCloud: Boolean = true) = EndedTripUploads(
        scope = backgroundScope,
        settle = if (withCloud) settle else null,
        online = { online },
        grace = 2.minutes,
        onWaiting = { waitingShown++ },
        stop = { stops += it },
    )

    @Test
    fun anEndedTripStopsOnlyOnceItIsInTheCloud() = runTest {
        val u = uploads()
        u.onActive("a")
        u.onActive(null)
        advanceTimeBy(30.seconds)
        assertEquals(emptyList<Outcome>(), stops)
        assertEquals(1, waitingShown)
        assertEquals(listOf("a"), settledFor)

        cloudDone.complete(Unit)
        runCurrent()
        assertEquals(listOf(Outcome.SETTLED), stops)
    }

    @Test
    fun aNewTripDuringTheWaitCancelsItAndNeverStops() = runTest {
        val u = uploads()
        u.onActive("a")
        u.onActive(null)
        advanceTimeBy(10.seconds)
        u.onActive("b")
        advanceTimeBy(5.minutes)
        cloudDone.complete(Unit)
        runCurrent()
        assertTrue(stops.isEmpty())

        u.onActive(null) // "b" ends: waits for b, not a
        runCurrent()
        assertEquals(listOf("a", "b"), settledFor)
        assertEquals(listOf(Outcome.SETTLED), stops)
    }

    @Test
    fun theGraceRunningOutStillStops() = runTest {
        val u = uploads()
        u.onActive("a")
        u.onActive(null)
        advanceTimeBy(2.minutes - 1.seconds)
        assertTrue(stops.isEmpty())
        advanceTimeBy(2.seconds)
        assertEquals(listOf(Outcome.TIMED_OUT), stops)
    }

    @Test
    fun offlineAtTheEndStopsAtOnceWithoutTheSendingNotice() = runTest {
        online = false
        val u = uploads()
        u.onActive("a")
        u.onActive(null)
        runCurrent()
        assertEquals(listOf(Outcome.OFFLINE), stops)
        assertEquals(0, waitingShown)
        assertTrue(settledFor.isEmpty())
    }

    @Test
    fun noCloudOrNoTripStopsAtOnce() = runTest {
        val noCloud = uploads(withCloud = false)
        noCloud.onActive("a")
        noCloud.onActive(null)
        val noTrip = uploads()
        noTrip.onActive(null)
        runCurrent()
        assertEquals(listOf(Outcome.NOTHING_TO_SEND, Outcome.NOTHING_TO_SEND), stops)
        assertEquals(0, waitingShown)
    }
}
