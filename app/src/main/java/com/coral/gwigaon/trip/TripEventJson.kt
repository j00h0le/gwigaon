package com.coral.gwigaon.trip

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.Instant

/**
 * The event wire format: the `trip_events` row shape
 * `{seq, type, occurred_at, lat, lng, reason, payload}` with snake_case payload keys
 * (same as supabase/seed.sql and web/contact). Hand-mapped so wire names never follow a Kotlin rename.
 */
object TripEventJson {
    fun encode(record: EventRecord): String = toJson(record).toString()

    fun toJson(record: EventRecord): JsonObject = buildJsonObject {
        put("seq", JsonPrimitive(record.seq))
        put("type", JsonPrimitive(record.event.type.wire))
        put("occurred_at", JsonPrimitive(record.occurredAt.toString()))
        put("lat", JsonPrimitive(record.lat))
        put("lng", JsonPrimitive(record.lng))
        put("reason", JsonPrimitive(record.event.reason))
        put("payload", JsonObject(payloadOf(record.event)))
    }

    fun decode(json: String): Result<EventRecord> = runCatching {
        val obj = Json.parseToJsonElement(json).jsonObject
        val type = obj.string("type").let { wire ->
            EventType.entries.firstOrNull { it.wire == wire } ?: throw IllegalArgumentException("unknown event type $wire")
        }
        val reason = obj["reason"].stringOrNull()
        val payload = obj["payload"]?.takeUnless { it is JsonNull }?.jsonObject ?: JsonObject(emptyMap())
        EventRecord(
            seq = obj.getValue("seq").jsonPrimitive.int,
            occurredAt = Instant.parse(obj.string("occurred_at")),
            lat = obj["lat"].doubleOrNull(),
            lng = obj["lng"].doubleOrNull(),
            event = eventOf(type, reason, payload),
        )
    }

    /** The `payload` column as stored locally (same JSON the cloud row gets). */
    fun payloadString(event: TripEvent): String = JsonObject(payloadOf(event)).toString()

    /** Rebuilds an event from its stored columns (type wire name, reason, payload JSON). */
    fun eventFromColumns(typeWire: String, reason: String?, payload: String): Result<TripEvent> = runCatching {
        val type = EventType.entries.firstOrNull { it.wire == typeWire }
            ?: throw IllegalArgumentException("unknown event type $typeWire")
        eventOf(type, reason, Json.parseToJsonElement(payload).jsonObject)
    }

    private fun payloadOf(e: TripEvent): Map<String, JsonElement> = when (e) {
        is TripEvent.RouteDeviation -> mapOf("distance_m" to JsonPrimitive(e.distanceM))
        is TripEvent.LongStop -> mapOf("duration_s" to JsonPrimitive(e.durationS))
        is TripEvent.MonitoringInterrupted -> mapOf("gap_s" to JsonPrimitive(e.gapS))
        is TripEvent.Sos -> mapOf("source" to JsonPrimitive(e.source.wire))
        is TripEvent.SafeArrivalConfirmed -> mapOf("closed_by" to JsonPrimitive(e.closedBy.wire))
        is TripEvent.CheckInPrompted -> mapOf("level" to JsonPrimitive(e.level), "cue" to JsonPrimitive(e.cue.wire))
        is TripEvent.CheckInAnswered -> mapOf("answer" to JsonPrimitive(e.answer.wire))
        is TripEvent.TripEnded -> mapOf("reason" to JsonPrimitive(e.endReason.wire))
        is TripEvent.PinLockout -> mapOf("failures" to JsonPrimitive(e.failures), "locked_s" to JsonPrimitive(e.lockedS))
        is TripEvent.TripStarted -> e.source?.let { mapOf("source" to JsonPrimitive(it.wire)) } ?: emptyMap()
        is TripEvent.SettingsChanged -> mapOf("fields" to JsonArray(e.fields.map { JsonPrimitive(it.wire) }))
        is TripEvent.DeviationCleared, is TripEvent.LongStopCleared,
        is TripEvent.SosCancelled, is TripEvent.MonitoringResumed, is TripEvent.NearDestination,
        is TripEvent.Arrived, is TripEvent.SafeArrivalCheckOpened -> emptyMap()
    }

    private fun eventOf(type: EventType, reason: String?, p: JsonObject): TripEvent = when (type) {
        EventType.TRIP_STARTED ->
            TripEvent.TripStarted(p["source"]?.let { p.wire("source", StartSource.entries) { it.wire } }, reason)
        EventType.ROUTE_DEVIATION -> TripEvent.RouteDeviation(p.int("distance_m"), reason)
        EventType.DEVIATION_CLEARED -> TripEvent.DeviationCleared(reason)
        EventType.LONG_STOP -> TripEvent.LongStop(p.int("duration_s"), reason)
        EventType.LONG_STOP_CLEARED -> TripEvent.LongStopCleared(reason)
        EventType.SOS -> TripEvent.Sos(p.wire("source", SosSource.entries) { it.wire }, reason)
        EventType.SOS_CANCELLED -> TripEvent.SosCancelled(reason)
        EventType.MONITORING_INTERRUPTED -> TripEvent.MonitoringInterrupted(p.int("gap_s"), reason)
        EventType.MONITORING_RESUMED -> TripEvent.MonitoringResumed(reason)
        EventType.NEAR_DESTINATION -> TripEvent.NearDestination(reason)
        EventType.ARRIVED -> TripEvent.Arrived(reason)
        EventType.SAFE_ARRIVAL_CHECK_OPENED -> TripEvent.SafeArrivalCheckOpened(reason)
        EventType.SAFE_ARRIVAL_CONFIRMED ->
            TripEvent.SafeArrivalConfirmed(p.wire("closed_by", SafeArrivalCloser.entries) { it.wire }, reason)
        EventType.CHECK_IN_PROMPTED ->
            TripEvent.CheckInPrompted(p.int("level"), p.wire("cue", CueKind.entries) { it.wire }, reason)
        EventType.CHECK_IN_ANSWERED -> TripEvent.CheckInAnswered(p.wire("answer", CheckInAnswer.entries) { it.wire }, reason)
        EventType.TRIP_ENDED -> TripEvent.TripEnded(p.wire("reason", EndReason.entries) { it.wire }, reason)
        EventType.PIN_LOCKOUT -> TripEvent.PinLockout(p.int("failures"), p.int("locked_s"), reason)
        EventType.SETTINGS_CHANGED -> TripEvent.SettingsChanged(
            (p["fields"] as? JsonArray ?: throw IllegalArgumentException("missing payload.fields")).map {
                val wire = it.stringOrNull()
                SettingsField.fromWire(wire.orEmpty()) ?: throw IllegalArgumentException("bad payload.fields $wire")
            },
            reason,
        )
    }

    private fun JsonObject.string(key: String): String =
        this[key].stringOrNull() ?: throw IllegalArgumentException("missing $key")

    private fun JsonObject.int(key: String): Int =
        this[key]?.jsonPrimitive?.int ?: throw IllegalArgumentException("missing payload.$key")

    private fun <T> JsonObject.wire(key: String, entries: List<T>, wireOf: (T) -> String): T {
        val value = string(key)
        return entries.firstOrNull { wireOf(it) == value } ?: throw IllegalArgumentException("bad payload.$key $value")
    }

    private fun JsonElement?.stringOrNull(): String? = (this as? JsonPrimitive)?.contentOrNull

    private fun JsonElement?.doubleOrNull(): Double? = (this as? JsonPrimitive)?.doubleOrNull
}
