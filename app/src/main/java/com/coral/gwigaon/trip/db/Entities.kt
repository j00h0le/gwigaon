package com.coral.gwigaon.trip.db

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.coral.gwigaon.trip.EventRecord
import com.coral.gwigaon.trip.PinAttempts
import com.coral.gwigaon.trip.TripEventJson
import kotlin.time.Instant

/**
 * One trip on this car. Column names follow the cloud `trips` table where they overlap; PIN and lockout
 * stay local only. Times are epoch milliseconds.
 */
@Entity(tableName = "trips", indices = [Index("status")])
data class TripEntity(
    @PrimaryKey val id: String,
    /** [com.coral.gwigaon.trip.TripStatus.wire]. */
    val status: String,
    @ColumnInfo(name = "destination_label") val destinationLabel: String,
    @ColumnInfo(name = "destination_lat") val destinationLat: Double,
    @ColumnInfo(name = "destination_lng") val destinationLng: Double,
    @ColumnInfo(name = "contact_label") val contactLabel: String,
    /** [com.coral.gwigaon.trip.PinHasher] stored form; never the PIN. */
    @ColumnInfo(name = "pin_hash") val pinHash: String,
    @ColumnInfo(name = "pin_failures") val pinFailures: Int = 0,
    @ColumnInfo(name = "pin_locked_until") val pinLockedUntil: Long? = null,
    @ColumnInfo(name = "started_at") val startedAt: Long,
    @ColumnInfo(name = "ended_at") val endedAt: Long? = null,
    /** Last time monitoring was known alive; the resume gap is measured from here (T8). */
    @ColumnInfo(name = "last_seen_at") val lastSeenAt: Long,
    /** Arrival instructions (added in schema 2); empty when not given. */
    @ColumnInfo(name = "arrival_parking", defaultValue = "") val arrivalParking: String = "",
    @ColumnInfo(name = "arrival_entrance", defaultValue = "") val arrivalEntrance: String = "",
    @ColumnInfo(name = "arrival_key_note", defaultValue = "") val arrivalKeyNote: String = "",
    /** Claude's spoken sentence for these notes (schema 7, ADR 0021); empty → template. */
    @ColumnInfo(name = "arrival_spoken", defaultValue = "") val arrivalSpoken: String = "",
    /** The contact's link key (schema 3); empty on trips from before T16, which get one at first upload. */
    @ColumnInfo(name = "share_token", defaultValue = "") val shareToken: String = "",
    /** The cloud has this trip's row (schema 3). */
    @ColumnInfo(name = "cloud_created", defaultValue = "0") val cloudCreated: Boolean = false,
    /** The last status sent to the cloud (schema 3); null before the first. */
    @ColumnInfo(name = "cloud_status") val cloudStatus: String? = null,
) {
    val pinAttempts: PinAttempts
        get() = PinAttempts(pinFailures, pinLockedUntil?.let(Instant::fromEpochMilliseconds))

    /** Like [com.coral.gwigaon.trip.StoredTrip]: no PIN hash, contact label or arrival notes in logs. */
    override fun toString(): String =
        "TripEntity(id=$id, status=$status, startedAt=$startedAt, endedAt=$endedAt, pinFailures=$pinFailures)"
}

/**
 * The append-only event log: one `trip_events` row without the cloud id. [seq] starts at 1 per trip and is
 * assigned only by [EventDao.append]; triggers in [GwigaonDatabase] refuse UPDATE and DELETE.
 */
@Entity(
    tableName = "events",
    primaryKeys = ["trip_id", "seq"],
    foreignKeys = [ForeignKey(entity = TripEntity::class, parentColumns = ["id"], childColumns = ["trip_id"])],
)
data class EventEntity(
    @ColumnInfo(name = "trip_id") val tripId: String,
    val seq: Int,
    val type: String,
    @ColumnInfo(name = "occurred_at") val occurredAt: Long,
    val lat: Double?,
    val lng: Double?,
    val reason: String?,
    /** JSON object with snake_case keys, as uploaded. */
    val payload: String,
) {
    fun toRecord(): Result<EventRecord> = TripEventJson.eventFromColumns(type, reason, payload).map {
        EventRecord(seq, Instant.fromEpochMilliseconds(occurredAt), lat, lng, it)
    }
}

