package com.coral.gwigaon.trip.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.coral.gwigaon.trip.EventRecord
import com.coral.gwigaon.trip.PinAttempts
import com.coral.gwigaon.trip.TripEvent
import com.coral.gwigaon.trip.TripEventJson
import kotlinx.coroutines.flow.Flow
import kotlin.time.Instant

@Dao
interface TripDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(trip: TripEntity)

    @Query("SELECT * FROM trips WHERE id = :id")
    suspend fun byId(id: String): TripEntity?

    /** The trip to resume after a restart, if any (at most one is ever not ended). */
    @Query("SELECT * FROM trips WHERE status != 'ended' ORDER BY started_at DESC LIMIT 1")
    suspend fun active(): TripEntity?

    @Query("SELECT * FROM trips WHERE status = 'ended' ORDER BY started_at DESC LIMIT 1")
    suspend fun latestEnded(): TripEntity?

    @Query("SELECT * FROM trips WHERE status != 'ended' ORDER BY started_at DESC LIMIT 1")
    fun observeActive(): Flow<TripEntity?>

    @Query(
        """
        SELECT destination_label, destination_lat, destination_lng FROM trips
        GROUP BY destination_label, destination_lat, destination_lng
        ORDER BY MAX(started_at) DESC
        LIMIT :limit
        """,
    )
    suspend fun recentDestinations(limit: Int): List<RecentDestination>

    @Query("UPDATE trips SET status = :status, ended_at = :endedAt WHERE id = :id")
    suspend fun setStatus(id: String, status: String, endedAt: Long? = null)

    @Query("UPDATE trips SET last_seen_at = :at WHERE id = :id")
    suspend fun touch(id: String, at: Long)

    @Query("SELECT * FROM trips WHERE cloud_created = 1 AND (cloud_status IS NULL OR cloud_status != status)")
    suspend fun cloudStatusBehind(): List<TripEntity>

    @Query("UPDATE trips SET cloud_created = :created WHERE id = :id")
    suspend fun setCloudCreated(id: String, created: Boolean)

    @Query("UPDATE trips SET cloud_status = :status WHERE id = :id")
    suspend fun setCloudStatus(id: String, status: String)

    @Query("UPDATE trips SET share_token = :token WHERE id = :id")
    suspend fun setShareToken(id: String, token: String)

    @Query("UPDATE trips SET pin_failures = :failures, pin_locked_until = :lockedUntil WHERE id = :id")
    suspend fun setPinAttemptsRaw(id: String, failures: Int, lockedUntil: Long?)

    suspend fun setPinAttempts(id: String, attempts: PinAttempts) =
        setPinAttemptsRaw(id, attempts.failures, attempts.lockedUntil?.toEpochMilliseconds())
}

/**
 * Append and read only: there is deliberately no update or delete here, and the database refuses them anyway.
 * Appending also queues the event for upload, in one transaction, so nothing is logged without being queued.
 */
@Dao
abstract class EventDao {
    @Query("SELECT COALESCE(MAX(seq), 0) FROM events WHERE trip_id = :tripId")
    protected abstract suspend fun lastSeq(tripId: String): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertEvent(event: EventEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertQueued(entry: UploadQueueEntity)

    /** Logs [event] as the trip's next seq (1, 2, 3, …) and returns the stored record. */
    @Transaction
    open suspend fun append(tripId: String, at: Instant, lat: Double?, lng: Double?, event: TripEvent): EventRecord {
        val seq = lastSeq(tripId) + 1
        val atMs = at.toEpochMilliseconds()
        insertEvent(EventEntity(tripId, seq, event.type.wire, atMs, lat, lng, event.reason, TripEventJson.payloadString(event)))
        insertQueued(UploadQueueEntity(tripId = tripId, seq = seq, urgent = event is TripEvent.Sos, nextAttemptAt = atMs))
        return EventRecord(seq, Instant.fromEpochMilliseconds(atMs), lat, lng, event)
    }

    @Query("SELECT * FROM events WHERE trip_id = :tripId ORDER BY seq")
    abstract suspend fun forTrip(tripId: String): List<EventEntity>

    @Query("SELECT * FROM events WHERE trip_id = :tripId ORDER BY seq")
    abstract fun observe(tripId: String): Flow<List<EventEntity>>
}

/** Upload bookkeeping only; the retry policy lives in [com.coral.gwigaon.sync.UploadQueue]. */
@Dao
interface UploadQueueDao {
    /** Due entries in log order, urgent (SOS) first. */
    @Query(
        """
        SELECT q.id AS queue_id, q.urgent, q.attempts, e.*
        FROM upload_queue q JOIN events e ON e.trip_id = q.trip_id AND e.seq = q.seq
        WHERE q.next_attempt_at <= :now AND q.failed_at IS NULL
        ORDER BY q.urgent DESC, q.trip_id, q.seq
        LIMIT :limit
        """,
    )
    suspend fun due(now: Long, limit: Int): List<QueuedEvent>

    @Query("UPDATE upload_queue SET attempts = attempts + 1, next_attempt_at = :nextAttemptAt WHERE id = :id")
    suspend fun retryLater(id: Long, nextAttemptAt: Long)

    @Query("DELETE FROM upload_queue WHERE id IN (:ids)")
    suspend fun remove(ids: List<Long>)

    @Query("UPDATE upload_queue SET failed_at = :at WHERE id IN (:ids)")
    suspend fun markFailed(ids: List<Long>, at: Long)

    @Query("SELECT EXISTS(SELECT 1 FROM upload_queue WHERE trip_id = :tripId AND failed_at IS NULL)")
    suspend fun hasPending(tripId: String): Boolean

    @Query("SELECT COUNT(*) FROM upload_queue WHERE failed_at IS NULL")
    fun observePending(): Flow<Int>

    @Query("SELECT MIN(next_attempt_at) FROM upload_queue WHERE failed_at IS NULL")
    suspend fun nextAttemptAt(): Long?
}

@Dao
interface CloudSessionDao {
    @Query("SELECT * FROM cloud_session WHERE id = 1")
    suspend fun get(): CloudSessionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(session: CloudSessionEntity)

    @Query("DELETE FROM cloud_session")
    suspend fun clear()
}

@Dao
interface SettingsDao {
    @Query("SELECT * FROM settings WHERE id = :id")
    suspend fun get(id: Int): SettingsEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(settings: SettingsEntity)

    @Query("UPDATE settings SET pin_failures = :failures, pin_locked_until = :lockedUntil WHERE id = :id")
    suspend fun setPinAttempts(id: Int, failures: Int, lockedUntil: Long?)
}

@Dao
interface OwnerLinkDao {
    @Query("SELECT * FROM owner_link WHERE id = 1")
    suspend fun get(): OwnerLinkEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(link: OwnerLinkEntity)
}
