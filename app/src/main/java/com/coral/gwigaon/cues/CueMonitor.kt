package com.coral.gwigaon.cues

import com.coral.gwigaon.sos.SosController
import com.coral.gwigaon.trip.ActiveTrip
import com.coral.gwigaon.trip.CheckInAnswer
import com.coral.gwigaon.trip.CueKind
import com.coral.gwigaon.trip.EventRecord
import com.coral.gwigaon.trip.EventType
import com.coral.gwigaon.trip.SosSource
import com.coral.gwigaon.trip.TripEvent
import com.coral.gwigaon.trip.TripSessionRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.roundToInt
import kotlin.time.Clock

/**
 * Conflict cues stage 1 and the check-in (SPEC conflict-cues, ADR 0012), for the process lifetime like SOS.
 *
 * The mic is on ([listening]) only while a trip runs, cues are switched on for that trip, `RECORD_AUDIO` is granted
 * and the mic opened; it is released the moment any of that stops. Each chunk goes into the 30 s in-memory
 * [PcmRingBuffer] and the [LoudnessMeter]; each 1 s window is judged against the trip's [LoudnessBaseline] and fed
 * to [CueLevelRule]. A prompt logs `CHECK_IN_PROMPTED` (the card shows from the trip state's `checkInPending`); the
 * answer, or the timeout, logs `CHECK_IN_ANSWERED`. Cues only ever log events: the contact sees them on the
 * timeline, nothing more. When SOS fires during a listening trip, the buffer is written once as that SOS's clip.
 * Stage 2 ([SpeechCues]) gets the same chunks and every window's verdict while listening, and reports word hits
 * through [onWordHit]; its engine warms up when the switch goes on.
 */
