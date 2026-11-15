package com.coral.gwigaon.trip

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Instant

/** One of every event type, with non-default payloads. */
fun allEvents(): List<TripEvent> = listOf(
    TripEvent.TripStarted(),
    TripEvent.RouteDeviation(distanceM = 1200, reason = "목적지와 다른 방향으로 1.2km 이동"),
    TripEvent.DeviationCleared(reason = "경로로 복귀"),
    TripEvent.LongStop(durationS = 420, reason = "목적지 외 장소에서 7분 정차"),
    TripEvent.LongStopCleared(),
    TripEvent.Sos(SosSource.TURN_SIGNAL, reason = "방향지시등 패턴으로 SOS"),
    TripEvent.SosCancelled(reason = "오작동으로 취소"),
    TripEvent.MonitoringInterrupted(gapS = 120, reason = "앱 재시작으로 2분간 모니터링 중단"),
    TripEvent.MonitoringResumed(),
    TripEvent.NearDestination(reason = "목적지 500m 이내"),
    TripEvent.Arrived(),
    TripEvent.SafeArrivalCheckOpened(reason = "도착 후 3분간 승객이 하차하지 않음"),
    TripEvent.SafeArrivalConfirmed(SafeArrivalCloser.DRIVER_CONFIRMED, reason = "승객 반응 있음"),
    TripEvent.CheckInPrompted(level = 2, cue = CueKind.PROFANITY),
    TripEvent.CheckInAnswered(CheckInAnswer.SOS),
    TripEvent.TripEnded(EndReason.SAFE_ARRIVAL, reason = "안전 귀가"),
    TripEvent.PinLockout(failures = 5, lockedS = 30, reason = "PIN 5회 오입력 · 30초 잠금"),
    TripEvent.SettingsChanged(listOf(SettingsField.HOME, SettingsField.PIN), reason = "설정 변경: 집 주소, PIN"),
)

class TripEventJsonTest {
    private val at = Instant.parse("2026-11-11T09:30:00Z")

    @Test
    fun wireNamesMatchTheTripEventsTableExactly() {
        // Same list, same order as the latest CHECK constraint on trip_events in supabase/migrations/.
        val expected = listOf(
            "TRIP_STARTED", "ROUTE_DEVIATION", "DEVIATION_CLEARED", "LONG_STOP", "LONG_STOP_CLEARED",
            "SOS", "SOS_CANCELLED", "MONITORING_INTERRUPTED", "MONITORING_RESUMED",
            "NEAR_DESTINATION", "ARRIVED", "SAFE_ARRIVAL_CHECK_OPENED", "SAFE_ARRIVAL_CONFIRMED",
            "CHECK_IN_PROMPTED", "CHECK_IN_ANSWERED", "TRIP_ENDED",
            // Added by the pin_lockout_event migration (T16).
            "PIN_LOCKOUT",
            // Added by the settings_changed_event migration (T26, ADR 0015).
            "SETTINGS_CHANGED",
        )
        assertEquals(expected, EventType.entries.map { it.wire })
        assertEquals(expected, allEvents().map { it.type.wire })
    }

    @Test
    fun everyEventRoundTrips() {
        allEvents().forEachIndexed { i, event ->
            val record = EventRecord(seq = i + 1, occurredAt = at, lat = 37.4979, lng = 127.0276, event = event)
            val json = TripEventJson.encode(record)
            assertEquals(json, record, TripEventJson.decode(json).getOrThrow())
        }
        val timeout = EventRecord(1, at, null, null, TripEvent.CheckInAnswered(CheckInAnswer.NO_RESPONSE, "30초 동안 응답 없음"))
        assertEquals(timeout, TripEventJson.decode(TripEventJson.encode(timeout)).getOrThrow())
    }

    @Test
    fun rowShapeAndPayloadKeysMatchTheCloudContract() {
        val record = EventRecord(7, at, 37.5, 127.0, TripEvent.TripEnded(EndReason.SAFE_ARRIVAL, reason = "안전 귀가"))
        val obj = Json.parseToJsonElement(TripEventJson.encode(record)).jsonObject
        assertEquals(setOf("seq", "type", "occurred_at", "lat", "lng", "reason", "payload"), obj.keys)
        assertEquals("TRIP_ENDED", obj.getValue("type").jsonPrimitive.content)
        assertEquals("2026-11-11T09:30:00Z", obj.getValue("occurred_at").jsonPrimitive.content)
        // web/contact/state.js reads payload.reason === "safe_arrival"
        assertEquals("safe_arrival", obj.getValue("payload").jsonObject.getValue("reason").jsonPrimitive.content)

        assertEquals(mapOf("distance_m" to "1200"), payload(TripEvent.RouteDeviation(distanceM = 1200)))
        assertEquals(mapOf("duration_s" to "420"), payload(TripEvent.LongStop(durationS = 420)))
        assertEquals(mapOf("gap_s" to "120"), payload(TripEvent.MonitoringInterrupted(gapS = 120)))
        assertEquals(mapOf("source" to "turn_signal"), payload(TripEvent.Sos(SosSource.TURN_SIGNAL)))
        assertEquals(mapOf("source" to "on_screen"), payload(TripEvent.Sos(SosSource.ON_SCREEN)))
        assertEquals(mapOf("closed_by" to "seat_empty"), payload(TripEvent.SafeArrivalConfirmed(SafeArrivalCloser.SEAT_EMPTY)))
        assertEquals(mapOf("closed_by" to "passenger_pin"), payload(TripEvent.SafeArrivalConfirmed(SafeArrivalCloser.PASSENGER_PIN)))
        assertEquals(mapOf("closed_by" to "driver_confirmed"), payload(TripEvent.SafeArrivalConfirmed(SafeArrivalCloser.DRIVER_CONFIRMED)))
        assertEquals(mapOf("level" to "1", "cue" to "loud"), payload(TripEvent.CheckInPrompted(level = 1, cue = CueKind.LOUD)))
        assertEquals(mapOf("answer" to "no_response"), payload(TripEvent.CheckInAnswered(CheckInAnswer.NO_RESPONSE)))
        assertEquals(mapOf("answer" to "ok"), payload(TripEvent.CheckInAnswered(CheckInAnswer.OK)))
        assertEquals(mapOf("reason" to "ended_by_passenger"), payload(TripEvent.TripEnded(EndReason.ENDED_BY_PASSENGER)))
        assertEquals(emptyMap<String, String>(), payload(TripEvent.Arrived()))
    }

