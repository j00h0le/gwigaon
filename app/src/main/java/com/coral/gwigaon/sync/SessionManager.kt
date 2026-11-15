package com.coral.gwigaon.sync

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Keeps one anonymous session for this car (SPEC cloud-sync). The same user owns every trip it uploads, so the
 * session is stored and refreshed rather than replaced; a new anonymous user is made only when there is none or
 * the server refuses the refresh (e.g. the user no longer exists). Trips uploaded under an old user then stay
 * read-only for the car, which only matters after a database reset.
 */
class SessionManager(
    private val auth: AuthApi,
    private val store: SessionStore,
    private val clock: Clock,
    private val refreshMargin: Duration = 60.seconds,
    private val log: SyncLog = SyncLog { _, _ -> },
) {
    private val mutex = Mutex()
    private var loaded = false
    private var current: CloudSession? = null

    /** A valid access token, refreshing or signing up first if needed. Throws [CloudError]. */
    suspend fun accessToken(): String = mutex.withLock {
        if (!loaded) {
            current = store.load()
            loaded = true
        }
        current?.takeIf { it.expiresAt - refreshMargin > clock.now() }?.let { return@withLock it.accessToken }
        val next = current?.let { refreshOrSignUp(it) } ?: auth.signUpAnonymously().also { log.log("signed up anonymously", null) }
        store.save(next)
        current = next
        next.accessToken
    }

    /** The server refused the access token (401): refresh before the next call. */
    suspend fun invalidate() = mutex.withLock {
        current = current?.copy(expiresAt = Instant.DISTANT_PAST)
    }

    private suspend fun refreshOrSignUp(old: CloudSession): CloudSession = try {
        auth.refresh(old.refreshToken)
    } catch (e: CloudError.Http) {
        if (e.status !in 400..499) throw e
        log.log("refresh refused (${e.status}); signing up again", null)
        auth.signUpAnonymously()
    }
}
