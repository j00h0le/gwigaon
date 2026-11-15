package com.coral.gwigaon.cues

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/** The switch for one trip: on, or turned off with the indicator (off for the rest of that trip). */
data class Listening(val tripId: String, val on: Boolean)

/**
 * Whether conflict cues are on for a trip (SPEC: off by default, one switch at setup, one tap off for the rest of
 * the trip). Kept in a small app-private file so a restarted process picks the running trip's choice back up;
 * when the file can't be read or written the choice still holds for this process.
 */
class CueSwitch(private val file: File?, private val log: CueLog = CueLog { _, _ -> }) {
    private val _state = MutableStateFlow(read())

    /** null when cues were never turned on (or only for an earlier trip that is long gone). */
    val state: StateFlow<Listening?> = _state.asStateFlow()

    fun isOn(tripId: String): Boolean = _state.value.let { it != null && it.tripId == tripId && it.on }

    fun enable(tripId: String) = set(Listening(tripId, on = true))

    /** Off for the rest of [tripId]; enabling again needs a new trip. */
    fun turnOff(tripId: String) {
        if (_state.value?.tripId == tripId) set(Listening(tripId, on = false))
    }

    private fun set(value: Listening) {
        _state.value = value
        val target = file ?: return
        runCatching { target.writeText("${value.tripId}\n${if (value.on) ON else OFF}\n") }
            .onFailure { log.log("cue switch not saved", it) }
    }

    private fun read(): Listening? {
        val source = file?.takeIf { it.exists() } ?: return null
        return runCatching {
            val lines = source.readLines()
            val tripId = lines.getOrNull(0)?.trim().orEmpty()
            val state = lines.getOrNull(1)?.trim()
            require(tripId.isNotEmpty() && (state == ON || state == OFF)) { "malformed cue switch file" }
            Listening(tripId, state == ON)
        }.onFailure { log.log("cue switch unreadable; cues off", it) }.getOrNull()
    }

    private companion object {
        const val ON = "on"
        const val OFF = "off"
    }
}
