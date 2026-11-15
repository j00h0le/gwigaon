package com.coral.gwigaon.arrival

/**
 * Speaks a Korean sentence once, without blocking the caller. In the app this is [PleosTts]; when speech fails
 * the arrival card still shows (SPEC arrival: card only), so implementations log errors instead of throwing.
 */
fun interface Speaker {
    fun speak(text: String)
}

/** Where the arrival module reports what it logged, spoke or failed to; the app writes it to logcat. */
fun interface ArrivalLog {
    fun log(message: String, error: Throwable?)
}
