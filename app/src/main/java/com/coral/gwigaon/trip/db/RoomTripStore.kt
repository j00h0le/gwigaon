package com.coral.gwigaon.trip.db

import com.coral.gwigaon.trip.ArrivalInstructions
import com.coral.gwigaon.trip.EventRecord
import com.coral.gwigaon.trip.PinAttempts
import com.coral.gwigaon.trip.StoredTrip
import com.coral.gwigaon.trip.TripEvent
import com.coral.gwigaon.trip.TripStatus
import com.coral.gwigaon.trip.TripStore
import com.coral.gwigaon.vehicle.Destination
import kotlin.time.Instant

/** [TripStore] on Room. Only column mapping lives here; behaviour is in TripSessionRepository. */
class RoomTripStore(private val db: GwigaonDatabase) : TripStore {
    override suspend fun insert(trip: StoredTrip) = db.trips().insert(
        TripEntity(
            id = trip.id,
            status = trip.status.wire,
            destinationLabel = trip.destination.name,
            destinationLat = trip.destination.lat,
            destinationLng = trip.destination.lng,
            contactLabel = trip.contactLabel,
            pinHash = trip.pinHash,
            pinFailures = trip.pinAttempts.failures,
            pinLockedUntil = trip.pinAttempts.lockedUntil?.toEpochMilliseconds(),
            startedAt = trip.startedAt.toEpochMilliseconds(),
            endedAt = trip.endedAt?.toEpochMilliseconds(),
            lastSeenAt = trip.lastSeenAt.toEpochMilliseconds(),
            arrivalParking = trip.arrival.parking,
            arrivalEntrance = trip.arrival.entrance,
            arrivalKeyNote = trip.arrival.keyNote,
            arrivalSpoken = trip.arrival.spoken,
            shareToken = trip.shareToken,
        ),
    )

    override suspend fun active(): StoredTrip? = db.trips().active()?.let(::stored)

    override suspend fun latestEnded(): StoredTrip? = db.trips().latestEnded()?.let(::stored)

    private fun stored(e: TripEntity): StoredTrip =
        StoredTrip(
            id = e.id,
            status = TripStatus.entries.firstOrNull { it.wire == e.status } ?: TripStatus.ACTIVE,
            destination = Destination(e.destinationLabel, e.destinationLat, e.destinationLng),
            contactLabel = e.contactLabel,
            pinHash = e.pinHash,
            pinAttempts = e.pinAttempts,
            startedAt = Instant.fromEpochMilliseconds(e.startedAt),
            endedAt = e.endedAt?.let(Instant::fromEpochMilliseconds),
            lastSeenAt = Instant.fromEpochMilliseconds(e.lastSeenAt),
            arrival = ArrivalInstructions(e.arrivalParking, e.arrivalEntrance, e.arrivalKeyNote, e.arrivalSpoken),
            shareToken = e.shareToken,
        )

    override suspend fun events(tripId: String): Result<List<EventRecord>> = runCatching {
        db.events().forTrip(tripId).map { it.toRecord().getOrThrow() }
    }

    override suspend fun append(tripId: String, at: Instant, lat: Double?, lng: Double?, event: TripEvent) =
        db.events().append(tripId, at, lat, lng, event)

    override suspend fun setStatus(tripId: String, status: TripStatus, endedAt: Instant?) =
        db.trips().setStatus(tripId, status.wire, endedAt?.toEpochMilliseconds())

    override suspend fun touch(tripId: String, at: Instant) = db.trips().touch(tripId, at.toEpochMilliseconds())

    override suspend fun setPinAttempts(tripId: String, attempts: PinAttempts) = db.trips().setPinAttempts(tripId, attempts)

    override suspend fun recentDestinations(limit: Int): List<Destination> =
        db.trips().recentDestinations(limit).map { Destination(it.label, it.lat, it.lng) }
}
