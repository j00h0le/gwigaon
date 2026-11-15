package com.coral.gwigaon.cues

import kotlinx.coroutines.flow.Flow

/** Where the cabin audio comes from: the car's mic in the app ([AndroidMic]), a scripted one in tests. */
interface MicSource {
    /** Whether the app may record at all (`RECORD_AUDIO`). */
    fun permitted(): Boolean

    /**
     * 16-bit mono PCM at the configured rate, in chunks of about 100 ms. Collecting opens the mic, cancelling
     * releases it. Fails if the mic can't be opened.
     */
    fun chunks(): Flow<ShortArray>
}

/** Where the cues report what happened; the app writes it to logcat, tests stay silent. Never gets audio or words. */
fun interface CueLog {
    fun log(message: String, error: Throwable?)
}
