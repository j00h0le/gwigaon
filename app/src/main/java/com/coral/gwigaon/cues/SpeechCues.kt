package com.coral.gwigaon.cues

import com.coral.gwigaon.trip.CueKind
import kotlin.time.Clock

/** What the speech engine reports back. Text stays inside stage 2: it is matched and dropped, never kept. */
interface SpeechListener {
    /** A partial ([completed] false) or final result for the current utterance. */
    fun onText(text: String, completed: Boolean)

    /** The engine failed mid-utterance: no final will come, so whatever utterance was open is over. */
    fun onAborted()
}

/**
 * The on-device recogniser ([PleosStt] in the app). It never opens the mic: it is fed stage 1's PCM. Every call is
 * safe before the engine is ready or after it failed (audio is then dropped), so stage 1 never depends on it.
 */
interface SpeechEngine {
    /** Reports results to [listener] from now on. */
    fun listen(listener: SpeechListener)

    /** Starts the engine ahead of time, so the first session doesn't wait ~2.5 s. Retries after a failure. */
    fun warmUp()

    /** 16-bit mono PCM at the recorder's rate, at real time. */
    fun send(pcm: ShortArray)

    /** No more audio for now (the sample or the stream ended). */
    fun complete()
}

/**
 * Conflict cues stage 2 (SPEC conflict-cues, ADR 0012): decides when stage 1's audio goes to the [SpeechEngine]
 * ([StageTwoScheduler]), and turns the engine's results into word cues ([WordMatcher]) for [CueMonitor]. Only the
 * cue leaves this class: no text is stored, logged or passed on. Thread-safe, because results arrive on the engine's
 * thread while audio comes from the recorder's.
 */
class SpeechCues(
    private val engine: SpeechEngine,
    private val clock: Clock,
    config: CueConfig = CueConfig.DEFAULT,
    private val log: CueLog = CueLog { _, _ -> },
) {
    private val lock = Any()
    private val scheduler = StageTwoScheduler(config)
    private val matcher = WordMatcher()
    private var mode = StageTwoMode.IDLE
    private var onHit: ((CueKind) -> Unit)? = null

    init {
        engine.listen(Results())
    }

    fun warmUp() = engine.warmUp()

    /**
     * Listening started: sample at once, report hits to [onHit] until [stop]. Also warms the engine up (a no-op when
     * it is already up), because a trip resumed after a process restart starts listening without the switch.
     */
    fun start(onHit: (CueKind) -> Unit) = synchronized(lock) {
        runCatching(engine::warmUp).onFailure { log.log("speech warm-up failed", it) }
        this.onHit = onHit
        matcher.reset()
        scheduler.reset(clock.now())
        mode = StageTwoMode.IDLE
    }

    /** Listening stopped: the engine gets nothing more and no hit is reported. */
    fun stop() = synchronized(lock) {
        onHit = null
        if (mode != StageTwoMode.IDLE) engine.complete()
        mode = StageTwoMode.IDLE
        matcher.reset()
    }

    /** One chunk of stage 1's audio, before its windows are judged. Sent on if stage 2 is listening now. */
    fun onChunk(chunk: ShortArray): Unit = synchronized(lock) {
        if (onHit == null) return
        if (advance() != StageTwoMode.IDLE) engine.send(chunk)
    }

    /**
     * A loudness window ended. A loud one wakes stage 2; if it was idle, [recent] (the audio just heard, the window
     * included) goes first, so the word that made it loud isn't missed.
     */
    fun onWindow(loud: Boolean, recent: () -> ShortArray): Unit = synchronized(lock) {
        if (onHit == null || !loud) return
        val wasIdle = advance() == StageTwoMode.IDLE
        scheduler.onLoud(clock.now())
        advance()
        if (wasIdle) engine.send(recent())
    }

    /** Moves the schedule to now; a sample or stream that just ended is completed. */
    private fun advance(): StageTwoMode {
        val next = scheduler.mode(clock.now())
        if (next != mode) {
            if (next == StageTwoMode.IDLE) {
                engine.complete()
                matcher.reset()
            }
            log.log("stage 2 ${next.name.lowercase()}", null)
            mode = next
        }
        return next
    }

    private inner class Results : SpeechListener {
        override fun onText(text: String, completed: Boolean) {
            val report = synchronized(lock) {
                val target = onHit ?: return
                val cue = matcher.onResult(text, completed) ?: return
                scheduler.onHit(clock.now())
                advance()
                log.log("stage 2 word cue ${cue.wire}", null)
                target to cue
            }
            report.first(report.second)
        }

        override fun onAborted() = synchronized(lock) { matcher.reset() }
    }
}
