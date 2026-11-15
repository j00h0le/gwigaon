package com.coral.gwigaon.sync

import kotlin.time.Instant

/** In-memory [SyncStore]: a queue of rows with their next-attempt times, and the trips they belong to. */
class FakeSyncStore : SyncStore {
    data class Entry(val row: PendingUpload, val nextAt: Instant, val failedAt: Instant? = null)

    val queue = linkedMapOf<Long, Entry>()
    val trips = linkedMapOf<String, CloudTrip>()
    private var nextId = 0L

    fun addTrip(id: String, status: String = "active", token: String = "tok_${id}_0123456789abcdefghij", at: Instant = T0) {
        trips[id] = CloudTrip(id, token, status, "집", "언니", at, null, at, created = false, pushedStatus = null)
    }

    fun enqueue(tripId: String, seq: Int, type: String = "TRIP_STARTED", urgent: Boolean = false, at: Instant = T0): Long {
        val id = ++nextId
        queue[id] = Entry(PendingUpload(id, urgent, 0, tripId, seq, type, at, 37.5, 127.0, null, "{}"), at)
        return id
    }

    override suspend fun due(now: Instant, limit: Int) = queue.values.filter { it.nextAt <= now && it.failedAt == null }
        .sortedWith(compareByDescending<Entry> { it.row.urgent }.thenBy { it.row.tripId }.thenBy { it.row.seq })
        .take(limit).map { it.row }

    override suspend fun nextAttemptAt() = queue.values.filter { it.failedAt == null }.minOfOrNull { it.nextAt }

    override suspend fun markFailed(queueIds: List<Long>, at: Instant) {
        queueIds.forEach { id -> queue[id] = queue.getValue(id).copy(failedAt = at) }
    }

    override suspend fun hasPending(tripId: String) = queue.values.any { it.row.tripId == tripId && it.failedAt == null }

    /** Entries the queue gave up on. */
    fun failed() = queue.values.filter { it.failedAt != null }.map { it.row.tripId to it.row.seq }

    override suspend fun remove(queueIds: List<Long>) {
        queueIds.forEach(queue::remove)
    }

    override suspend fun retryLater(queueId: Long, at: Instant) {
        val e = queue.getValue(queueId)
        queue[queueId] = Entry(e.row.copy(attempts = e.row.attempts + 1), at)
    }

    override suspend fun trip(id: String) = trips[id]

    override suspend fun tripsToPush() = trips.values.filter { it.created && it.pushedStatus != it.status }

    override suspend fun setCreated(id: String, created: Boolean) {
        trips[id] = trips.getValue(id).copy(created = created)
    }

    override suspend fun setPushedStatus(id: String, status: String) {
        trips[id] = trips.getValue(id).copy(pushedStatus = status)
    }

    override suspend fun setShareToken(id: String, token: String) {
        trips[id] = trips.getValue(id).copy(shareToken = token)
    }

    companion object {
        val T0: Instant = Instant.parse("2026-11-11T09:00:00Z")
    }
}

/**
 * The cloud as the app sees it: trips and events keyed like the real tables (event key = trip_id + seq, so a
 * resend is a no-op), with failures to inject per call.
 */
class FakeRest : RestApi {
    val trips = linkedMapOf<String, CloudTrip>()
    val events = linkedMapOf<Pair<String, Int>, PendingUpload>()
    val eventRequests = mutableListOf<List<Pair<String, Int>>>()
    val patches = mutableListOf<CloudTrip>()
    val tokensSeen = mutableListOf<String>()

    /** Next failures of insertEvents, in order; null = succeed. */
    val eventFailures = ArrayDeque<Exception?>()
    val tripFailures = ArrayDeque<Exception?>()
    val patchFailures = ArrayDeque<Exception?>()

    /** When set, the next patchTrip waits here (a slow request still in flight). */
    var patchGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null

    /** Rows of these types are refused (400) like a CHECK violation, alone or in a batch. */
    var refusedTypes = setOf<String>()

    /** Like the trip_events trigger: past this many events per trip the whole request fails with 500 / 54000. */
    var eventCap = Int.MAX_VALUE

    /** Trips owned by another (older) anonymous user: inserting the row is a no-op, events are refused (403). */
    val foreignTrips = mutableSetOf<String>()

    /** RLS: events for a trip the cloud doesn't have are refused (403). */
    var enforceTripExists = true

    override suspend fun insertTrip(accessToken: String, trip: CloudTrip) {
        tokensSeen += accessToken
        tripFailures.removeFirstOrNull()?.let { throw it }
        trips.putIfAbsent(trip.id, trip)
    }

    override suspend fun insertEvents(accessToken: String, rows: List<PendingUpload>) {
        tokensSeen += accessToken
        eventRequests += rows.map { it.tripId to it.seq }
        eventFailures.removeFirstOrNull()?.let { throw it }
        if (rows.any { it.type in refusedTypes }) throw CloudError.Http(400, "23514")
        if (enforceTripExists && rows.any { it.tripId !in trips || it.tripId in foreignTrips }) throw CloudError.Http(403, "42501")
        val stored = events.keys.groupingBy { it.first }.eachCount()
        val adding = rows.filter { (it.tripId to it.seq) !in events }.groupingBy { it.tripId }.eachCount()
        if (adding.any { (trip, n) -> (stored[trip] ?: 0) + n > eventCap }) throw CloudError.Http(500, "54000")
        rows.forEach { events.putIfAbsent(it.tripId to it.seq, it) }
    }

    override suspend fun patchTrip(accessToken: String, trip: CloudTrip) {
        tokensSeen += accessToken
        patchGate?.also { patchGate = null }?.await()
        patchFailures.removeFirstOrNull()?.let { throw it }
        patches += trip
    }
}

class FakeAuth : AuthApi {
    var signUps = 0
    var refreshes = 0
    val refreshFailures = ArrayDeque<Exception?>()
    var signUpFailure: Exception? = null
    var lifetimeS = 3600L
    var now: () -> Instant = { FakeSyncStore.T0 }

    override suspend fun signUpAnonymously(): CloudSession {
        signUpFailure?.let { throw it }
        signUps++
        return CloudSession("access-s$signUps", "refresh-s$signUps", now() + kotlin.time.Duration.parse("${lifetimeS}s"))
    }

    override suspend fun refresh(refreshToken: String): CloudSession {
        refreshFailures.removeFirstOrNull()?.let { throw it }
        refreshes++
        return CloudSession("access-r$refreshes", "refresh-r$refreshes", now() + kotlin.time.Duration.parse("${lifetimeS}s"))
    }
}

class MemorySessionStore(var session: CloudSession? = null) : SessionStore {
    var saves = 0

    override suspend fun load() = session

    override suspend fun save(session: CloudSession?) {
        saves++
        this.session = session
    }
}

val offline = CloudError.Network(java.net.ConnectException("connection refused"))
