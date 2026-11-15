package com.coral.gwigaon.cues

import com.coral.gwigaon.sos.SosController
import com.coral.gwigaon.trip.CheckInAnswer
import com.coral.gwigaon.trip.CueKind
import com.coral.gwigaon.trip.EndReason
import com.coral.gwigaon.trip.FakeTripStore
import com.coral.gwigaon.trip.MonitoringConfig
import com.coral.gwigaon.trip.PinHasher
import com.coral.gwigaon.trip.SosSource
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
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** A mic that records whether it is open; tests push audio through [CueMonitor.onChunk]. */
private class FakeMic(var granted: Boolean = true) : MicSource {
    var open = 0
    var opens = 0
    var failOnOpen = false

    override fun permitted() = granted

    override fun chunks(): Flow<ShortArray> = flow {
        check(!failOnOpen) { "mic busy" }
        open++
        opens++
        try {
            awaitCancellation()
        } finally {
            open--
        }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class CueMonitorTest {
    @get:Rule val tmp = TemporaryFolder()

    private val store = FakeTripStore()
    private val clock = TestClock()
    private var ids = 0
    private val sessions = TripSessionRepository(store, PinHasher(iterations = 1_000), clock) { "trip-${++ids}" }
    private val sos = SosController(sessions, clock, MonitoringConfig.DEFAULT)
    private val mic = FakeMic()
    private val config = CueConfig.DEFAULT
    private val clipDir by lazy { File(tmp.root, "clips") }
    private val switchFile by lazy { File(tmp.root, "cues") }
    private var warmUps = 0
    private val logged = mutableListOf<Pair<String, Throwable?>>()
    private val setup = TripSetup(Destination("집", 37.5, 127.0), "언니", "2580")

    private fun TestScope.monitor(switch: CueSwitch = CueSwitch(switchFile)) = CueMonitor(
        sessions, sos, mic, switch, ClipStore(clipDir, config.sampleRate), clock, config,
        log = { m, e -> logged += m to e }, warmUp = { warmUps++ },
        io = UnconfinedTestDispatcher(testScheduler),
    )

    private fun TestScope.watching(m: CueMonitor) = m.also {
        it.watch(CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler)))
    }

    private fun events(tripId: String = "trip-1") = store.events[tripId].orEmpty().map { it.event }

    private fun window(amplitude: Short) = ShortArray(config.samplesPerWindow) { if (it % 2 == 0) amplitude else (-amplitude).toShort() }

    /** 10 quiet windows (about -56 dBFS) for the baseline, then [loud] windows at about -21 dBFS, 1 s apart. */
    private suspend fun shout(m: CueMonitor, loud: Int, quiet: Int = 10) {
        repeat(quiet) {
            clock.advance(1.seconds)
            m.onChunk(window(50))
        }
        repeat(loud) {
            clock.advance(1.seconds)
            m.onChunk(window(3000))
        }
    }

    @Test
    fun offByDefaultTheMicStaysClosed() = runTest {
        val m = watching(monitor())
        sessions.start(setup).getOrThrow()
        runCurrent()
        assertFalse(m.listening.value)
        assertFalse(m.switchedOn())
        assertEquals(0, mic.opens)
    }

    @Test
    fun switchingOnOpensTheMicAndWarmsUpOneTapOffReleasesIt() = runTest {
        val m = watching(monitor())
        val trip = sessions.start(setup).getOrThrow()
        m.enable(trip.trip.id)
        runCurrent()
        assertTrue(m.listening.value)
        assertTrue(m.switchedOn())
        assertEquals(1, mic.open)
        assertEquals(1, warmUps)

        m.turnOff()
        runCurrent()
        assertFalse(m.listening.value)
        assertEquals(0, mic.open)
    }

    @Test
    fun theMicIsReleasedWhenTheTripEnds() = runTest {
        val m = watching(monitor())
        val trip = sessions.start(setup).getOrThrow()
        m.enable(trip.trip.id)
        runCurrent()
        assertEquals(1, mic.open)

        sessions.endByPassenger("2580").getOrThrow()
        runCurrent()
        assertFalse(m.listening.value)
        assertEquals(0, mic.open)
        assertTrue(events().last() is TripEvent.TripEnded)
    }

