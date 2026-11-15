package com.coral.gwigaon.sync

import java.security.SecureRandom
import java.util.Base64

/**
 * The contact's key to one trip (SPEC cloud-sync: ≥ 128-bit random, url-safe). Made on the car when the trip
 * starts, so the link and QR work before anything is uploaded. 18 random bytes → 24 characters, the same shape
 * as the cloud default and the contact page's check (`[A-Za-z0-9_-]{22,}`).
 */
object ShareToken {
    private const val BYTES = 18
    private val random = SecureRandom()
    private val PATTERN = Regex("^[A-Za-z0-9_-]{22,}$")

    fun generate(): String {
        val bytes = ByteArray(BYTES).also(random::nextBytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    fun isValid(token: String): Boolean = PATTERN.matches(token)

    /**
     * The contact page link: the token goes in the fragment (`#t=`), which browsers never send to a server
     * (SPEC contact-page). Null when no page is configured or the token doesn't look like one.
     */
    fun link(contactPageUrl: String, token: String): String? {
        if (contactPageUrl.isBlank() || !isValid(token)) return null
        return contactPageUrl.substringBefore('#') + "#t=" + token
    }
}
