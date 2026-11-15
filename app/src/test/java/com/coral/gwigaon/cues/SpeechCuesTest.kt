package com.coral.gwigaon.cues

import com.coral.gwigaon.sos.SosController
import com.coral.gwigaon.trip.CueKind
import com.coral.gwigaon.trip.FakeTripStore
import com.coral.gwigaon.trip.MonitoringConfig
import com.coral.gwigaon.trip.PinHasher
import com.coral.gwigaon.trip.TestClock
import com.coral.gwigaon.trip.TripEvent
import com.coral.gwigaon.trip.TripSessionRepository
import com.coral.gwigaon.trip.TripSetup
import com.coral.gwigaon.vehicle.Destination
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class WordMatcherTest {
    private val matcher = WordMatcher()

    @Test
    fun aPartialHitCountsAndTheFinalDoesNotCountItAgain() {
        assertNull(matcher.onResult("살려", false))
        assertEquals(CueKind.DISTRESS, matcher.onResult("살려주세요", false))
        assertNull(matcher.onResult("살려주세요 제발", false))
        assertNull(matcher.onResult("살려주세요 제발", true))
        assertEquals("the next utterance counts again", CueKind.DISTRESS, matcher.onResult("하지마", true))
    }

    @Test
    fun aFinalThatRewritesTheWordKeepsThePartialsHit() {
        // Seen in the spike: the partial had it right, the final rewrote it.
        assertEquals(CueKind.PROFANITY, matcher.onResult("개새끼야", false))
        assertNull(matcher.onResult("제습기가 차 세", true))
    }

    @Test
    fun aWordOnlyInTheFinalStillCounts() {
        assertNull(matcher.onResult("제가", false))
        assertEquals(CueKind.PROFANITY, matcher.onResult("미친놈아 꺼져", true))
    }

    @Test
    fun spacingAndPunctuationAreIgnored() {
        assertEquals(CueKind.DISTRESS, matcher.onResult("하지 마!", true))
        assertEquals(CueKind.DISTRESS, matcher.onResult("멈, 춰요.", true))
        assertEquals(CueKind.PROFANITY, matcher.onResult("개 새 끼", true))
        assertEquals("하지마", WordMatcher.normalise(" 하지\t마?! "))
    }

    @Test
    fun ordinaryTripTalkDoesNotMatch() {
        listOf(
            "안녕하세요 양재역으로 가 주세요", "여기서 내려주세요", "하지만 길이 막히네요", "미친 듯이 맛있어요",
            "시간 진짜 왜 이렇게 가는 거야", "", "   ",
        ).forEach { assertNull(it, matcher.onResult(it, true)) }
    }

    @Test
    fun distressWinsOverProfanityInOneUtterance() {
        assertEquals(CueKind.DISTRESS, matcher.onResult("개새끼야 살려주세요", true))
    }

    @Test
    fun resetStartsANewUtterance() {
        assertEquals(CueKind.DISTRESS, matcher.onResult("경찰 불러", false))
        matcher.reset()
        assertEquals(CueKind.DISTRESS, matcher.onResult("경찰 불러", false))
    }
}

class StageTwoSchedulerTest {
    private val config = CueConfig.DEFAULT
    private val clock = TestClock()
    private val scheduler = StageTwoScheduler(config)

    private fun modesFor(seconds: Int, step: Duration = 1.seconds): List<StageTwoMode> = (0 until seconds).map {
        scheduler.mode(clock.now()).also { clock.advance(step) }
    }

    @Test
    fun idleBeforeItStarts() {
        assertEquals(StageTwoMode.IDLE, scheduler.mode(clock.now()))
    }

    @Test
    fun aQuietTripSamplesTenSecondsEveryMinute() {
        scheduler.reset(clock.now())
        val modes = modesFor(180)
        assertEquals(30, modes.count { it == StageTwoMode.SAMPLING })
        assertTrue(modes.subList(0, 10).all { it == StageTwoMode.SAMPLING })
        assertTrue(modes.subList(10, 60).all { it == StageTwoMode.IDLE })
        assertTrue(modes.subList(60, 70).all { it == StageTwoMode.SAMPLING })
        assertFalse(StageTwoMode.STREAMING in modes)
    }

    @Test
    fun aLoudWindowWakesItAtOnceAndItPersistsUntilACalmPeriodPasses() {
        scheduler.reset(clock.now())
        modesFor(20)
        assertEquals(StageTwoMode.IDLE, scheduler.mode(clock.now()))

        scheduler.onLoud(clock.now())
        assertEquals(StageTwoMode.STREAMING, scheduler.mode(clock.now()))

        clock.advance(50.seconds)
        scheduler.onHit(clock.now()) // a word keeps it awake
        clock.advance(59.seconds)
        assertEquals(StageTwoMode.STREAMING, scheduler.mode(clock.now()))

        clock.advance(1.seconds)
        assertEquals("calm: back to sampling", StageTwoMode.IDLE, scheduler.mode(clock.now()))
        clock.advance(59.seconds)
        assertEquals(StageTwoMode.IDLE, scheduler.mode(clock.now()))
        clock.advance(1.seconds)
        assertEquals("the next sample a full minute after the calm", StageTwoMode.SAMPLING, scheduler.mode(clock.now()))
    }

