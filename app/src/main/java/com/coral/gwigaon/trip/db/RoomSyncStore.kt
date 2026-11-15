package com.coral.gwigaon.trip.db

import com.coral.gwigaon.sync.CloudSession
import com.coral.gwigaon.sync.CloudTrip
import com.coral.gwigaon.sync.PendingUpload
import com.coral.gwigaon.sync.SessionStore
import com.coral.gwigaon.sync.SyncStore
import kotlin.time.Instant

/** [SyncStore] and [SessionStore] on Room. Column mapping only; the policy is in UploadQueue / SessionManager. */
class RoomSyncStore(private val db: GwigaonDatabase) : SyncStore, SessionStore {
    override suspend fun due(now: Instant, limit: Int): List<PendingUpload> =
        db.uploadQueue().due(now.toEpochMilliseconds(), limit).map { q ->
            val e = q.event
            PendingUpload(
                queueId = q.queueId, urgent = q.urgent, attempts = q.attempts, tripId = e.tripId, seq = e.seq,
                type = e.type, occurredAt = Instant.fromEpochMilliseconds(e.occurredAt), lat = e.lat, lng = e.lng,
                reason = e.reason, payload = e.payload,
            )
        }

    override suspend fun nextAttemptAt(): Instant? = db.uploadQueue().nextAttemptAt()?.let(Instant::fromEpochMilliseconds)

    override suspend fun remove(queueIds: List<Long>) = db.uploadQueue().remove(queueIds)

    override suspend fun markFailed(queueIds: List<Long>, at: Instant) =
        db.uploadQueue().markFailed(queueIds, at.toEpochMilliseconds())

    override suspend fun hasPending(tripId: String): Boolean = db.uploadQueue().hasPending(tripId)

    override suspend fun retryLater(queueId: Long, at: Instant) = db.uploadQueue().retryLater(queueId, at.toEpochMilliseconds())

    override suspend fun trip(id: String): CloudTrip? = db.trips().byId(id)?.toCloud()

    override suspend fun tripsToPush(): List<CloudTrip> = db.trips().cloudStatusBehind().map { it.toCloud() }

    override suspend fun setCreated(id: String, created: Boolean) = db.trips().setCloudCreated(id, created)

    override suspend fun setPushedStatus(id: String, status: String) = db.trips().setCloudStatus(id, status)

    override suspend fun setShareToken(id: String, token: String) = db.trips().setShareToken(id, token)

    override suspend fun load(): CloudSession? = db.cloudSession().get()?.let {
        CloudSession(it.accessToken, it.refreshToken, Instant.fromEpochMilliseconds(it.expiresAt))
    }

    override suspend fun save(session: CloudSession?) {
        if (session == null) {
            db.cloudSession().clear()
        } else {
            db.cloudSession().put(CloudSessionEntity(accessToken = session.accessToken, refreshToken = session.refreshToken, expiresAt = session.expiresAt.toEpochMilliseconds()))
        }
    }

    private fun TripEntity.toCloud() = CloudTrip(
        id = id, shareToken = shareToken, status = status, destinationLabel = destinationLabel, contactLabel = contactLabel,
        startedAt = Instant.fromEpochMilliseconds(startedAt), endedAt = endedAt?.let(Instant::fromEpochMilliseconds),
        lastSeenAt = Instant.fromEpochMilliseconds(lastSeenAt), created = cloudCreated, pushedStatus = cloudStatus,
    )
}
