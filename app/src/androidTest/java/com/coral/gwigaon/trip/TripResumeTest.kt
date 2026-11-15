package com.coral.gwigaon.trip

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.coral.gwigaon.trip.db.GwigaonDatabase
import com.coral.gwigaon.trip.db.RoomTripStore
import com.coral.gwigaon.vehicle.Destination
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** Resume after process death, end to end through Room: a second repository over the same database is the new process. */
@RunWith(AndroidJUnit4::class)
class TripResumeTest {
    private class StepClock(var now: Instant) : Clock {
        override fun now() = now
    }

    private lateinit var db: GwigaonDatabase
    private val clock = StepClock(Instant.parse("2026-11-11T13:00:00Z"))
    private val hasher = PinHasher(iterations = 1_000)

    @Before
    fun open() {
        db = GwigaonDatabase.inMemory(ApplicationProvider.getApplicationContext())
    }

    @After
    fun close() = db.close()

    private fun process() = TripSessionRepository(RoomTripStore(db), hasher, clock)

    @Test
    fun killedTripResumesWithTheGapLogged() = runTest {
        val first = process()
        val trip = first.start(TripSetup(Destination("집", 37.5, 127.0), "언니", "2580")).getOrThrow().trip
        first.log(TripEvent.Sos(SosSource.ON_SCREEN)).getOrThrow()
        clock.now += 40.seconds
        first.heartbeat()
        val lastSeen = clock.now
        repeat(3) { first.checkPin("0000") }

        clock.now += 3.minutes // process dead
        val second = process()
        val resumed = second.resumeIfActive().getOrThrow()
        assertNotNull(resumed)
        resumed!!

        val rows = db.events().forTrip(trip.id)
        assertEquals(listOf("TRIP_STARTED", "SOS", "MONITORING_INTERRUPTED", "MONITORING_RESUMED"), rows.map { it.type })
        assertEquals(listOf(1, 2, 3, 4), rows.map { it.seq })
        val gap = rows[2]
        assertEquals("""{"gap_s":180}""", gap.payload)
        assertEquals("앱 재시작으로 3분간 모니터링 중단", gap.reason)
        assertEquals(lastSeen.toEpochMilliseconds(), gap.occurredAt)
        assertEquals(clock.now.toEpochMilliseconds(), rows[3].occurredAt)

        assertTrue(resumed.state.sosActive)
        assertEquals(3, resumed.trip.pinAttempts.failures)
        assertEquals(clock.now.toEpochMilliseconds(), db.trips().byId(trip.id)!!.lastSeenAt)
        assertEquals(1, db.uploadQueue().due(Long.MAX_VALUE, 10).count { it.event.type == "MONITORING_INTERRUPTED" })
    }

    @Test
    fun endedTripIsNotResumed() = runTest {
        val first = process()
        val trip = first.start(TripSetup(Destination("집", 37.5, 127.0), "언니", "2580")).getOrThrow().trip
        first.endByPassenger("2580").getOrThrow()
        clock.now += 1.minutes

        assertEquals(null, process().resumeIfActive().getOrThrow())
        assertEquals(TripStatus.ENDED.wire, db.trips().byId(trip.id)!!.status)
        assertEquals(2, db.events().forTrip(trip.id).size)
    }
}
