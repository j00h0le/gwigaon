package com.coral.gwigaon.trip

import com.coral.gwigaon.vehicle.Destination
import kotlin.time.Instant

/**
 * What the passenger tells the driver about the drop-off (SPEC arrival): parking floor/area, gate or entrance,
 * and where the key goes. Empty strings mean "not given". Spoken and shown near the destination (T13).
 */
data class ArrivalInstructions(
    val parking: String = "",
    val entrance: String = "",
    val keyNote: String = "",
    /** The polite sentence Claude wrote for exactly these notes (ADR 0021); empty → the car's template is spoken. */
    val spoken: String = "",
) {
    val isEmpty: Boolean get() = parking.isBlank() && entrance.isBlank() && keyNote.isBlank()

    /** Same three notes, whatever the stored sentence. */
    fun sameNotes(other: ArrivalInstructions): Boolean =
        parking == other.parking && entrance == other.entrance && keyNote == other.keyNote

    /** The key note can be a door code; only say which fields are filled. */
    override fun toString(): String =
        "ArrivalInstructions(parking=${parking.isNotBlank()}, entrance=${entrance.isNotBlank()}, " +
            "keyNote=${keyNote.isNotBlank()}, spoken=${spoken.isNotBlank()})"
}

/** A trip as stored on this car (cloud `trips` columns plus local-only PIN data). */
data class StoredTrip(
    val id: String,
    val status: TripStatus,
    val destination: Destination,
    val contactLabel: String,
    val pinHash: String,
    val pinAttempts: PinAttempts,
    val startedAt: Instant,
    val endedAt: Instant?,
    /** Last moment monitoring was known to be running; a restart's gap is measured from here. */
    val lastSeenAt: Instant,
    val arrival: ArrivalInstructions = ArrivalInstructions(),
    /** The contact's link key ([com.coral.gwigaon.sync.ShareToken]); empty only on trips from before T16. */
    val shareToken: String = "",
) {
    /** PIN으로 운행 종료 was on when the trip started (ADR 0015); an empty hash means no PIN. */
    val pinOn: Boolean get() = pinHash.isNotEmpty()

    /** Never prints the PIN hash, the contact label or the share token, so logging a trip can't leak them. */
    override fun toString(): String =
        "StoredTrip(id=$id, status=${status.wire}, startedAt=$startedAt, endedAt=$endedAt, lastSeenAt=$lastSeenAt, " +
            "pinFailures=${pinAttempts.failures}, pinLocked=${pinAttempts.lockedUntil != null})"
}

/** Persistence for [TripSessionRepository]; Room in the app, a fake in unit tests. */
interface TripStore {
    suspend fun insert(trip: StoredTrip)

    /** The trip that isn't ended yet, if any. */
    suspend fun active(): StoredTrip?

    /** The most recently started trip that has ended, if any (home's 운행 기록, ADR 0017 amendment). */
    suspend fun latestEnded(): StoredTrip?

    /** The trip's log in seq order. Fails if a stored row can't be read back. */
    suspend fun events(tripId: String): Result<List<EventRecord>>

    /** Appends as the next seq (and queues it for upload). */
    suspend fun append(tripId: String, at: Instant, lat: Double?, lng: Double?, event: TripEvent): EventRecord

    suspend fun setStatus(tripId: String, status: TripStatus, endedAt: Instant?)

    suspend fun touch(tripId: String, at: Instant)

    suspend fun setPinAttempts(tripId: String, attempts: PinAttempts)

    /** Destinations of past trips, most recent first, each place once. */
    suspend fun recentDestinations(limit: Int): List<Destination>
}
