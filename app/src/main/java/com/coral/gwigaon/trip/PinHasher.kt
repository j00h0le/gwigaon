package com.coral.gwigaon.trip

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Salted PBKDF2-HMAC-SHA256 for the passenger's 4-digit PIN (SPEC trip-core). Stored form:
 * `pbkdf2-sha256$<iterations>$<salt b64>$<hash b64>`, so the cost can be raised later without breaking old trips.
 *
 * A 4-digit PIN has only 10 000 values, so the hash alone doesn't stop someone who has the database file;
 * the real protection is [PinGate]'s lockout plus the database staying in app-private storage.
 */
class PinHasher(
    private val random: SecureRandom = SecureRandom(),
    private val iterations: Int = DEFAULT_ITERATIONS,
) {
    init {
        require(iterations in 1..MAX_ITERATIONS) { "iterations out of range" }
    }

    fun hash(pin: String): String {
        require(isValidPin(pin)) { "PIN must be $PIN_LENGTH digits" }
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val derived = derive(pin, salt, iterations)
        return listOf(SCHEME, iterations.toString(), b64.encodeToString(salt), b64.encodeToString(derived))
            .joinToString(SEPARATOR)
    }

    /** False for a wrong PIN, a malformed PIN, or a malformed stored hash; never throws. */
    fun verify(pin: String, stored: String): Boolean {
        if (!isValidPin(pin)) return false
        val parsed = parse(stored) ?: return false
        val derived = derive(pin, parsed.salt, parsed.iterations)
        return MessageDigest.isEqual(derived, parsed.hash)
    }

    private class Parsed(val iterations: Int, val salt: ByteArray, val hash: ByteArray)

    private fun parse(stored: String): Parsed? {
        val parts = stored.split(SEPARATOR)
        if (parts.size != 4 || parts[0] != SCHEME) return null
        val iterations = parts[1].toIntOrNull()?.takeIf { it in 1..MAX_ITERATIONS } ?: return null
        val salt = runCatching { b64d.decode(parts[2]) }.getOrNull() ?: return null
        val hash = runCatching { b64d.decode(parts[3]) }.getOrNull() ?: return null
        if (salt.isEmpty() || hash.size != KEY_BITS / 8) return null
        return Parsed(iterations, salt, hash)
    }

    private fun derive(pin: String, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, iterations, KEY_BITS)
        try {
            return SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    companion object {
        const val PIN_LENGTH = 4

        /** About 0.1 s on the emulator; the lockout, not the cost, is what limits guessing. */
        const val DEFAULT_ITERATIONS = 120_000
        private const val MAX_ITERATIONS = 10_000_000
        private const val SALT_BYTES = 16
        private const val KEY_BITS = 256
        private const val ALGORITHM = "PBKDF2WithHmacSHA256"
        private const val SCHEME = "pbkdf2-sha256"
        private const val SEPARATOR = "$"
        private val b64 = Base64.getEncoder().withoutPadding()
        private val b64d = Base64.getDecoder()

        fun isValidPin(pin: String): Boolean = pin.length == PIN_LENGTH && pin.all { it in '0'..'9' }
    }
}