/** One row of the recents query. */
data class RecentDestination(
    @ColumnInfo(name = "destination_label") val label: String,
    @ColumnInfo(name = "destination_lat") val lat: Double,
    @ColumnInfo(name = "destination_lng") val lng: Double,
)

/** Events still to upload (T16). Written in the same transaction as the event, removed once the cloud has it. */
@Entity(
    tableName = "upload_queue",
    indices = [Index(value = ["trip_id", "seq"], unique = true)],
    foreignKeys = [
        ForeignKey(
            entity = EventEntity::class,
            parentColumns = ["trip_id", "seq"],
            childColumns = ["trip_id", "seq"],
        ),
    ],
)
data class UploadQueueEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "trip_id") val tripId: String,
    val seq: Int,
    /** SOS: upload now, skip batching (SPEC sos). */
    val urgent: Boolean,
    val attempts: Int = 0,
    @ColumnInfo(name = "next_attempt_at") val nextAttemptAt: Long,
    /** Set when the cloud refused the event for good: kept on record, never sent again. The local log keeps the event. */
    @ColumnInfo(name = "failed_at") val failedAt: Long? = null,
)

/** A queue entry with the event it carries. */
data class QueuedEvent(
    @ColumnInfo(name = "queue_id") val queueId: Long,
    val urgent: Boolean,
    val attempts: Int,
    @Embedded val event: EventEntity,
)

/**
 * The car's anonymous Supabase session (schema 3), one row ([id] = [SINGLE]). App-private and not backed up, like
 * the rest of this database.
 */
@Entity(tableName = "cloud_session")
data class CloudSessionEntity(
    @PrimaryKey val id: Int = SINGLE,
    @ColumnInfo(name = "access_token") val accessToken: String,
    @ColumnInfo(name = "refresh_token") val refreshToken: String,
    @ColumnInfo(name = "expires_at") val expiresAt: Long,
) {
    override fun toString(): String = "CloudSessionEntity(expiresAt=$expiresAt)"

    companion object {
        const val SINGLE = 1
    }
}

/**
 * 설정 (schema 5, ADR 0015). Row [CURRENT] is what is saved; row [AT_LAST_TRIP] is a copy taken at the last trip
 * start, the baseline for `SETTINGS_CHANGED`. The PIN lockout counters on [CURRENT] guard opening 설정.
 * Times are epoch milliseconds.
 */
@Entity(tableName = "settings")
data class SettingsEntity(
    @PrimaryKey val id: Int,
    @ColumnInfo(name = "home_label") val homeLabel: String?,
    @ColumnInfo(name = "home_lat") val homeLat: Double?,
    @ColumnInfo(name = "home_lng") val homeLng: Double?,
    /** JSON array of 보호자 labels, in the order shown. */
    val contacts: String,
    @ColumnInfo(name = "arrival_parking") val arrivalParking: String,
    @ColumnInfo(name = "arrival_entrance") val arrivalEntrance: String,
    @ColumnInfo(name = "arrival_key_note") val arrivalKeyNote: String,
    /** Claude's spoken sentence for the notes above (schema 7, ADR 0021); empty → template. */
    @ColumnInfo(name = "arrival_spoken", defaultValue = "") val arrivalSpoken: String = "",
    val cues: Boolean,
    /** [com.coral.gwigaon.trip.PinHasher] stored form, null when the PIN is off; never the PIN. */
    @ColumnInfo(name = "pin_hash") val pinHash: String?,
    @ColumnInfo(name = "pin_failures", defaultValue = "0") val pinFailures: Int = 0,
    @ColumnInfo(name = "pin_locked_until") val pinLockedUntil: Long? = null,
    /** The 비상 연락처 (QR) step was shown (ADR 0019, schema 6). */
    @ColumnInfo(name = "link_shown", defaultValue = "0") val linkShown: Boolean = false,
) {
    override fun toString(): String = "SettingsEntity(id=$id, pinOn=${pinHash != null}, pinFailures=$pinFailures)"

    companion object {
        const val CURRENT = 1
        const val AT_LAST_TRIP = 2
    }
}

/** The account's standing contact link (ADR 0019); one row, id 1. The token is a bearer secret: not in logs. */
@Entity(tableName = "owner_link")
data class OwnerLinkEntity(
    @PrimaryKey val id: Int = ID,
    val token: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    val synced: Boolean,
) {
    override fun toString(): String = "OwnerLinkEntity(createdAt=$createdAt, synced=$synced)"

    companion object {
        const val ID = 1
    }
}
