package com.coral.gwigaon.sync

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerializationException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

data class SyncConfig(
    /** Events per request for ordinary uploads; an SOS always goes alone and first. */
    val batchSize: Int = 50,
    /** First retry after this, doubling per failed attempt up to [backoffMax]. */
    val backoffBase: Duration = 2.seconds,
    val backoffMax: Duration = 60.seconds,
    /** The loop looks at the queue at least this often, even with nothing new. */
    val idleWake: Duration = 30.seconds,
    /** How often the running trip's `last_seen_at` is sent (SPEC: 30 s). */
    val heartbeat: Duration = 30.seconds,
    val dueLimit: Int = 200,
) {
    /** Delay after the [attempts]-th failure (1-based): 2 s, 4 s, 8 s, … capped at [backoffMax]. */
    fun backoff(attempts: Int): Duration {
        var d = backoffBase
        repeat((attempts - 1).coerceIn(0, MAX_DOUBLINGS)) { d = (d * 2).coerceAtMost(backoffMax) }
        return d.coerceAtMost(backoffMax)
    }

    private companion object {
        const val MAX_DOUBLINGS = 32
    }
}

/** What one pass did, for tests and logs. */
data class DrainResult(val uploaded: Int, val failed: Int)

/**
 * Uploads the Room queue to `trip_events` (SPEC cloud-sync): per trip in seq order, SOS first and on its own,
 * ordinary events in batches. The cloud ignores a (trip_id, seq) it already has, so sending twice is harmless and
 * an entry leaves the queue only after the server said yes. Failures back off per entry; a network failure ends
 * the pass, since the next request would fail the same way. An event the cloud refuses for good (a CHECK or limit
 * violation, a trip row owned by another user) is marked failed instead of retried forever; the local log keeps it.
 * The trip row goes up before its first events, and "ended" goes up only after the trip's last event: the contact
 * page and the AI summary treat it as final. Passes and status pushes never overlap.
 */
