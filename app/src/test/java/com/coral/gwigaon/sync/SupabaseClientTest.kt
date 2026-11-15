package com.coral.gwigaon.sync

import com.coral.gwigaon.trip.TestClock
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class SupabaseClientTest {
    private val clock = TestClock()
    private val sent = mutableListOf<HttpRequest>()
    private var reply: (HttpRequest) -> HttpResponse = { HttpResponse(201, "") }
    private val client = SupabaseClient("http://10.0.2.2:54321/", "sb_publishable_test", { r -> sent += r; reply(r) }, clock)
    private val t0 = Instant.parse("2026-11-11T09:00:00Z")
    private val trip = CloudTrip("trip-1", "tok_abcdefghijklmnopqrstuv", "active", "집".repeat(250), "언니", t0, null, t0, false, null)

    @Test
    fun anonymousSignUpPostsAnEmptyBodyWithTheKeyAndReadsTheSession() = runTest {
        reply = { HttpResponse(200, """{"access_token":"a1","refresh_token":"r1","expires_at":1793000000,"expires_in":3600,"user":{"is_anonymous":true}}""") }

        val s = client.signUpAnonymously()

        val r = sent.single()
        assertEquals("POST", r.method)
        assertEquals("http://10.0.2.2:54321/auth/v1/signup", r.url)
        assertEquals("{}", r.body)
        assertEquals("sb_publishable_test", r.headers["apikey"])
        assertFalse("Authorization" in r.headers)
        assertEquals(CloudSession("a1", "r1", Instant.fromEpochSeconds(1793000000)), s)
    }

    @Test
    fun refreshSendsTheRefreshTokenAndFallsBackToExpiresIn() = runTest {
        reply = { HttpResponse(200, """{"access_token":"a2","refresh_token":"r2","expires_in":600}""") }

        val s = client.refresh("r1")

        assertEquals("http://10.0.2.2:54321/auth/v1/token?grant_type=refresh_token", sent.single().url)
        assertEquals("r1", Json.parseToJsonElement(sent.single().body!!).jsonObject["refresh_token"]!!.jsonPrimitive.content)
        assertEquals(clock.now + 600.seconds, s.expiresAt)
    }

    @Test
    fun aSuccessWithoutTokensIsAnError() = runTest {
        reply = { HttpResponse(200, """{"user":{}}""") }
        assertTrue(runCatching { client.signUpAnonymously() }.exceptionOrNull() is CloudError.Http)
        reply = { HttpResponse(200, "not json") }
        assertTrue(runCatching { client.signUpAnonymously() }.exceptionOrNull() is CloudError.Http)
    }

    @Test
    fun tripInsertIgnoresDuplicatesAndKeepsLabelsWithinTheCloudLimits() = runTest {
        client.insertTrip("user-token", trip)

        val r = sent.single()
        assertEquals("POST", r.method)
        assertEquals("http://10.0.2.2:54321/rest/v1/trips", r.url)
        assertEquals("Bearer user-token", r.headers["Authorization"])
        assertEquals("resolution=ignore-duplicates,return=minimal", r.headers["Prefer"])
        val row = Json.parseToJsonElement(r.body!!).jsonObject
        assertEquals("tok_abcdefghijklmnopqrstuv", row["share_token"]!!.jsonPrimitive.content)
        assertEquals(200, row["destination_label"]!!.jsonPrimitive.content.length)
        assertEquals(JsonNull, row["ended_at"])
        assertEquals("2026-11-11T09:00:00Z", row["started_at"]!!.jsonPrimitive.content)
        assertFalse("owner" in row) // the server sets it from the token
    }

    @Test
    fun eventsGoAsOneArrayWithOnConflictOnTripAndSeq() = runTest {
        val rows = listOf(
            PendingUpload(1, false, 0, "trip-1", 1, "TRIP_STARTED", t0, null, null, null, "{}"),
            PendingUpload(2, true, 0, "trip-1", 2, "SOS", t0, 37.5, 127.0, "화면 SOS 버튼으로 SOS", """{"source":"on_screen"}"""),
        )

        client.insertEvents("user-token", rows)

        val r = sent.single()
        assertEquals("http://10.0.2.2:54321/rest/v1/trip_events?on_conflict=trip_id,seq", r.url)
        assertEquals("resolution=ignore-duplicates,return=minimal", r.headers["Prefer"])
        val arr: JsonArray = Json.parseToJsonElement(r.body!!).jsonArray
        assertEquals(listOf(1, 2), arr.map { it.jsonObject["seq"]!!.jsonPrimitive.int })
        assertEquals("on_screen", arr[1].jsonObject["payload"]!!.jsonObject["source"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, arr[0].jsonObject["lat"])
    }

    @Test
    fun noEventsNoRequest() = runTest {
        client.insertEvents("t", emptyList())
        assertTrue(sent.isEmpty())
    }

    @Test
    fun patchUpdatesOnlyStatusEndAndLastSeen() = runTest {
        reply = { HttpResponse(204, "") }
        client.patchTrip("user-token", trip.copy(status = "ended", endedAt = t0 + 60.seconds))

        val r = sent.single()
        assertEquals("PATCH", r.method)
        assertEquals("http://10.0.2.2:54321/rest/v1/trips?id=eq.trip-1", r.url)
        assertEquals(setOf("status", "ended_at", "last_seen_at"), Json.parseToJsonElement(r.body!!).jsonObject.keys)
    }

    @Test
    fun httpErrorsCarryStatusAndCodeButNoBody() = runTest {
        reply = { HttpResponse(403, """{"code":"42501","message":"new row violates row-level security policy for table \"trip_events\""}""") }
        val e = runCatching { client.insertTrip("t", trip) }.exceptionOrNull() as CloudError.Http
        assertEquals(403, e.status)
        assertEquals("42501", e.code)
        assertEquals("http 403 (42501)", e.message)

        reply = { HttpResponse(400, """{"error_code":"refresh_token_not_found"}""") }
        assertEquals("refresh_token_not_found", (runCatching { client.refresh("x") }.exceptionOrNull() as CloudError.Http).code)

        reply = { HttpResponse(502, "<html>bad gateway</html>") }
        assertEquals(null, (runCatching { client.insertTrip("t", trip) }.exceptionOrNull() as CloudError.Http).code)
    }

    @Test
    fun noAnswerIsANetworkError() = runTest {
        reply = { throw IOException("timeout") }
        assertTrue(runCatching { client.insertTrip("t", trip) }.exceptionOrNull() is CloudError.Network)
    }

    @Test
    fun requestsNeverPrintHeadersOrBodies() {
        val r = HttpRequest("POST", "http://x/rest/v1/trips?id=eq.1", mapOf("apikey" to "k", "Authorization" to "Bearer t"), "{}")
        assertEquals("HttpRequest(POST http://x/rest/v1/trips)", r.toString())
        assertFalse(trip.toString().contains("tok_"))
    }

    @Test
    fun summarizeTripCallsTheFunctionWithTheUsersTokenAndReadsTheReport() = runTest {
        reply = {
            HttpResponse(
                200,
                """{"summary":"안전하게 도착했어요.","summary_seqs":[1,4],"notable_events":[{"seq":2,"text":"경로 이탈"},{"text":"seq 없음"}],""" +
                    """"caveats":["정차 이유는 기록에 없어요."],"model":"claude-sonnet-5-5","cached":false}""",
            )
        }

        val s = client.summarizeTrip("user-token", "trip-1")

        val r = sent.single()
        assertEquals("POST", r.method)
        assertEquals("http://10.0.2.2:54321/functions/v1/summarize-trip", r.url)
        assertEquals("Bearer user-token", r.headers["Authorization"])
        assertEquals("trip-1", Json.parseToJsonElement(r.body!!).jsonObject["trip_id"]!!.jsonPrimitive.content)
        assertEquals(
            TripSummary("안전하게 도착했어요.", listOf(1, 4), listOf(NotableEvent(2, "경로 이탈")), listOf("정차 이유는 기록에 없어요."), "claude-sonnet-5-5"),
            s,
        )
    }

    @Test
    fun anUnreadableOrFailedSummaryIsAnError() = runTest {
        reply = { HttpResponse(200, """{"cached":true}""") }
        assertTrue(runCatching { client.summarizeTrip("t", "trip-1") }.exceptionOrNull() is CloudError.Http)
        reply = { HttpResponse(502, """{"error":"model_unavailable"}""") }
        val e = runCatching { client.summarizeTrip("t", "trip-1") }.exceptionOrNull() as CloudError.Http
        assertEquals(502, e.status)
    }
}
