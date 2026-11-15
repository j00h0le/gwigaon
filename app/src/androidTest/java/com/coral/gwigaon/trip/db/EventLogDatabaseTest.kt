package com.coral.gwigaon.trip.db

import android.database.sqlite.SQLiteConstraintException
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.coral.gwigaon.trip.EndReason
import com.coral.gwigaon.trip.PinAttempts
import com.coral.gwigaon.trip.SosSource
import com.coral.gwigaon.trip.TripEvent
import com.coral.gwigaon.trip.TripStatus
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

@RunWith(AndroidJUnit4::class)
class EventLogDatabaseTest {
    private lateinit var db: GwigaonDatabase
    private val t0 = Instant.parse("2026-11-11T09:00:00Z")

    @Before
    fun open() {
        db = GwigaonDatabase.inMemory(ApplicationProvider.getApplicationContext())
    }

    @After
    fun close() = db.close()

    private suspend fun newTrip(id: String = "trip-1") = TripEntity(
        id = id,
        status = TripStatus.ACTIVE.wire,
        destinationLabel = "집",
        destinationLat = 37.5,
        destinationLng = 127.0,
        contactLabel = "언니",
        pinHash = "pbkdf2-sha256\$1\$AA\$AA",
        startedAt = t0.toEpochMilliseconds(),
        lastSeenAt = t0.toEpochMilliseconds(),
    ).also { db.trips().insert(it) }

    @Test
    fun seqStartsAtOneAndStrictlyIncreasesPerTrip() = runTest {
        newTrip("a")
        newTrip("b")
        val events = db.events()
        val a1 = events.append("a", t0, 37.5, 127.0, TripEvent.TripStarted())
        val b1 = events.append("b", t0, null, null, TripEvent.TripStarted())
        val a2 = events.append("a", t0 + 5.seconds, 37.6, 127.1, TripEvent.LongStop(420, "7분 정차"))
        val a3 = events.append("a", t0 + 9.seconds, null, null, TripEvent.LongStopCleared())

        assertEquals(listOf(1, 1, 2, 3), listOf(a1.seq, b1.seq, a2.seq, a3.seq))
        assertEquals(listOf(1, 2, 3), events.forTrip("a").map { it.seq })
        assertEquals(listOf(1), events.forTrip("b").map { it.seq })
    }

    @Test
    fun concurrentAppendsNeverShareASeq() = runTest {
        newTrip()
        val seqs = (1..40).map { async { db.events().append("trip-1", t0, null, null, TripEvent.Sos(SosSource.ON_SCREEN)).seq } }
            .awaitAll()
        assertEquals((1..40).toList(), seqs.sorted())
        assertEquals((1..40).toList(), db.events().forTrip("trip-1").map { it.seq })
    }

    @Test
    fun storedRowReadsBackAsTheSameEvent() = runTest {
        newTrip()
        val written = db.events().append("trip-1", t0, 37.51, 127.02, TripEvent.TripEnded(EndReason.SAFE_ARRIVAL, "안전 귀가"))
        val row = db.events().forTrip("trip-1").single()
        assertEquals("TRIP_ENDED", row.type)
        assertEquals("""{"reason":"safe_arrival"}""", row.payload)
        assertEquals(written, row.toRecord().getOrThrow())
        assertEquals(written, db.events().observe("trip-1").first().single().toRecord().getOrThrow())
    }

    @Test
    fun eventDaoHasNoUpdateOrDeleteApi() {
        val names = EventDao::class.java.declaredMethods.map { it.name.lowercase() }
        val writes = names.filter { n -> listOf("update", "delete", "remove", "upsert", "clear", "replace").any { it in n } }
        assertTrue("EventDao exposes $writes", writes.isEmpty())
    }

    @Test
    fun databaseRefusesUpdatesAndDeletesOfEvents() = runTest {
        newTrip()
        db.events().append("trip-1", t0, null, null, TripEvent.TripStarted())
        val sql = db.openHelper.writableDatabase
        listOf(
            "UPDATE events SET type = 'SOS_CANCELLED' WHERE seq = 1",
            "DELETE FROM events WHERE seq = 1",
        ).forEach { stmt ->
            try {
                sql.execSQL(stmt)
                fail("allowed: $stmt")
            } catch (e: SQLiteConstraintException) {
                assertTrue(e.message.orEmpty().contains("append-only"))
            }
        }
        assertEquals("TRIP_STARTED", db.events().forTrip("trip-1").single().type)
    }