    @Test
    fun withoutTheMicPermissionNothingListensUntilItIsGranted() = runTest {
        mic.granted = false
        val m = watching(monitor())
        val trip = sessions.start(setup).getOrThrow()
        m.enable(trip.trip.id)
        runCurrent()
        assertFalse(m.listening.value)

        mic.granted = true
        m.refreshPermission()
        runCurrent()
        assertTrue(m.listening.value)
    }

    @Test
    fun aMicThatFailsToOpenTurnsCuesOffForTheTrip() = runTest {
        mic.failOnOpen = true
        val m = watching(monitor())
        val trip = sessions.start(setup).getOrThrow()
        m.enable(trip.trip.id)
        runCurrent()
        assertFalse(m.listening.value)
        assertTrue(logged.any { it.second != null })
    }

    @Test
    fun tenLoudSecondsPromptACheckInThatTheCardAnswers() = runTest {
        val m = watching(monitor())
        val trip = sessions.start(setup).getOrThrow()
        m.enable(trip.trip.id)
        runCurrent()

        shout(m, loud = 10)

        val prompt = events().last()
        assertEquals(TripEvent.CheckInPrompted(1, CueKind.LOUD, "큰 소리가 10초 이상 이어짐"), prompt)
        assertTrue(sessions.active.value!!.state.checkInPending)

        m.answer(CheckInAnswer.OK).getOrThrow()
        assertEquals(TripEvent.CheckInAnswered(CheckInAnswer.OK, "기사가 괜찮다고 응답"), events().last())
        assertFalse(sessions.active.value!!.state.checkInPending)
        assertFalse("loudness never writes audio", clipDir.exists() && clipDir.list()!!.isNotEmpty())
    }

    @Test
    fun levelsAreLoggedOnlyWhenAskedAndNeverAudio() = runTest {
        val quietLog = watching(monitor())
        val trip = sessions.start(setup).getOrThrow()
        quietLog.enable(trip.trip.id)
        shout(quietLog, loud = 1, quiet = 1)
        assertFalse(logged.any { it.first.startsWith("window") })

        val verbose = CueMonitor(
            sessions, sos, mic, CueSwitch(switchFile), ClipStore(clipDir, config.sampleRate), clock,
            config.copy(logLevels = true), log = { m, e -> logged += m to e },
        )
        verbose.onChunk(window(50))
        assertEquals("window -56 dBFS, baseline null, loud=false", logged.last().first)
    }

    @Test
    fun anUnansweredCardTimesOutAsNoResponse() = runTest {
        val m = watching(monitor())
        val trip = sessions.start(setup).getOrThrow()
        m.enable(trip.trip.id)
        runCurrent()
        shout(m, loud = 10)

        clock.advance(29.seconds)
        m.tick()
        assertTrue(events().last() is TripEvent.CheckInPrompted)
        clock.advance(1.seconds)
        m.tick()
        assertEquals(TripEvent.CheckInAnswered(CheckInAnswer.NO_RESPONSE, "30초 동안 응답 없음"), events().last())
        m.tick()
        assertEquals(1, events().count { it is TripEvent.CheckInAnswered })
    }

    @Test
    fun theTimeoutTickerRunsOnItsOwn() = runTest {
        val m = watching(monitor())
        val trip = sessions.start(setup).getOrThrow()
        m.enable(trip.trip.id)
        runCurrent()
        shout(m, loud = 10)
        clock.advance(31.seconds)
        advanceTimeBy(config.tick * 2)
        assertTrue(events().last() is TripEvent.CheckInAnswered)
    }

    @Test
    fun sosFromTheCardIsANormalSosAndSavesTheClipOnce() = runTest {
        val m = watching(monitor())
        val trip = sessions.start(setup).getOrThrow()
        m.enable(trip.trip.id)
        runCurrent()
        shout(m, loud = 10)

        m.answer(CheckInAnswer.SOS).getOrThrow()
        runCurrent()

        val tail = events().takeLast(2)
        assertEquals(TripEvent.CheckInAnswered(CheckInAnswer.SOS, "상태 확인에서 SOS 선택"), tail[0])
        assertTrue(tail[1] is TripEvent.Sos && (tail[1] as TripEvent.Sos).source == SosSource.ON_SCREEN)
        val clips = clipDir.listFiles()!!.toList()
        assertEquals(1, clips.size)
        val sosSeq = store.events["trip-1"]!!.last().seq
        assertEquals("sos-trip-1-$sosSeq.wav", clips.single().name)
        assertEquals(44 + 20 * config.samplesPerWindow * 2L, clips.single().length()) // 20 s heard so far
    }

