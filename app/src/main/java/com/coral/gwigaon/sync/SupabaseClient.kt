package com.coral.gwigaon.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import java.io.IOException
import java.net.URLEncoder
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * The app's Supabase calls over plain HTTP (ADR 0009): Auth anonymous sign-up and refresh, and three PostgREST
 * calls on `trips` and `trip_events`, plus the account's standing link (ADR 0019). [apiKey] is the publishable key only (SPEC: never the service role key);
 * every table call carries the user's access token, so RLS decides what goes through.
 */
class SupabaseClient(
    baseUrl: String,
    private val apiKey: String,
    private val http: HttpTransport,
    private val clock: Clock,
) : AuthApi, RestApi, SummaryApi, OwnerLinkApi, PhraseApi {
    private val base = baseUrl.trimEnd('/')

    override suspend fun signUpAnonymously(): CloudSession =
        session(call("POST", "$base/auth/v1/signup", token = null, body = "{}"))

    override suspend fun refresh(refreshToken: String): CloudSession = session(
        call(
            "POST", "$base/auth/v1/token?grant_type=refresh_token", token = null,
            body = buildJsonObject { put("refresh_token", JsonPrimitive(refreshToken)) }.toString(),
        ),
    )

    override suspend fun insertTrip(accessToken: String, trip: CloudTrip) {
        val row = buildJsonObject {
            put("id", JsonPrimitive(trip.id))
            put("share_token", JsonPrimitive(trip.shareToken))
            put("status", JsonPrimitive(trip.status))
            put("destination_label", JsonPrimitive(trip.destinationLabel.take(MAX_DESTINATION_LABEL)))
            put("contact_label", JsonPrimitive(trip.contactLabel.take(MAX_CONTACT_LABEL)))
            put("started_at", JsonPrimitive(trip.startedAt.toString()))
            put("ended_at", JsonPrimitive(trip.endedAt?.toString()))
            put("last_seen_at", JsonPrimitive(trip.lastSeenAt.toString()))
        }
        call("POST", "$base/rest/v1/trips", accessToken, row.toString(), prefer = INSERT_IGNORE)
    }

    override suspend fun insertEvents(accessToken: String, rows: List<PendingUpload>) {
        if (rows.isEmpty()) return
        val body = buildJsonArray {
            rows.forEach { r ->
                add(
                    buildJsonObject {
                        put("trip_id", JsonPrimitive(r.tripId))
                        put("seq", JsonPrimitive(r.seq))
                        put("type", JsonPrimitive(r.type))
                        put("occurred_at", JsonPrimitive(r.occurredAt.toString()))
                        put("lat", JsonPrimitive(r.lat))
                        put("lng", JsonPrimitive(r.lng))
                        put("reason", JsonPrimitive(r.reason))
                        put("payload", Json.parseToJsonElement(r.payload))
                    },
                )
            }
        }
        call("POST", "$base/rest/v1/trip_events?on_conflict=trip_id,seq", accessToken, body.toString(), prefer = INSERT_IGNORE)
    }

    override suspend fun patchTrip(accessToken: String, trip: CloudTrip) {
        val row = buildJsonObject {
            put("status", JsonPrimitive(trip.status))
            put("ended_at", JsonPrimitive(trip.endedAt?.toString()))
            put("last_seen_at", JsonPrimitive(trip.lastSeenAt.toString()))
        }
        val id = URLEncoder.encode(trip.id, Charsets.UTF_8.name())
        call("PATCH", "$base/rest/v1/trips?id=eq.$id", accessToken, row.toString(), prefer = "return=minimal")
    }

    override suspend fun setOwnerLink(accessToken: String, token: String, createdAt: Instant): Boolean {
        val body = buildJsonObject {
            put("p_token", JsonPrimitive(token))
            put("p_created_at", JsonPrimitive(createdAt.toString()))
        }.toString()
        val text = call("POST", "$base/rest/v1/rpc/set_owner_link", accessToken, body)
        return text.trim() == "true"
    }

    /**
     * The token stays out of the URL (API logs record query strings; ADR 0022): RLS already limits the rows to the
     * viewers of this account's links, and the link is picked from the answer.
     */
    override suspend fun linkViewers(accessToken: String, token: String): List<Instant> {
        val text = call("GET", "$base/rest/v1/owner_link_viewers?select=token,joined_at&order=joined_at.asc", accessToken, body = null)
        return runCatching {
            Json.parseToJsonElement(text).let { it as JsonArray }.mapNotNull { row ->
                (row as? JsonObject)?.takeIf { it.text("token") == token }?.text("joined_at")
                    ?.let { runCatching { Instant.parse(it) }.getOrNull() }
            }
        }.getOrElse { throw CloudError.Http(UNUSABLE_SUCCESS, "unreadable_viewers") }
    }

    override suspend fun summarizeTrip(accessToken: String, tripId: String): TripSummary {
        val body = buildJsonObject { put("trip_id", JsonPrimitive(tripId)) }.toString()
        val text = call("POST", "$base/functions/v1/summarize-trip", accessToken, body)
        return parseSummary(text) ?: throw CloudError.Http(UNUSABLE_SUCCESS, "unreadable_summary")
    }

    override suspend fun phraseArrival(accessToken: String, entrance: String, parking: String, key: String): String {
        val body = buildJsonObject {
            put("entrance", JsonPrimitive(entrance))
            put("parking", JsonPrimitive(parking))
            put("key", JsonPrimitive(key))
        }.toString()
        val text = call("POST", "$base/functions/v1/phrase-arrival", accessToken, body)
        return runCatching { Json.parseToJsonElement(text).jsonObject.text("sentence") }.getOrNull()
            ?.takeIf { it.isNotBlank() } ?: throw CloudError.Http(UNUSABLE_SUCCESS, "unreadable_sentence")
    }

    /** null if the answer isn't the function's shape; the function validated the content already. */
    private fun parseSummary(text: String): TripSummary? = runCatching {
        val obj = Json.parseToJsonElement(text).jsonObject
        TripSummary(
            summary = obj.text("summary") ?: return null,
            summarySeqs = obj.array("summary_seqs").mapNotNull { (it as? JsonPrimitive)?.intOrNull },
            notableEvents = obj.array("notable_events").mapNotNull { e ->
                val o = e as? JsonObject ?: return@mapNotNull null
                val seq = (o["seq"] as? JsonPrimitive)?.intOrNull ?: return@mapNotNull null
                NotableEvent(seq, o.text("text") ?: return@mapNotNull null)
            },
            caveats = obj.array("caveats").mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
            model = obj.text("model").orEmpty(),
        )
    }.getOrNull()

    /** [body] null sends none (GET: a body would turn HttpURLConnection's request into a POST). */
    private suspend fun call(method: String, url: String, token: String?, body: String?, prefer: String? = null): String {
        val headers = buildMap {
            put("apikey", apiKey)
            if (body != null) put("Content-Type", "application/json")
            put("Accept", "application/json")
            if (token != null) put("Authorization", "Bearer $token")
            if (prefer != null) put("Prefer", prefer)
        }
        val response = try {
            http.send(HttpRequest(method, url, headers, body))
        } catch (e: IOException) {
            throw CloudError.Network(e)
        }
        if (response.status !in 200..299) throw CloudError.Http(response.status, errorCode(response.body))
        return response.body
    }

    private fun session(body: String): CloudSession {
        val obj = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: throw CloudError.Http(UNUSABLE_SUCCESS, "unreadable_session")
        val access = obj.text("access_token")
        val refresh = obj.text("refresh_token")
        if (access == null || refresh == null) throw CloudError.Http(UNUSABLE_SUCCESS, "no_session")
        val expiresAt = obj.long("expires_at")?.let { Instant.fromEpochSeconds(it) }
            ?: (clock.now() + (obj.long("expires_in") ?: DEFAULT_EXPIRES_IN_S).seconds)
        return CloudSession(access, refresh, expiresAt)
    }

    /** PostgREST says `code`, Auth `error_code` (or `code` as a number); either helps the logs, nothing else. */
    private fun errorCode(body: String): String? = runCatching {
        val obj = Json.parseToJsonElement(body).jsonObject
        obj.text("code") ?: obj.text("error_code")
    }.getOrNull()

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.array(key: String): JsonArray = this[key] as? JsonArray ?: JsonArray(emptyList())

    private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull

    private companion object {
        const val INSERT_IGNORE = "resolution=ignore-duplicates,return=minimal"
        /** Limits from the cloud CHECK constraints (trips migration). */
        const val MAX_DESTINATION_LABEL = 200
        const val MAX_CONTACT_LABEL = 100
        const val DEFAULT_EXPIRES_IN_S = 3600L
        /** A 2xx whose body isn't a usable session or summary is treated like a refused request. */
        const val UNUSABLE_SUCCESS = 502
    }
}
