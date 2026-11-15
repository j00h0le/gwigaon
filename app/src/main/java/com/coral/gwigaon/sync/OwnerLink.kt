package com.coral.gwigaon.sync

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * This account's standing contact link (ADR 0019): one token, shown as a QR in 설정 and on home. [synced] once the
 * cloud has it (`set_owner_link`); until then the QR works for nobody, so 설정 says 연결 준비 중.
 */
data class OwnerLink(val token: String, val createdAt: Instant, val synced: Boolean) {
    /** The token is a long-lived bearer secret: never in logs. */
    override fun toString(): String = "OwnerLink(createdAt=$createdAt, synced=$synced)"

    /** The contact page with the token in the fragment (`#c=`), which browsers never send to a server. */
    fun url(contactPageUrl: String): String? =
        if (contactPageUrl.isBlank() || !ShareToken.isValid(token)) null else contactPageUrl.substringBefore('#') + "#c=" + token
}

/** What 설정 says under the QR. */
sealed interface LinkConnection {
    /** This build has no cloud: the QR can't connect anyone. */
    data object NoCloud : LinkConnection

    /** Not uploaded yet (offline or still going up). */
    data object Preparing : LinkConnection

    /** Uploaded; nobody has opened it yet. */
    data object NotConnected : LinkConnection

    /** [phones] have opened it, the first at [since]. */
    data class Connected(val phones: Int, val since: Instant) : LinkConnection

    /** Uploaded, but who opened it couldn't be read just now. */
    data object Unknown : LinkConnection
}

/** Where the link is kept on the car (Room in the app, a fake in tests). */
interface OwnerLinkStore {
    suspend fun get(): OwnerLink?

    suspend fun put(link: OwnerLink)
}

/** The two cloud calls for the link, as the car's anonymous user. Both throw [CloudError]. */
interface OwnerLinkApi {
    /** `set_owner_link`: registers [token] (replacing the active one); false = the token is someone else's or revoked. */
    suspend fun setOwnerLink(accessToken: String, token: String, createdAt: Instant): Boolean

    /** When each phone joined [token], oldest first (RLS: only the owner sees them). */
    suspend fun linkViewers(accessToken: String, token: String): List<Instant>
}

class OwnerLinkCloud(val sessions: SessionManager, val api: OwnerLinkApi)

/**
 * Makes the link once, keeps it, uploads it, and replaces it on QR 다시 만들기 (ADR 0019). The token is made on the car
 * so the QR shows at once, even offline; [sync] retries the upload whenever 설정 or home shows the QR.
 */
class OwnerLinkRepository(
    private val store: OwnerLinkStore,
    private val cloud: OwnerLinkCloud?,
    private val clock: Clock,
    private val newToken: () -> String = ShareToken::generate,
    private val log: SyncLog = SyncLog { _, _ -> },
) {
    private val mutex = Mutex()
    private val _link = MutableStateFlow<OwnerLink?>(null)
    val link: StateFlow<OwnerLink?> = _link.asStateFlow()

    /** The stored link, or a new one when there is none yet. */
    suspend fun ensure(): OwnerLink = mutex.withLock {
        _link.value ?: (store.get() ?: OwnerLink(newToken(), clock.now(), synced = false).also { store.put(it) }).also { _link.value = it }
    }

    /** QR 다시 만들기: a new token at once; phones on the old one lose access when it reaches the cloud. */
    suspend fun replace(): OwnerLink = mutex.withLock {
        OwnerLink(newToken(), clock.now(), synced = false).also {
            store.put(it)
            _link.value = it
        }
    }

    /**
     * Uploads the link if it isn't yet. Never throws (it runs on the app scope, where a throw would crash the app:
     * /ship Q5); false when it is still not in the cloud.
     */
    suspend fun sync(): Boolean = try {
        syncOnce()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.log("owner link not synced", e)
        false
    }

    private suspend fun syncOnce(): Boolean {
        val cloud = cloud ?: return false
        val link = ensure()
        if (link.synced) return true
        return try {
            val accepted = cloud.api.setOwnerLink(cloud.sessions.accessToken(), link.token, link.createdAt)
            mutex.withLock {
                // Only if nothing replaced it meanwhile.
                if (_link.value?.token != link.token) return@withLock false
                if (accepted) {
                    val done = link.copy(synced = true)
                    store.put(done)
                    _link.value = done
                    true
                } else {
                    // Someone else's or a revoked token (practically never): start over with a fresh one.
                    log.log("owner link refused; making a new one", null)
                    val fresh = OwnerLink(newToken(), clock.now(), synced = false)
                    store.put(fresh)
                    _link.value = fresh
                    false
                }
            }
        } catch (e: CloudError) {
            if (e is CloudError.Http && e.status == UNAUTHORIZED) cloud.sessions.invalidate()
            log.log("owner link upload failed", e)
            false
        }
    }

    /** Uploads if needed, then reads who opened the link. */
    suspend fun connection(): LinkConnection {
        val cloud = cloud ?: return LinkConnection.NoCloud
        if (!sync()) return LinkConnection.Preparing
        val link = _link.value ?: return LinkConnection.Preparing
        return try {
            val joined = cloud.api.linkViewers(cloud.sessions.accessToken(), link.token)
            if (joined.isEmpty()) LinkConnection.NotConnected else LinkConnection.Connected(joined.size, joined.min())
        } catch (e: CloudError) {
            log.log("owner link viewers unreadable", e)
            LinkConnection.Unknown
        }
    }

    private companion object {
        const val UNAUTHORIZED = 401
    }
}
