package com.coral.gwigaon.sync

import com.coral.gwigaon.trip.TestClock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class OwnerLinkTest {
    private val clock = TestClock()
    private val store = MemoryLinkStore()
    private val api = FakeLinkApi()
    private val auth = FakeAuth()
    private val sessions = SessionManager(auth, MemorySessionStore(), clock)
    private var n = 0
    private val logged = mutableListOf<String>()
    private fun repo(cloud: Boolean = true) = OwnerLinkRepository(
        store, if (cloud) OwnerLinkCloud(sessions, api) else null, clock,
        newToken = { "linkToken_${n++}_0123456789abcdefgh" },
        log = { m, _ -> logged += m },
    )

    @Test
    fun theLinkIsMadeOnceAndKept() = runTest {
        val first = repo().ensure()
        assertEquals(first, repo().ensure())
        assertFalse(first.synced)
        assertEquals(clock.now, first.createdAt)
        assertEquals(1, n)
    }

    @Test
    fun syncNeverThrowsWhenTheDatabaseFails() = runTest {
        store.putFailure = IllegalStateException("disk full")
        assertFalse(repo().sync())
        assertEquals(listOf("owner link not synced"), logged)
        assertTrue(api.registered.isEmpty())
    }

    @Test
    fun syncUploadsOnceAndMarksTheLinkSynced() = runTest {
        val r = repo()
        assertTrue(r.sync())
        assertTrue(r.sync())
        assertEquals(1, api.registered.size)
        assertEquals("linkToken_0_0123456789abcdefgh", api.registered.single().first)
        assertTrue(store.get()!!.synced)
    }

    @Test
    fun aReplaceDuringAnUploadKeepsTheNewTokenAndUploadsItNext() = runTest {
        // Checkpoint F review R5: the old token's answer must not overwrite the QR made while it was in flight.
        val r = repo()
        val gate = CompletableDeferred<Unit>()
        api.gate = gate
        val inFlight = launch { assertFalse(r.sync()) }
        runCurrent()
        val fresh = r.replace()
        gate.complete(Unit)
        inFlight.join()
        assertEquals(fresh, r.link.value)
        assertEquals(fresh, store.get())
        assertFalse(fresh.synced)
        api.gate = null
        assertTrue(r.sync())
        assertEquals(fresh.token, api.registered.last().first)
        assertTrue(store.get()!!.synced)
    }

    @Test
    fun offlineKeepsTheQrAndSaysPreparing() = runTest {
        api.failures += offline
        val r = repo()
        assertEquals(LinkConnection.Preparing, r.connection())
        assertFalse(store.get()!!.synced)
        assertEquals("owner link upload failed", logged.single())
        // Back online, the same token goes up.
        assertEquals(LinkConnection.NotConnected, r.connection())
        assertEquals(listOf("linkToken_0_0123456789abcdefgh"), api.registered.map { it.first })
    }

    @Test
    fun aRefusedTokenIsReplacedByAFreshOne() = runTest {
        api.accept = false
        val r = repo()
        assertFalse(r.sync())
        assertEquals("linkToken_1_0123456789abcdefgh", r.link.value!!.token)
        api.accept = true
        assertTrue(r.sync())
        assertEquals("linkToken_1_0123456789abcdefgh", api.registered.last().first)
    }

    @Test
    fun anExpiredSessionIsDroppedSoTheNextCallSignsInAgain() = runTest {
        api.failures += CloudError.Http(401, "jwt expired")
        val r = repo()
        assertFalse(r.sync())
        assertTrue(r.sync())
        assertEquals(1, auth.signUps)
        assertEquals(1, auth.refreshes) // the dropped session was refreshed, not replaced
    }

    @Test
    fun connectionCountsThePhonesThatJoined() = runTest {
        val r = repo()
        val t1 = Instant.parse("2026-11-14T10:00:00Z")
        api.viewers = listOf(t1, t1 + 5.minutes)
        assertEquals(LinkConnection.Connected(2, t1), r.connection())
        api.viewerFailure = offline
        assertEquals(LinkConnection.Unknown, r.connection())
    }

    @Test
    fun withoutACloudTheLinkIsKeptButNeverUploaded() = runTest {
        val r = repo(cloud = false)
        assertEquals(LinkConnection.NoCloud, r.connection())
        assertFalse(r.sync())
        assertTrue(api.registered.isEmpty())
    }

    @Test
    fun replaceMakesANewUnsyncedToken() = runTest {
        val r = repo()
        r.sync()
        clock.now += 3.minutes
        val fresh = r.replace()
        assertEquals("linkToken_1_0123456789abcdefgh", fresh.token)
        assertFalse(fresh.synced)
        assertEquals(clock.now, fresh.createdAt)
        assertTrue(r.sync())
        assertEquals(fresh.token, api.registered.last().first)
    }

    @Test
    fun theUrlCarriesTheTokenOnlyInTheFragmentAndToStringHidesIt() {
        val link = OwnerLink("linkToken_0_0123456789abcdefgh", clock.now, synced = true)
        assertEquals("https://x.example/c/#c=linkToken_0_0123456789abcdefgh", link.url("https://x.example/c/#old"))
        assertNull(link.url(""))
        assertNull(link.copy(token = "short").url("https://x.example/c/"))
        assertFalse(link.toString().contains(link.token))
    }

    @Test
    fun theClientRegistersByRpcAndReadsViewersWithoutABody() = runTest {
        val sent = mutableListOf<HttpRequest>()
        var reply = HttpResponse(200, "true")
        val client = SupabaseClient("http://local/", "sb_publishable_test", { r -> sent += r; reply }, clock)
        val at = Instant.parse("2026-11-14T09:00:00Z")

        assertTrue(client.setOwnerLink("acc", "linkToken_0_0123456789abcdefgh", at))
        val post = sent.single()
        assertEquals("POST", post.method)
        assertEquals("http://local/rest/v1/rpc/set_owner_link", post.url)
        assertEquals("Bearer acc", post.headers["Authorization"])
        val json = Json.parseToJsonElement(post.body!!).jsonObject
        assertEquals("linkToken_0_0123456789abcdefgh", json["p_token"]!!.jsonPrimitive.content)
        assertEquals(at.toString(), json["p_created_at"]!!.jsonPrimitive.content)
        reply = HttpResponse(200, "false")
        assertFalse(client.setOwnerLink("acc", "linkToken_0_0123456789abcdefgh", at))

        sent.clear()
        reply = HttpResponse(
            200,
            """[{"token":"linkToken_0_0123456789abcdefgh","joined_at":"2026-11-14T10:00:00+00:00"},""" +
                """{"token":"linkToken_0_0123456789abcdefgh","joined_at":"bad"},""" +
                """{"token":"oldToken_00_0123456789abcdefgh","joined_at":"2026-11-13T10:00:00+00:00"}]""",
        )
        assertEquals(listOf(Instant.parse("2026-11-14T10:00:00Z")), client.linkViewers("acc", "linkToken_0_0123456789abcdefgh"))
        val get = sent.single()
        assertEquals("GET", get.method)
        assertNull(get.body)
        assertFalse("Content-Type" in get.headers)
        assertTrue(get.url.endsWith("owner_link_viewers?select=token,joined_at&order=joined_at.asc"))
        assertFalse("linkToken" in get.url) // ADR 0022: the token never goes in a URL the server logs
        reply = HttpResponse(200, "{}")
        assertTrue(runCatching { client.linkViewers("acc", "linkToken_0_0123456789abcdefgh") }.exceptionOrNull() is CloudError.Http)
    }
}

class MemoryLinkStore : OwnerLinkStore {
    private var kept: OwnerLink? = null
    var putFailure: Exception? = null

    override suspend fun get() = kept

    override suspend fun put(link: OwnerLink) {
        putFailure?.let { throw it }
        kept = link
    }
}

class FakeLinkApi : OwnerLinkApi {
    val registered = mutableListOf<Pair<String, Instant>>()
    val failures = ArrayDeque<Exception>()
    var accept = true
    var viewers = emptyList<Instant>()
    var viewerFailure: Exception? = null

    /** Holds an upload in flight until completed (a replace can happen meanwhile). */
    var gate: CompletableDeferred<Unit>? = null

    override suspend fun setOwnerLink(accessToken: String, token: String, createdAt: Instant): Boolean {
        gate?.await()
        failures.removeFirstOrNull()?.let { throw it }
        if (accept) registered += token to createdAt
        return accept
    }

    override suspend fun linkViewers(accessToken: String, token: String): List<Instant> {
        viewerFailure?.let { throw it }
        return viewers
    }
}
