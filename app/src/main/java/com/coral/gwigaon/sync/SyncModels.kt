package com.coral.gwigaon.sync

import kotlin.time.Instant

/** One queued event with its row, as [SyncStore.due] returns it. [payload] is the stored JSON object text. */
data class PendingUpload(
    val queueId: Long,
    val urgent: Boolean,
    val attempts: Int,
    val tripId: String,
    val seq: Int,
    val type: String,
    val occurredAt: Instant,
    val lat: Double?,
    val lng: Double?,
    val reason: String?,
    val payload: String,
) {
    /** Reasons can name places; logs only get the row's identity. */
    override fun toString(): String = "PendingUpload(queueId=$queueId, trip=$tripId, seq=$seq, type=$type, urgent=$urgent)"
}

/**
 * The cloud `trips` row as this car knows it, plus what has been sent: [created] once the row exists in the cloud,
 * [pushedStatus] the last status sent (null: none yet).
 */
data class CloudTrip(
    val id: String,
    val shareToken: String,
    val status: String,
    val destinationLabel: String,
    val contactLabel: String,
    val startedAt: Instant,
    val endedAt: Instant?,
    val lastSeenAt: Instant,
    val created: Boolean,
    val pushedStatus: String?,
) {
    /** The share token is the contact's key to the trip and the labels are typed by people: not for logs. */
    override fun toString(): String = "CloudTrip(id=$id, status=$status, created=$created, pushedStatus=$pushedStatus)"
}

/** An anonymous Supabase Auth session (SPEC cloud-sync: the app signs in anonymously). */
data class CloudSession(val accessToken: String, val refreshToken: String, val expiresAt: Instant) {
    override fun toString(): String = "CloudSession(expiresAt=$expiresAt)"
}

/** Why a cloud call failed. Messages never carry tokens or request bodies. */
sealed class CloudError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** No answer: offline, DNS, timeout, connection reset. Worth retrying. */
    class Network(cause: Throwable) : CloudError("network: ${cause.javaClass.simpleName}", cause)

    /** The server answered with [status]; [code] is the Postgres/PostgREST/Auth error code when it gave one. */
    class Http(val status: Int, val code: String?) : CloudError("http $status${code?.let { " ($it)" } ?: ""}")
}

/** Upload bookkeeping over the local database (Room in the app, a fake in unit tests). */
interface SyncStore {
    /** Entries due at [now] in log order, urgent (SOS) first. */
    suspend fun due(now: Instant, limit: Int): List<PendingUpload>

    /** When the earliest entry is due, or null if the queue is empty. */
    suspend fun nextAttemptAt(): Instant?

    /** The cloud has these entries' events: drop them from the queue. */
    suspend fun remove(queueIds: List<Long>)

    /** The cloud refused these entries for good: keep them on record but never send them again. */
    suspend fun markFailed(queueIds: List<Long>, at: Instant)

    /** Whether the trip still has events waiting to go up (refused-for-good ones don't count). */
    suspend fun hasPending(tripId: String): Boolean

    /** Counts a failed attempt and sets when to try again. */
    suspend fun retryLater(queueId: Long, at: Instant)

    suspend fun trip(id: String): CloudTrip?

    /** Trips already in the cloud whose current status hasn't been sent yet. */
    suspend fun tripsToPush(): List<CloudTrip>

    suspend fun setCreated(id: String, created: Boolean)

    suspend fun setPushedStatus(id: String, status: String)

    /** Trips logged before share tokens existed get one when they are first uploaded. */
    suspend fun setShareToken(id: String, token: String)
}

/** Where the anonymous session is kept between runs, so the car stays the owner of its trips. */
interface SessionStore {
    suspend fun load(): CloudSession?

    suspend fun save(session: CloudSession?)
}

/** Supabase Auth: anonymous sign-up and refresh. Both throw [CloudError]. */
interface AuthApi {
    suspend fun signUpAnonymously(): CloudSession

    suspend fun refresh(refreshToken: String): CloudSession
}

/** The PostgREST calls the app makes, as the signed-in anonymous user. All throw [CloudError]. */
interface RestApi {
    /** Inserts the trip row; a row with the same id already there is left as it is. */
    suspend fun insertTrip(accessToken: String, trip: CloudTrip)

    /** Inserts events in one request; rows with a (trip_id, seq) already there are skipped (idempotent). */
    suspend fun insertEvents(accessToken: String, rows: List<PendingUpload>)

    /** Updates the trip's status, end time and last-seen time. */
    suspend fun patchTrip(accessToken: String, trip: CloudTrip)
}

/**
 * The `summarize-trip` answer (SPEC journey-report): an AI interpretation grounded in the trip's events. Every
 * [NotableEvent.seq] and [summarySeqs] entry names a logged event; the function rejects anything else.
 */
data class TripSummary(
    val summary: String,
    val summarySeqs: List<Int>,
    val notableEvents: List<NotableEvent>,
    val caveats: List<String>,
    val model: String,
)

data class NotableEvent(val seq: Int, val text: String)

/** The Edge Function that writes the journey report's AI part. Throws [CloudError]. */
fun interface SummaryApi {
    suspend fun summarizeTrip(accessToken: String, tripId: String): TripSummary
}

/** The `phrase-arrival` Edge Function (ADR 0021): the notes in, one polite spoken sentence out. */
fun interface PhraseApi {
    suspend fun phraseArrival(accessToken: String, entrance: String, parking: String, key: String): String
}

/** Where cloud sync reports what happened; the app writes it to logcat, tests stay silent. */
fun interface SyncLog {
    fun log(message: String, error: Throwable?)
}