    @Test
    fun aHitDuringASampleTurnsItIntoAStream() {
        scheduler.reset(clock.now())
        assertEquals(StageTwoMode.SAMPLING, scheduler.mode(clock.now()))
        clock.advance(5.seconds)
        scheduler.onHit(clock.now())
        clock.advance(30.seconds)
        assertEquals(StageTwoMode.STREAMING, scheduler.mode(clock.now()))
    }

    @Test
    fun resetForgetsAStream() {
        scheduler.reset(clock.now())
        scheduler.onLoud(clock.now())
        scheduler.reset(clock.now())
        assertEquals(StageTwoMode.SAMPLING, scheduler.mode(clock.now()))
        clock.advance(10.seconds)
        assertEquals(StageTwoMode.IDLE, scheduler.mode(clock.now()))
    }
}

/** Records what reaches the engine; tests play its results back through [listener]. */
class FakeSpeechEngine(private val failWarmUp: Boolean = false) : SpeechEngine {
    lateinit var listener: SpeechListener
    var warmUps = 0
    val sent = mutableListOf<ShortArray>()
    var completes = 0

    val sentSamples: Int get() = sent.sumOf { it.size }

    override fun listen(listener: SpeechListener) {
        this.listener = listener
    }

    override fun warmUp() {
        warmUps++
        check(!failWarmUp) { "STT service missing" }
    }

    override fun send(pcm: ShortArray) {
        sent += pcm
    }

    override fun complete() {
        completes++
    }
}

class SpeechCuesTest {
    private val config = CueConfig.DEFAULT
    private val clock = TestClock()
    private val engine = FakeSpeechEngine()
    private val logged = mutableListOf<String>()
    private val cues = SpeechCues(engine, clock, config) { m, _ -> logged += m }
    private val hits = mutableListOf<CueKind>()
    private val second = ShortArray(config.sampleRate)

    /** [seconds] of audio in 1 s chunks, each followed by its window's verdict. */
    private fun play(seconds: Int, loud: Boolean = false) = repeat(seconds) {
        cues.onChunk(second)
        cues.onWindow(loud) { ShortArray(config.preRollSamples) }
        clock.advance(1.seconds)
    }

    @Test
    fun nothingGoesOutBeforeStart() {
        play(5, loud = true)
        assertEquals(0, engine.sentSamples)
    }

    @Test
    fun aQuietTripOnlySamples() {
        cues.start { hits += it }
        play(180)
        assertEquals("10 s per minute", 30 * config.sampleRate, engine.sentSamples)
        assertEquals("each sample completed", 3, engine.completes)
        assertTrue(hits.isEmpty())
    }

    @Test
    fun aLoudWindowWakesItWithThePreRollAndItStreamsUntilCalm() {
        cues.start { hits += it }
        play(20)
        val before = engine.sentSamples

        play(1, loud = true)
        assertEquals("pre-roll, the loud chunk included", before + config.preRollSamples, engine.sentSamples)

        play(59)
        assertEquals(before + config.preRollSamples + 59 * config.sampleRate, engine.sentSamples)
        val completes = engine.completes
        play(1)
        assertEquals("calm: the stream is completed", completes + 1, engine.completes)
    }

    @Test
    fun aWordReportsItsCueOnceAndKeepsTheStreamGoing() {
        cues.start { hits += it }
        play(5)
        engine.listener.onText("살려주세요", false)
        engine.listener.onText("살려주세요", true)
        assertEquals(listOf(CueKind.DISTRESS), hits)

        play(60) // the sample would have ended after 10 s; the hit keeps it streaming for a calm period
        assertEquals(65 * config.sampleRate, engine.sentSamples)
    }

    @Test
    fun onePhraseIsOneCueAndAnAbortedSessionStartsANewUtterance() {
        cues.start { hits += it }
        // The order the SDK used on the emulator: partials, then the end, then the final.
        engine.listener.onText("살려", false)
        engine.listener.onText("살려주세요", false)
        engine.listener.onText("살려주세요", true)
        assertEquals(listOf(CueKind.DISTRESS), hits)

        engine.listener.onText("꺼져", false)
        engine.listener.onAborted()
        engine.listener.onText("꺼져", false)
        assertEquals(listOf(CueKind.DISTRESS, CueKind.PROFANITY, CueKind.PROFANITY), hits)
    }

    @Test
    fun noWordTextEverReachesTheLog() {
        cues.start { hits += it }
        play(3)
        val said = listOf("개새끼야 차 세워", "살려주세요", "여기서 내려주세요")
        said.forEach { engine.listener.onText(it, true) }
        assertEquals(listOf(CueKind.PROFANITY, CueKind.DISTRESS), hits)
        assertTrue(logged.contains("stage 2 word cue profanity"))
        val words = said.flatMap { it.split(" ") } + said.map(WordMatcher::normalise)
        logged.forEach { line -> words.forEach { assertFalse("'$line' leaks text", line.contains(it)) } }
    }