    @Test
    fun tripStartedCarriesItsSourceAndOldOnesStillRead() {
        assertEquals(mapOf("source" to "button"), payload(TripEvent.TripStarted(StartSource.BUTTON)))
        assertEquals(mapOf("source" to "gear"), payload(TripEvent.TripStarted(StartSource.GEAR)))
        val gear = EventRecord(1, at, null, null, TripEvent.TripStarted(StartSource.GEAR))
        assertEquals(gear, TripEventJson.decode(TripEventJson.encode(gear)).getOrThrow())
        assertEquals(TripEvent.TripStarted(), TripEventJson.eventFromColumns("TRIP_STARTED", null, "{}").getOrThrow())
        assertTrue(TripEventJson.eventFromColumns("TRIP_STARTED", null, """{"source":"remote"}""").isFailure)
    }

    @Test
    fun settingsChangedListsFieldWireNames() {
        val stored = TripEventJson.payloadString(TripEvent.SettingsChanged(listOf(SettingsField.CONTACTS, SettingsField.CUES)))
        assertEquals("""{"fields":["contacts","cues"]}""", stored)
        assertTrue(TripEventJson.eventFromColumns("SETTINGS_CHANGED", null, """{"fields":["wifi"]}""").isFailure)
        assertTrue(TripEventJson.eventFromColumns("SETTINGS_CHANGED", null, "{}").isFailure)
    }

    @Test
    fun missingLocationAndReasonAreJsonNull() {
        val json = TripEventJson.encode(EventRecord(1, at, null, null, TripEvent.TripStarted()))
        val obj = Json.parseToJsonElement(json).jsonObject
        assertEquals("null", obj.getValue("lat").toString())
        assertEquals("null", obj.getValue("reason").toString())
        assertEquals(EventRecord(1, at, null, null, TripEvent.TripStarted()), TripEventJson.decode(json).getOrThrow())
    }

    @Test
    fun decodesTheSeedRowsWrittenByTheCloudTrack() {
        // Shape as supabase/seed.sql and the contact page store it (payload {} when empty).
        val row = """{"seq":4,"type":"SOS","occurred_at":"2026-11-10T13:20:00Z","lat":37.51,"lng":127.05,""" +
            """"reason":"방향지시등 패턴으로 SOS","payload":{"source":"turn_signal"}}"""
        val r = TripEventJson.decode(row).getOrThrow()
        assertEquals(TripEvent.Sos(SosSource.TURN_SIGNAL, reason = "방향지시등 패턴으로 SOS"), r.event)
        assertEquals(4, r.seq)
    }

    @Test
    fun rejectsUnknownTypesAndBadPayloads() {
        fun bad(json: String) = assertTrue(json, TripEventJson.decode(json).isFailure)
        bad("""{"seq":1,"type":"NOT_A_TYPE","occurred_at":"2026-11-11T09:30:00Z","lat":null,"lng":null,"reason":null,"payload":{}}""")
        bad("""{"seq":1,"type":"SOS","occurred_at":"2026-11-11T09:30:00Z","lat":null,"lng":null,"reason":null,"payload":{"source":"radio"}}""")
        bad("""{"seq":1,"type":"LONG_STOP","occurred_at":"2026-11-11T09:30:00Z","lat":null,"lng":null,"reason":null,"payload":{}}""")
        bad("""{"seq":1,"type":"ARRIVED","occurred_at":"yesterday","lat":null,"lng":null,"reason":null,"payload":{}}""")
        bad("not json")
    }

    private fun payload(event: TripEvent): Map<String, String> =
        (Json.parseToJsonElement(TripEventJson.encode(EventRecord(1, at, null, null, event))).jsonObject
            .getValue("payload") as JsonObject).mapValues { it.value.jsonPrimitive.content }
}

class TripEventColumnsTest {
    @Test
    fun everyEventSurvivesTheStoredColumns() {
        allEvents().forEach { e ->
            val back = TripEventJson.eventFromColumns(e.type.wire, e.reason, TripEventJson.payloadString(e)).getOrThrow()
            assertEquals(e, back)
        }
    }

    @Test
    fun storedPayloadUsesWireKeys() {
        assertEquals("""{"gap_s":120}""", TripEventJson.payloadString(TripEvent.MonitoringInterrupted(gapS = 120)))
        assertEquals("{}", TripEventJson.payloadString(TripEvent.Arrived()))
    }

    @Test
    fun badColumnsFailInsteadOfGuessing() {
        assertTrue(TripEventJson.eventFromColumns("NOPE", null, "{}").isFailure)
        assertTrue(TripEventJson.eventFromColumns("LONG_STOP", null, "{}").isFailure)
        assertTrue(TripEventJson.eventFromColumns("ARRIVED", null, "not json").isFailure)
    }
}