class UploadQueue(
    private val store: SyncStore,
    private val rest: RestApi,
    private val sessions: SessionManager,
    private val clock: Clock,
    private val config: SyncConfig = SyncConfig(),
    private val log: SyncLog = SyncLog { _, _ -> },
    private val newShareToken: () -> String = ShareToken::generate,
) {
    /** One at a time: a heartbeat's push can't land between a pass's events and its "ended". */
    private val lock = Mutex()

    /** Sends what is due; with [ignoreBackoff] (network just came back, app start) everything queued. */
    suspend fun drain(ignoreBackoff: Boolean = false): DrainResult = lock.withLock { drainLocked(ignoreBackoff) }

    private suspend fun drainLocked(ignoreBackoff: Boolean): DrainResult {
        val now = clock.now()
        val due = store.due(if (ignoreBackoff) Instant.DISTANT_FUTURE else now, config.dueLimit)
        var uploaded = 0
        var failed = 0
        val touched = linkedSetOf<String>()
        if (due.isNotEmpty()) {
            val pass = Pass()
            for (unit in units(due)) {
                val sent = send(unit, pass)
                uploaded += sent
                failed += unit.size - sent
                if (sent > 0) touched += unit.first().tripId
            }
            if (uploaded > 0 || failed > 0) log.log("upload pass: $uploaded sent, $failed to retry", null)
        }
        val toPush = touched + store.tripsToPush().map { it.id }
        toPush.forEach { pushTripLocked(it) }
        return DrainResult(uploaded, failed)
    }

    /**
     * Sends the trip's status, end and last-seen time if the trip is already in the cloud (the heartbeat and
     * status changes). False if it couldn't be sent now.
     */
    suspend fun pushTrip(tripId: String): Boolean = lock.withLock { pushTripLocked(tripId) }

    private suspend fun pushTripLocked(tripId: String): Boolean {
        val trip = store.trip(tripId)?.takeIf { it.created } ?: return false
        if (trip.status == ENDED && store.hasPending(trip.id)) return false
        return try {
            rest.patchTrip(sessions.accessToken(), trip)
            store.setPushedStatus(trip.id, trip.status)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (e is CloudError.Http && e.status == UNAUTHORIZED) sessions.invalidate()
            log.log("trip state not sent", e)
            false
        }
    }

    /**
     * True once the cloud has everything this car will send for [tripId]: no event left to go up (refused-for-good
     * ones don't count) and the trip's current status pushed. A trip this car no longer knows is settled.
     */
    suspend fun settled(tripId: String): Boolean {
        if (store.hasPending(tripId)) return false
        val trip = store.trip(tripId) ?: return true
        return trip.created && trip.pushedStatus == trip.status
    }

    /**
     * Waits until [settled] (true) or until [timeout] runs out (false), looking every [poll]. The loop in [run] does
     * the uploading; this only watches, so the caller (TripService after a trip ends) can keep the process alive.
     */
    suspend fun awaitSettled(tripId: String, timeout: Duration, poll: Duration = 1.seconds): Boolean =
        withTimeoutOrNull(timeout) {
            while (!settled(tripId)) delay(poll)
            true
        } ?: false

    /** How long the loop may sleep before something is due again. */
    suspend fun nextWait(): Duration {
        val next = store.nextAttemptAt() ?: return config.idleWake
        return (next - clock.now()).coerceIn(MIN_WAIT, config.idleWake)
    }

    /**
     * Drains on every [wakes] signal (new queue entry, SOS), whenever an entry falls due, and at least every
     * [SyncConfig.idleWake]; [drainAll] (network back) also skips the backoff. The first pass sends everything.
     */
    fun run(scope: CoroutineScope, wakes: Flow<Unit>, drainAll: Flow<Unit>): Job {
        val wake = Channel<Unit>(Channel.CONFLATED)
        val skipBackoff = AtomicBoolean(true)
        scope.launch { wakes.collect { wake.trySend(Unit) } }
        scope.launch {
            drainAll.collect {
                skipBackoff.set(true)
                wake.trySend(Unit)
            }
        }
        return scope.launch {
            while (isActive) {
                try {
                    drain(ignoreBackoff = skipBackoff.getAndSet(false))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.log("upload pass failed", e)
                }
                val wait = runCatching { nextWait() }.getOrDefault(config.idleWake)
                withTimeoutOrNull(wait) { wake.receive() }
            }
        }
    }

    private class Pass {
        var token: String? = null
        var stopped = false
    }

    /** SOS rows first, each alone; then the rest per trip in seq order, in batches. */
    private fun units(due: List<PendingUpload>): List<List<PendingUpload>> {
        val (urgent, normal) = due.partition { it.urgent }
        return urgent.sortedWith(ORDER).map { listOf(it) } +
            normal.sortedWith(ORDER).groupBy { it.tripId }.values.flatMap { it.chunked(config.batchSize) }
    }

    /** How many of the unit's events are in the cloud now; the rest are backed off. */
    private suspend fun send(unit: List<PendingUpload>, pass: Pass): Int {
        if (pass.stopped) {
            backoff(unit)
            return 0
        }
        val tripId = unit.first().tripId
        var rowInsertedNow = false
        try {
            val token = pass.token ?: sessions.accessToken().also { pass.token = it }
            rowInsertedNow = ensureTrip(tripId, token)
            rest.insertEvents(token, unit)
            store.remove(unit.map { it.queueId })
            return unit.size
        } catch (e: CancellationException) {
            throw e
        } catch (e: CloudError.Http) {
            when {
                e.status == UNAUTHORIZED -> {
                    sessions.invalidate()
                    pass.stopped = true
                }
                // Refused right after inserting the row: the row was already there under another user (the
                // session was replaced), so this car can never add to it.
                e.status == FORBIDDEN && rowInsertedNow -> return giveUp(unit, "trip row belongs to another user", e)
                // RLS refused the events: the trip row isn't in the cloud (or not ours). Insert it again next time.
                e.status == FORBIDDEN -> store.setCreated(tripId, false)
                // One bad row must not hold back the others: try them one by one.
                unit.size > 1 && isRowRefusal(e) -> return oneByOne(unit, pass)
                isRowRefusal(e) -> return giveUp(unit, "refused by the cloud", e)
            }
            log.log("upload refused for trip $tripId (${unit.size} events)", e)
        } catch (e: SerializationException) {
            // A stored payload that isn't JSON will never become JSON.
            if (unit.size > 1) return oneByOne(unit, pass)
            return giveUp(unit, "payload unreadable", e)
        } catch (e: TripRowRefused) {
            // The user behind the token is gone (e.g. database reset): get a new session, then retry.
            sessions.invalidate()
            pass.stopped = true
            log.log("trip row refused; renewing the session", e.cause)
        } catch (e: CloudError.Network) {
            pass.stopped = true
            log.log("offline; retrying later", e)
        } catch (e: Exception) {
            log.log("upload failed for trip $tripId", e)
        }
        backoff(unit)
        return 0
    }

    /** Sends each event of [unit] on its own, so the ones the cloud takes aren't held back by one it refuses. */
    private suspend fun oneByOne(unit: List<PendingUpload>, pass: Pass): Int = unit.sumOf { send(listOf(it), pass) }

    /** Puts the trip row in the cloud if it isn't known to be there; true if it was inserted by this call. */
    private suspend fun ensureTrip(tripId: String, token: String): Boolean {
        var trip = store.trip(tripId) ?: throw IllegalStateException("trip $tripId not stored")
        if (trip.created) return false
        if (trip.shareToken.isBlank()) {
            trip = trip.copy(shareToken = newShareToken())
            store.setShareToken(trip.id, trip.shareToken)
        }
        try {
            rest.insertTrip(token, trip)
        } catch (e: CloudError.Http) {
            // 409: the row's owner is gone (database reset). A 401 needs no case here: send() renews on any 401.
            if (e.status == CONFLICT) throw TripRowRefused(e)
            throw e
        }
        store.setCreated(trip.id, true)
        return true
    }

    /**
     * The server refused the rows themselves, so sending them again changes nothing: a client error other than
     * auth, RLS, timeout or rate limit, or the per-trip event limit (54000, which PostgREST answers with 500).
     */
    private fun isRowRefusal(e: CloudError.Http): Boolean =
        e.code == PROGRAM_LIMIT || (e.status in 400..499 && e.status !in TRANSIENT_4XX)

    /** Marks the unit failed for good; it counts as not sent. */
    private suspend fun giveUp(unit: List<PendingUpload>, why: String, e: Exception): Int {
        store.markFailed(unit.map { it.queueId }, clock.now())
        log.log("gave up on trip ${unit.first().tripId} seq ${unit.joinToString { "${it.seq} ${it.type}" }}: $why", e)
        return 0
    }

    private suspend fun backoff(unit: List<PendingUpload>) {
        val now = clock.now()
        unit.forEach { store.retryLater(it.queueId, now + config.backoff(it.attempts + 1)) }
    }

    private class TripRowRefused(cause: CloudError.Http) : Exception(cause)

    private companion object {
        const val UNAUTHORIZED = 401
        const val FORBIDDEN = 403
        const val CONFLICT = 409
        const val PROGRAM_LIMIT = "54000"
        const val ENDED = "ended"
        val TRANSIENT_4XX = setOf(UNAUTHORIZED, FORBIDDEN, 408, 429)
        val MIN_WAIT = 1.seconds
        val ORDER = compareBy<PendingUpload>({ it.tripId }, { it.seq })
    }
}