    @Test
    fun eventForUnknownTripIsRejected() = runTest {
        try {
            db.events().append("nope", t0, null, null, TripEvent.TripStarted())
            fail("event without a trip was stored")
        } catch (_: SQLiteConstraintException) {
            assertTrue(db.events().forTrip("nope").isEmpty())
        }
    }

    @Test
    fun everyAppendIsQueuedAndSosGoesFirst() = runTest {
        newTrip()
        val events = db.events()
        events.append("trip-1", t0, null, null, TripEvent.TripStarted())
        events.append("trip-1", t0 + 1.seconds, null, null, TripEvent.RouteDeviation(1200))
        events.append("trip-1", t0 + 2.seconds, 37.5, 127.0, TripEvent.Sos(SosSource.TURN_SIGNAL))
        val queue = db.uploadQueue()

        val due = queue.due(now = (t0 + 2.seconds).toEpochMilliseconds(), limit = 10)
        assertEquals(listOf(3, 1, 2), due.map { it.event.seq })
        assertEquals(listOf(true, false, false), due.map { it.urgent })
        assertEquals("SOS", due.first().event.type)
        assertEquals(3, queue.observePending().first())
    }

    @Test
    fun queueRetriesLaterAndForgetsUploadedEntries() = runTest {
        newTrip()
        db.events().append("trip-1", t0, null, null, TripEvent.TripStarted())
        db.events().append("trip-1", t0, null, null, TripEvent.Arrived())
        val queue = db.uploadQueue()
        val now = t0.toEpochMilliseconds()
        val (first, second) = queue.due(now, 10)

        queue.retryLater(first.queueId, now + 4_000)
        queue.remove(listOf(second.queueId))

        assertTrue(queue.due(now, 10).isEmpty())
        val retried = queue.due(now + 4_000, 10).single()
        assertEquals(1, retried.event.seq)
        assertEquals(1, retried.attempts)
        // Uploading removes the queue entry, never the event.
        assertEquals(2, db.events().forTrip("trip-1").size)
    }

    @Test
    fun activeTripStatusPinAttemptsAndHeartbeatPersist() = runTest {
        newTrip()
        val trips = db.trips()
        assertEquals("trip-1", trips.active()?.id)
        assertEquals(PinAttempts(), trips.byId("trip-1")!!.pinAttempts)

        val locked = PinAttempts(failures = 0, lockedUntil = t0 + 30.seconds)
        trips.setPinAttempts("trip-1", locked)
        trips.touch("trip-1", (t0 + 60.seconds).toEpochMilliseconds())
        val saved = trips.byId("trip-1")!!
        assertEquals(locked, saved.pinAttempts)
        assertEquals((t0 + 60.seconds).toEpochMilliseconds(), saved.lastSeenAt)

        trips.setStatus("trip-1", TripStatus.ENDED.wire, endedAt = (t0 + 90.seconds).toEpochMilliseconds())
        assertNull(trips.active())
        assertNull(trips.observeActive().first())
        assertEquals(TripStatus.ENDED.wire, trips.byId("trip-1")!!.status)
    }

    @Test
    fun entriesRefusedForGoodAreKeptButNeverDueOrPending() = runTest {
        newTrip("a")
        db.events().append("a", t0, null, null, TripEvent.TripStarted())
        db.events().append("a", t0, null, null, TripEvent.LongStop(420, "7분 정차"))
        val store = RoomSyncStore(db)
        val (first, second) = store.due(Instant.DISTANT_FUTURE, 10)
        assertTrue(store.hasPending("a"))

        store.markFailed(listOf(first.queueId), t0)

        assertEquals(listOf(second.seq), store.due(Instant.DISTANT_FUTURE, 10).map { it.seq })
        assertEquals(1, db.uploadQueue().observePending().first())
        store.remove(listOf(second.queueId))
        assertEquals(false, store.hasPending("a"))
        assertNull(store.nextAttemptAt())
        assertEquals(2, db.events().forTrip("a").size) // the local log keeps the refused event
    }
}