    @Test
    fun startWarmsTheEngineUpEvenWhenItFails() {
        cues.start { hits += it }
        assertEquals(1, engine.warmUps)

        val broken = FakeSpeechEngine(failWarmUp = true)
        val brokenCues = SpeechCues(broken, clock, config) { m, _ -> logged += m }
        brokenCues.start { hits += it }
        assertTrue(logged.contains("speech warm-up failed"))
        brokenCues.onChunk(second)
        assertEquals("audio still goes to the engine, which drops it while down", config.sampleRate, broken.sentSamples)
    }

        @Test
    fun stopCompletesAndSilencesIt() {
        cues.start { hits += it }
        play(3)
        cues.stop()
        assertEquals(1, engine.completes)
        play(70, loud = true)
        engine.listener.onText("살려주세요", true)
        assertEquals(3 * config.sampleRate, engine.sentSamples)
        assertTrue(hits.isEmpty())

        cues.stop()
        assertEquals("nothing open, nothing to complete", 1, engine.completes)
    }
}

/** A mic that stays open until cancelled; tests push audio through [CueMonitor.onChunk]. */
private class OpenMic : MicSource {
    override fun permitted() = true
    override fun chunks(): Flow<ShortArray> = flow { awaitCancellation() }
}

@OptIn(ExperimentalCoroutinesApi::class)
class CueMonitorStageTwoTest {
    @get:Rule val tmp = TemporaryFolder()

    private val store = FakeTripStore()
    private val clock = TestClock()
    private var ids = 0
    private val sessions = TripSessionRepository(store, PinHasher(iterations = 1_000), clock) { "trip-${++ids}" }
    private val sos = SosController(sessions, clock, MonitoringConfig.DEFAULT)
    private val config = CueConfig.DEFAULT
    private val logged = mutableListOf<String>()
    private val setup = TripSetup(Destination("집", 37.5, 127.0), "언니", "2580")

    private fun TestScope.monitor(engine: FakeSpeechEngine) = CueMonitor(
        sessions, sos, OpenMic(), CueSwitch(null), ClipStore(tmp.root, config.sampleRate), clock, config,
        log = { m, _ -> logged += m },
        speech = SpeechCues(engine, clock, config) { m, _ -> logged += m },
        io = UnconfinedTestDispatcher(testScheduler),
    ).also { it.watch(CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler))) }

    private fun window(amplitude: Short) = ShortArray(config.samplesPerWindow) { if (it % 2 == 0) amplitude else (-amplitude).toShort() }

    private fun prompts() = store.events["trip-1"].orEmpty().map { it.event }.filterIsInstance<TripEvent.CheckInPrompted>()

    @Test
    fun aWordFromTheEngineRaisesACheckInWithTheCueOnly() = runTest {
        val engine = FakeSpeechEngine()
        val m = monitor(engine)
        m.enable(sessions.start(setup).getOrThrow().trip.id)
        runCurrent()
        assertEquals("on the switch, and again as listening starts", 2, engine.warmUps)

        clock.advance(1.seconds)
        m.onChunk(window(50))
        assertEquals("the first sample streams stage 1's audio", config.samplesPerWindow, engine.sentSamples)

        engine.listener.onText("하지 마, 하지 말라고", true)
        runCurrent()
        assertEquals(listOf(TripEvent.CheckInPrompted(1, CueKind.DISTRESS, "도움을 청하는 말이 들림")), prompts())
        assertFalse(logged.any { "하지" in it })
    }

    @Test
    fun loudnessWakesStageTwoAndTheMicGoingOffStopsIt() = runTest {
        val engine = FakeSpeechEngine()
        val m = monitor(engine)
        m.enable(sessions.start(setup).getOrThrow().trip.id)
        runCurrent()
        repeat(20) {
            clock.advance(1.seconds)
            m.onChunk(window(50))
        }
        val beforeLoud = engine.sentSamples
        clock.advance(1.seconds)
        m.onChunk(window(3000))
        assertEquals(beforeLoud + config.preRollSamples, engine.sentSamples)

        m.turnOff()
        runCurrent()
        assertTrue(engine.completes >= 1)
        engine.listener.onText("살려주세요", true)
        runCurrent()
        assertTrue(prompts().isEmpty())
    }

    @Test
    fun withoutSpeechStageOneStillPromptsOnLoudness() = runTest {
        val engine = FakeSpeechEngine(failWarmUp = true)
        val m = monitor(engine)
        m.enable(sessions.start(setup).getOrThrow().trip.id)
        runCurrent()
        assertTrue(m.listening.value)
        assertTrue(logged.contains("speech warm-up failed"))

        repeat(10) {
            clock.advance(1.seconds)
            m.onChunk(window(50))
        }
        repeat(10) {
            clock.advance(1.seconds)
            m.onChunk(window(3000))
        }
        assertEquals(listOf(TripEvent.CheckInPrompted(1, CueKind.LOUD, "큰 소리가 10초 이상 이어짐")), prompts())
    }
}