class CueMonitor(
    private val sessions: TripSessionRepository,
    private val sos: SosController,
    private val mic: MicSource,
    private val switch: CueSwitch,
    private val clips: ClipStore,
    private val clock: Clock,
    private val config: CueConfig = CueConfig.DEFAULT,
    private val log: CueLog = CueLog { _, _ -> },
    private val speech: SpeechCues? = null,
    private val warmUp: () -> Unit = { speech?.warmUp() },
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val mutex = Mutex()
    private val rule = CueLevelRule(config)
    private val meter = LoudnessMeter(config.samplesPerWindow)
    private val baseline = LoudnessBaseline(config)
    private val ring = PcmRingBuffer(config.clipSamples)
    private var ruleTripId: String? = null
    private var hitScope: CoroutineScope? = null

    private val permitted = MutableStateFlow(mic.permitted())
    private val micFailedFor = MutableStateFlow<String?>(null)
    private val _listening = MutableStateFlow(false)

    /** True while the mic is on for the running trip: the indicator shows exactly then. */
    val listening: StateFlow<Boolean> = _listening.asStateFlow()

    /** Whether the switch is on for the running trip (the mic may still be refused). */
    fun switchedOn(): Boolean = sessions.active.value?.trip?.id?.let(switch::isOn) == true

    /** Setup's switch was on for the trip that just started: cues on, speech engine warming up. */
    fun enable(tripId: String) {
        refreshPermission()
        switch.enable(tripId)
        runCatching(warmUp).onFailure { log.log("speech warm-up failed", it) }
    }

    /** The indicator's one tap: off for the rest of this trip, and what was buffered is forgotten. */
    fun turnOff() {
        val tripId = sessions.active.value?.trip?.id ?: return
        switch.turnOff(tripId)
        ring.clear()
        log.log("cues turned off for the trip", null)
    }

    /** Call after a permission prompt: a grant made now lets the mic open. */
    fun refreshPermission() {
        permitted.value = mic.permitted()
    }

    /** Starts the mic loop, the card's timeout and the SOS clip, for as long as [scope] lives. */
    fun watch(scope: CoroutineScope): Job = scope.launch {
        hitScope = this
        launch {
            combine(sessions.active, switch.state, permitted, micFailedFor) { active, sw, ok, failed ->
                val id = active?.trip?.id
                id != null && sw?.tripId == id && sw.on && ok && failed != id
            }.distinctUntilChanged().collect { _listening.value = it }
        }
        launch {
            listening.collectLatest { on ->
                if (on) {
                    runMic()
                } else {
                    // Nothing heard before "off" may end up in a later clip.
                    ring.clear()
                    mutex.withLock {
                        meter.reset()
                        rule.resetLoudness()
                    }
                }
            }
        }
        launch {
            while (isActive) {
                delay(config.tick)
                tick()
            }
        }
        launch { sos.fired.collect { onSos(it) } }
    }

    private suspend fun runMic() {
        val tripId = sessions.active.value?.trip?.id ?: return
        log.log("mic on", null)
        val hits = hitScope
        speech?.start { cue -> hits?.launch { onWordHit(cue) } }
        try {
            mic.chunks().collect { onChunk(it) }
        } catch (e: CancellationException) {
            log.log("mic off", null)
            throw e
        } catch (e: Exception) {
            log.log("mic failed; cues off for this trip", e)
            micFailedFor.value = tripId
        } finally {
            speech?.stop()
        }
    }

    internal suspend fun onChunk(chunk: ShortArray) = mutex.withLock {
        val active = sessions.active.value ?: return@withLock
        syncTrip(active)
        ring.write(chunk)
        speech?.onChunk(chunk)
        for (db in meter.add(chunk)) {
            val base = baseline.median()
            val loud = baseline.onWindow(db)
            if (config.logLevels) log.log("window ${db.roundToInt()} dBFS, baseline ${base?.roundToInt()}, loud=$loud", null)
            speech?.onWindow(loud) { ring.tail(config.preRollSamples) }
            rule.onWindow(loud, clock.now())?.let { promptLocked(it) }
        }
    }

    /** Stage 2 heard a distress phrase or profanity. Only the cue is passed in; words never leave stage 2. */
    suspend fun onWordHit(cue: CueKind) = mutex.withLock {
        val active = sessions.active.value ?: return@withLock
        if (!_listening.value) return@withLock
        syncTrip(active)
        rule.onWordHit(cue, clock.now())?.let { promptLocked(it) }
    }

    /** The card's 괜찮아요 / SOS. SOS is then a normal on-screen SOS. */
    suspend fun answer(answer: CheckInAnswer): Result<EventRecord> {
        require(answer != CheckInAnswer.NO_RESPONSE) { "no_response comes from the timeout" }
        val logged = mutex.withLock {
            val active = sessions.active.value
                ?: return@withLock Result.failure(IllegalStateException("no trip running"))
            syncTrip(active)
            sessions.log(TripEvent.CheckInAnswered(answer, CueTexts.answered(answer, config)))
                .onSuccess { rule.answered(it.occurredAt) }
                .onFailure { log.log("check-in answer not stored", it) }
        }
        if (logged.isSuccess && answer == CheckInAnswer.SOS) sos.fire(SosSource.ON_SCREEN)
        return logged
    }

    internal suspend fun tick() = mutex.withLock {
        val active = sessions.active.value ?: return@withLock
        syncTrip(active)
        val now = clock.now()
        if (!rule.timedOut(now)) return@withLock
        // Closed either way, so a refused event can't make the timeout fire every second.
        rule.answered(now)
        sessions.log(TripEvent.CheckInAnswered(CheckInAnswer.NO_RESPONSE, CueTexts.answered(CheckInAnswer.NO_RESPONSE, config)))
            .onFailure { log.log("check-in timeout not stored", it) }
    }

    private suspend fun promptLocked(prompt: CheckInPrompt) {
        val active = sessions.active.value ?: return
        if (active.state.sosActive) return // the SOS screen is up; asking now would be noise
        sessions.log(TripEvent.CheckInPrompted(prompt.level, prompt.cue, CueTexts.prompted(prompt, config)))
            .onSuccess {
                rule.shown(it.occurredAt)
                log.log("check-in level ${prompt.level} (${prompt.cue.wire})", null)
            }
            .onFailure { log.log("check-in prompt not stored", it) }
    }

    /**
     * A new trip starts every rule from scratch. A trip resumed with a card still showing picks up the card's
     * time from the log, so the timeout still closes it.
     */
    private suspend fun syncTrip(active: ActiveTrip) {
        val id = active.trip.id
        if (id == ruleTripId) return
        ruleTripId = id
        rule.reset()
        baseline.reset()
        meter.reset()
        ring.clear()
        if (active.state.checkInPending) {
            val shownAt = sessions.events(id).getOrNull()
                ?.lastOrNull { it.event.type == EventType.CHECK_IN_PROMPTED }?.occurredAt
            rule.shown(shownAt ?: clock.now())
        }
    }

    private suspend fun onSos(record: EventRecord) {
        val tripId = sessions.active.value?.trip?.id ?: return
        if (!switch.isOn(tripId)) return
        val pcm = ring.snapshot()
        if (pcm.isEmpty()) return
        withContext(io) { clips.save(tripId, record.seq, pcm) }
            .onSuccess { log.log("SOS clip saved (${pcm.size / config.sampleRate} s)", null) }
            .onFailure { log.log("SOS clip not saved", it) }
    }
}