    @Test
    fun anSosWithCuesOffWritesNoClip() = runTest {
        val m = watching(monitor())
        val trip = sessions.start(setup).getOrThrow()
        m.enable(trip.trip.id)
        runCurrent()
        shout(m, loud = 3)
        m.turnOff()
        runCurrent()

        sos.fire(SosSource.ON_SCREEN).getOrThrow()
        runCurrent()
        assertFalse(clipDir.exists() && clipDir.list()!!.isNotEmpty())
    }

    @Test
    fun noPromptWhileTheSosScreenIsUp() = runTest {
        val m = watching(monitor())
        val trip = sessions.start(setup).getOrThrow()
        m.enable(trip.trip.id)
        runCurrent()
        sos.fire(SosSource.ON_SCREEN).getOrThrow()
        shout(m, loud = 10)
        assertEquals(0, events().count { it is TripEvent.CheckInPrompted })
    }

    @Test
    fun wordHitsPromptOnlyWhileListening() = runTest {
        val m = watching(monitor())
        val trip = sessions.start(setup).getOrThrow()
        m.onWordHit(CueKind.DISTRESS)
        assertEquals(0, events().count { it is TripEvent.CheckInPrompted })

        m.enable(trip.trip.id)
        runCurrent()
        m.onWordHit(CueKind.DISTRESS)
        assertEquals(TripEvent.CheckInPrompted(1, CueKind.DISTRESS, "도움을 청하는 말이 들림"), events().last())
    }

    @Test
    fun aRepeatWithinTwoMinutesIsLevelTwoEvenInTheCooldown() = runTest {
        val m = watching(monitor())
        val trip = sessions.start(setup).getOrThrow()
        m.enable(trip.trip.id)
        runCurrent()
        shout(m, loud = 10)
        m.answer(CheckInAnswer.OK).getOrThrow()
        shout(m, loud = 10, quiet = 20)
        assertEquals(TripEvent.CheckInPrompted(2, CueKind.LOUD, "반복 감지 · 큰 소리가 10초 이상 이어짐"), events().last())
    }

    @Test
    fun answeringWithoutACardIsRefused() = runTest {
        val m = monitor()
        assertTrue(m.answer(CheckInAnswer.OK).isFailure)
        sessions.start(setup).getOrThrow()
        assertTrue(m.answer(CheckInAnswer.OK).isFailure)
    }

    @Test(expected = IllegalArgumentException::class)
    fun noResponseIsNotAnAnswerTheCardGives() = runTest {
        monitor().answer(CheckInAnswer.NO_RESPONSE)
    }

    @Test
    fun aResumedTripWithACardShowingStillTimesOut() = runTest {
        val first = watching(monitor())
        val trip = sessions.start(setup).getOrThrow()
        first.enable(trip.trip.id)
        runCurrent()
        shout(first, loud = 10)

        // Process restart: a new monitor over the same trip and switch file.
        val second = monitor()
        clock.advance(30.seconds)
        second.tick()
        assertEquals(TripEvent.CheckInAnswered(CheckInAnswer.NO_RESPONSE, "30초 동안 응답 없음"), events().last())
        assertTrue(CueSwitch(switchFile).isOn(trip.trip.id))
    }

    @Test
    fun aNewTripStartsWithCuesOffAndAFreshBaseline() = runTest {
        val m = watching(monitor())
        val first = sessions.start(setup).getOrThrow()
        m.enable(first.trip.id)
        runCurrent()
        shout(m, loud = 9)
        sessions.endByPassenger("2580").getOrThrow()
        sessions.start(setup).getOrThrow()
        runCurrent()
        assertFalse(m.listening.value)
        m.enable("trip-2")
        runCurrent()
        shout(m, loud = 1, quiet = 0) // no baseline yet on the new trip, and the old run is gone
        assertEquals(0, events("trip-2").count { it is TripEvent.CheckInPrompted })
        assertTrue(events("trip-1").last().let { it is TripEvent.TripEnded && it.endReason == EndReason.ENDED_BY_PASSENGER })
    }
}

class CueSwitchTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun choiceSurvivesARestartAndOffHoldsForTheRestOfTheTrip() {
        val file = File(tmp.root, "cues")
        val switch = CueSwitch(file)
        assertFalse(switch.isOn("a"))
        switch.enable("a")
        assertTrue(CueSwitch(file).isOn("a"))
        switch.turnOff("b") // another trip: ignored
        assertTrue(switch.isOn("a"))
        switch.turnOff("a")
        assertFalse(CueSwitch(file).isOn("a"))
        assertEquals(Listening("a", on = false), CueSwitch(file).state.value)
    }

    @Test
    fun aMissingOrBrokenFileMeansOff() {
        val logged = mutableListOf<Throwable?>()
        val file = File(tmp.root, "cues").apply { writeText("a\nmaybe\n") }
        assertFalse(CueSwitch(file) { _, e -> logged += e }.isOn("a"))
        assertTrue(logged.single() != null)
        assertFalse(CueSwitch(File(tmp.root, "none")).isOn("a"))
    }

    @Test
    fun withoutAFileTheChoiceHoldsInMemory() {
        val switch = CueSwitch(null)
        switch.enable("a")
        assertTrue(switch.isOn("a"))
    }

    @Test
    fun anUnwritableFileIsReportedAndTheChoiceStillHolds() {
        val logged = mutableListOf<Throwable?>()
        val dirAsFile = tmp.newFolder("blocked")
        val switch = CueSwitch(dirAsFile) { _, e -> logged += e }
        switch.enable("a")
        assertTrue(switch.isOn("a"))
        assertTrue(logged.isNotEmpty())
    }
}

class ClipStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun savesOneWavPerSosInItsFolder() {
        val store = ClipStore(File(tmp.root, "clips"), 16_000)
        val file = store.save("trip-1", 7, shortArrayOf(1, 2, 3)).getOrThrow()
        assertEquals("sos-trip-1-7.wav", file.name)
        assertEquals(50L, file.length())
        assertEquals(listOf(file), store.list())
    }

    @Test
    fun refusesEmptyAudioAndOddTripIds() {
        val store = ClipStore(File(tmp.root, "clips"), 16_000)
        assertTrue(store.save("trip-1", 1, ShortArray(0)).isFailure)
        assertTrue(store.save("../x", 1, shortArrayOf(1)).isFailure)
        assertEquals(emptyList<File>(), store.list())
    }

    @Test
    fun purgeDeletesOnlyClipsOlderThanTheCutoff() {
        val dir = File(tmp.root, "clips")
        val store = ClipStore(dir, 16_000)
        val old = store.save("trip-1", 1, shortArrayOf(1)).getOrThrow()
        val fresh = store.save("trip-2", 1, shortArrayOf(1)).getOrThrow()
        val other = File(dir, "notes.txt").apply { writeText("x") }
        val cutoff = Instant.fromEpochMilliseconds(2_000_000_000_000)
        assertTrue(old.setLastModified(cutoff.toEpochMilliseconds() - 1_000))
        assertTrue(other.setLastModified(cutoff.toEpochMilliseconds() - 1_000))
        assertTrue(fresh.setLastModified(cutoff.toEpochMilliseconds() + 1_000))

        assertEquals(1, store.purgeOlderThan(cutoff).getOrThrow())
        assertFalse(old.exists())
        assertTrue(fresh.exists())
        assertTrue(other.exists())
        assertEquals(0, store.purgeOlderThan(cutoff).getOrThrow())
    }

    @Test
    fun purgeOfAMissingFolderDeletesNothing() {
        val store = ClipStore(File(tmp.root, "never-made"), 16_000)
        assertEquals(0, store.purgeOlderThan(Instant.fromEpochMilliseconds(Long.MAX_VALUE / 2)).getOrThrow())
    }

    @Test
    fun clipsAreKeptThirtyDaysByDefault() {
        assertEquals(30.days, CueConfig.DEFAULT.clipRetention)
    }
}

class CueTextsTest {
    @Test
    fun reasonsNameTheCueNeverTheWords() {
        assertEquals("거친 말이 들림", CueTexts.prompted(CheckInPrompt(1, CueKind.PROFANITY)))
        assertEquals("반복 감지 · 도움을 청하는 말이 들림", CueTexts.prompted(CheckInPrompt(2, CueKind.DISTRESS)))
    }
}
