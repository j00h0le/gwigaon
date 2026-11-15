package com.coral.gwigaon.report

import com.coral.gwigaon.sync.CloudError
import com.coral.gwigaon.sync.FakeAuth
import com.coral.gwigaon.sync.MemorySessionStore
import com.coral.gwigaon.sync.NotableEvent
import com.coral.gwigaon.sync.SessionManager
import com.coral.gwigaon.sync.SummaryApi
import com.coral.gwigaon.sync.TripSummary
import com.coral.gwigaon.sync.offline
import com.coral.gwigaon.trip.ArrivalInstructions
import com.coral.gwigaon.trip.CheckInAnswer
import com.coral.gwigaon.trip.CueKind
import com.coral.gwigaon.trip.EventType
import com.coral.gwigaon.trip.SettingsField
import com.coral.gwigaon.trip.SosSource
import com.coral.gwigaon.trip.EndReason
import com.coral.gwigaon.trip.EventRecord
import com.coral.gwigaon.trip.PinAttempts
import com.coral.gwigaon.trip.SafeArrivalCloser
import com.coral.gwigaon.trip.StoredTrip
import com.coral.gwigaon.trip.TestClock
import com.coral.gwigaon.trip.TripEvent
import com.coral.gwigaon.trip.TripStatus
import com.coral.gwigaon.vehicle.Destination
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

private val SEOUL: ZoneId = ZoneId.of("Asia/Seoul")
private val T0 = Instant.parse("2026-11-11T12:03:00Z") // 21:03 in Seoul

private fun log(): List<EventRecord> = listOf(
    EventRecord(3, T0 + 12.minutes, 37.5, 127.0, TripEvent.LongStop(300, "8분째 정차 중이에요 (목적지 아님)")),
    EventRecord(1, T0, 37.5, 127.0, TripEvent.TripStarted()),
    EventRecord(2, T0 + 5.minutes, 37.5, 127.0, TripEvent.RouteDeviation(420, "안내 경로에서 420m 벗어났어요")),
    EventRecord(4, T0 + 38.minutes, null, null, TripEvent.TripEnded(EndReason.SAFE_ARRIVAL, "운행 종료")),
)

class ReportLogTest {
    private fun at(seq: Int, e: TripEvent) = EventRecord(seq, T0 + seq.minutes, null, null, e)

    @Test
    fun showsStartArrivalSafeArrivalCautionsAndSosOnly() {
        val shown = listOf(
            TripEvent.TripStarted(), TripEvent.Arrived(), TripEvent.TripEnded(EndReason.SAFE_ARRIVAL),
            TripEvent.RouteDeviation(1), TripEvent.LongStop(1), TripEvent.SafeArrivalCheckOpened(),
            TripEvent.MonitoringInterrupted(1), TripEvent.PinLockout(5, 60), TripEvent.CheckInAnswered(CheckInAnswer.NO_RESPONSE),
            TripEvent.TripEnded(EndReason.ENDED_EARLY), TripEvent.Sos(SosSource.TURN_SIGNAL), TripEvent.Sos(SosSource.ON_SCREEN),
            TripEvent.SosCancelled(),
        )
        val hidden = listOf(
            TripEvent.NearDestination(), TripEvent.CheckInPrompted(1, CueKind.LOUD), TripEvent.CheckInAnswered(CheckInAnswer.OK),
            // Its SOS is logged as its own event, which shows.
            TripEvent.CheckInAnswered(CheckInAnswer.SOS),
            TripEvent.SafeArrivalConfirmed(SafeArrivalCloser.DRIVER_CONFIRMED), TripEvent.SafeArrivalConfirmed(SafeArrivalCloser.SEAT_EMPTY),
            TripEvent.DeviationCleared(), TripEvent.LongStopCleared(), TripEvent.MonitoringResumed(),
            TripEvent.SettingsChanged(listOf(SettingsField.HOME)),
            TripEvent.TripEnded(EndReason.ENDED_AT_ARRIVAL), TripEvent.TripEnded(EndReason.ENDED_BY_PASSENGER),
        )
        shown.forEach { assertTrue("$it shows", ReportLog.shows(it)) }
        hidden.forEach { assertFalse("$it hidden", ReportLog.shows(it)) }
        // Every event type is decided by one of the two lists.
        assertEquals(EventType.entries.toSet(), (shown + hidden).map { it.type }.toSet())
    }

    @Test
    fun filterKeepsTheOrderAndDropsTheRest() {
        val log = listOf(
            at(1, TripEvent.TripStarted()), at(2, TripEvent.SettingsChanged(listOf(SettingsField.PIN))),
            at(3, TripEvent.Sos(SosSource.ON_SCREEN)), at(4, TripEvent.SosCancelled()), at(5, TripEvent.NearDestination()),
            at(6, TripEvent.Arrived()),
        )
        assertEquals(listOf(1, 3, 4, 6), ReportLog.filter(log).map { it.seq })
    }

    @Test
    fun outcomeComesFromTheTripEnd() {
        assertEquals(ReportOutcome.SAFE_ARRIVAL, ReportLog.outcome(log()))
        assertEquals(ReportOutcome.ENDED_EARLY, ReportLog.outcome(listOf(at(1, TripEvent.TripEnded(EndReason.ENDED_EARLY)))))
        assertEquals(ReportOutcome.ENDED_AT_ARRIVAL, ReportLog.outcome(listOf(at(1, TripEvent.TripEnded(EndReason.ENDED_AT_ARRIVAL)))))
        assertEquals(ReportOutcome.ENDED_BY_PASSENGER, ReportLog.outcome(listOf(at(1, TripEvent.TripEnded(EndReason.ENDED_BY_PASSENGER)))))
        assertEquals(ReportOutcome.UNKNOWN, ReportLog.outcome(listOf(at(1, TripEvent.TripStarted()))))
    }
}

class ReportTextsTest {
    @Test
    fun shortLogIsInSeqOrderWithLocalTimesLabelsAndReasons() {
        val log = log() + listOf(
            EventRecord(5, T0 + 9.minutes, null, null, TripEvent.DeviationCleared("경로로 돌아왔어요")),
            EventRecord(6, T0 + 30.minutes, null, null, TripEvent.NearDestination()),
        )
        val t = ReportTexts.shortLog(log, SEOUL)

        assertEquals(listOf(1, 2, 3, 4), t.map { it.seq })
        assertEquals(listOf("21:03", "21:08", "21:15", "21:41"), t.map { it.time })
        assertEquals(listOf("운행 시작", "경로 이탈", "장시간 정차", "안전 귀가"), t.map { it.label })
        assertEquals(listOf(ReportTone.NEUTRAL, ReportTone.WARN, ReportTone.WARN, ReportTone.OK), t.map { it.tone })
        assertEquals("안내 경로에서 420m 벗어났어요", t[1].reason)
        // A long stop says how long the car stood, from its duration, not the live wording in its reason.
        assertEquals("5분 동안 정차했어요", t[2].reason)
        // No reason, and a reason that only repeats the label, both show the label alone.
        assertNull(t[0].reason)
        assertNull(ReportTexts.shortLog(listOf(EventRecord(1, T0, null, null, TripEvent.TripEnded(EndReason.SAFE_ARRIVAL, "안전 귀가"))), SEOUL)[0].reason)
    }

    @Test
    fun stopDurationsAreWholeMinutesNeverSeconds() {
        assertEquals("1분 동안 정차했어요", ReportTexts.stopped(20))
        assertEquals("7분 동안 정차했어요", ReportTexts.stopped(7 * 60 + 59))
        assertEquals("1시간 5분 동안 정차했어요", ReportTexts.stopped(65 * 60))
    }

    @Test
    fun anEarlyEndIsACautionWithWhatWasLeft() {
        val end = EventRecord(9, T0, null, null, TripEvent.TripEnded(EndReason.ENDED_EARLY, "목적지 밖에서 운행 종료 · 1.2km 남음"))
        val item = ReportTexts.shortLog(listOf(end), SEOUL).single()
        assertEquals("목적지 밖에서 운행 종료", item.label)
        assertEquals("1.2km 남음", item.reason)
        assertEquals(ReportTone.WARN, item.tone)
    }

    @Test
    fun theArrivalCheckIsACautionOnTheReport() {
        assertEquals(ReportTone.WARN, ReportTexts.meta(TripEvent.SafeArrivalCheckOpened()).second)
        assertEquals("도착 후 운행 종료" to ReportTone.NEUTRAL, ReportTexts.meta(TripEvent.TripEnded(EndReason.ENDED_AT_ARRIVAL)))
    }

    @Test
    fun statusSaysHowTheTripEnded() {
        assertEquals("안전하게 귀가했어요", ReportTexts.status(ReportOutcome.SAFE_ARRIVAL))
        assertEquals("목적지 전에 운행이 끝났어요", ReportTexts.status(ReportOutcome.ENDED_EARLY))
        assertEquals("목적지에서 운행을 끝냈어요", ReportTexts.status(ReportOutcome.ENDED_AT_ARRIVAL))
        assertEquals("승객이 PIN으로 운행을 끝냈어요", ReportTexts.status(ReportOutcome.ENDED_BY_PASSENGER))
        assertEquals("운행이 끝났어요", ReportTexts.status(ReportOutcome.UNKNOWN))
    }

    @Test
    fun aDriversConfirmationIsACautionNotASafeArrival() {
        assertEquals("기사 확인 (하차 전)" to ReportTone.WARN, ReportTexts.meta(TripEvent.SafeArrivalConfirmed(SafeArrivalCloser.DRIVER_CONFIRMED)))
        assertEquals("안전 하차 확인" to ReportTone.OK, ReportTexts.meta(TripEvent.SafeArrivalConfirmed(SafeArrivalCloser.SEAT_EMPTY)))
    }

    @Test
    fun everyEventTypeHasAKoreanLabel() {
        val all = listOf(
            TripEvent.TripStarted(), TripEvent.RouteDeviation(1), TripEvent.DeviationCleared(), TripEvent.LongStop(1),
            TripEvent.LongStopCleared(), TripEvent.Sos(com.coral.gwigaon.trip.SosSource.ON_SCREEN), TripEvent.SosCancelled(),
            TripEvent.MonitoringInterrupted(1), TripEvent.MonitoringResumed(), TripEvent.NearDestination(), TripEvent.Arrived(),
            TripEvent.SafeArrivalCheckOpened(), TripEvent.CheckInPrompted(1, com.coral.gwigaon.trip.CueKind.LOUD),
            TripEvent.CheckInAnswered(com.coral.gwigaon.trip.CheckInAnswer.OK), TripEvent.PinLockout(5, 60),
            TripEvent.SettingsChanged(listOf(com.coral.gwigaon.trip.SettingsField.HOME)),
        )
        all.forEach { e -> assertTrue(e.type.name, ReportTexts.meta(e).first.any { it in '가'..'힣' }) }
        assertEquals(ReportTone.DANGER, ReportTexts.meta(TripEvent.Sos(com.coral.gwigaon.trip.SosSource.TURN_SIGNAL)).second)
    }

    @Test
    fun periodShowsStartEndAndLength() {
        assertEquals("21:03 → 21:41 · 38분", ReportTexts.period(T0, T0 + 38.minutes, SEOUL))
        assertEquals("21:03 시작", ReportTexts.period(T0, null, SEOUL))
        assertEquals("운행 기록 · 집", ReportTexts.kicker("집"))
    }
}

/** Answers from a script: each call takes the next answer (an exception is thrown), the last one repeats. */
private class ScriptedSummaryApi(vararg answers: Any) : SummaryApi {
    private val script = ArrayDeque(answers.toList())
    val tokens = mutableListOf<String>()

    override suspend fun summarizeTrip(accessToken: String, tripId: String): TripSummary {
        tokens += accessToken
        val next = if (script.size > 1) script.removeFirst() else script.first()
        if (next is Exception) throw next
        return next as TripSummary
    }
}

private val summary = TripSummary(
    "21시 3분에 출발해 한 번 경로를 벗어났고 21시 41분에 안전하게 도착했어요.",
    listOf(1, 2, 4),
    listOf(NotableEvent(2, "21:08에 안내 경로를 420m 벗어났어요."), NotableEvent(99, "알 수 없는 이벤트")),
    listOf("정차 이유는 기록에 없어요."),
    "claude-sonnet-5-5",
)

private fun cloud(api: SummaryApi, auth: FakeAuth = FakeAuth()) =
    SummaryCloud(SessionManager(auth, MemorySessionStore(), TestClock(Instant.parse("2026-11-11T09:00:00Z"))), api)

private fun repo(api: SummaryApi?, auth: FakeAuth = FakeAuth()) =
    ReportRepository({ Result.success(log()) }, api?.let { cloud(it, auth) }, ReportConfig(attempts = 3, wait = 3.seconds))

class ReportRepositoryTest {
    @Test
    fun noCloudMeansNoAiPart() = runTest {
        assertEquals(SummaryOutcome.NoCloud, repo(null).summary("trip-1"))
    }

    @Test
    fun theSummaryComesBackWithTheCarsSession() = runTest {
        val api = ScriptedSummaryApi(summary)
        assertEquals(SummaryOutcome.Ready(summary), repo(api).summary("trip-1"))
        assertEquals(listOf("access-s1"), api.tokens)
    }

    @Test
    fun waitsWhileTheUploadCatchesUp() = runTest {
        val api = ScriptedSummaryApi(CloudError.Http(403, null), CloudError.Http(409, "trip_not_ended"), summary)
        val start = testScheduler.currentTime

        assertEquals(SummaryOutcome.Ready(summary), repo(api).summary("trip-1"))
        assertEquals(6_000L, testScheduler.currentTime - start)
    }

    @Test
    fun aRefusedTokenIsRefreshedBeforeTheNextTry() = runTest {
        val auth = FakeAuth()
        val api = ScriptedSummaryApi(CloudError.Http(401, null), summary)
        assertEquals(SummaryOutcome.Ready(summary), repo(api, auth).summary("trip-1"))
        assertEquals(listOf("access-s1", "access-r1"), api.tokens)
        assertEquals(1, auth.refreshes)
    }

    @Test
    fun aModelErrorFailsAtOnce() = runTest {
        val api = ScriptedSummaryApi(CloudError.Http(502, null), summary)
        assertEquals(SummaryOutcome.Failed, repo(api).summary("trip-1"))
        assertEquals(1, api.tokens.size)
    }

    @Test
    fun givesUpAfterTheLastTry() = runTest {
        val api = ScriptedSummaryApi(offline)
        assertEquals(SummaryOutcome.Failed, repo(api).summary("trip-1"))
        assertEquals(3, api.tokens.size)
    }

    @Test
    fun eventsComeFromTheLocalLog() = runTest {
        assertEquals(4, repo(null).events("trip-1").getOrThrow().size)
    }
}

class ReportViewModelTest {
    private val trip = StoredTrip(
        "trip-1", TripStatus.ENDED, Destination("집", 37.5, 127.0), "언니", "hash", PinAttempts(),
        T0, T0 + 38.minutes, T0 + 38.minutes, ArrivalInstructions(), "tok",
    )
    private var closed = 0

    private fun TestScope.vm(api: SummaryApi?) =
        ReportViewModel(trip, repo(api), SEOUL, onClose = { closed++ }, scope = this)

    @Test
    fun showsTheTimelineAndThePlainSummary() = runTest {
        val vm = vm(ScriptedSummaryApi(summary))
        assertEquals("운행 기록 · 집", vm.state.value.kicker)
        assertEquals(AiState.Loading, vm.state.value.ai)
        advanceUntilIdle()

        val s = vm.state.value
        assertTrue(s.timelineLoaded)
        assertEquals(4, s.timeline.size)
        assertEquals(ReportOutcome.SAFE_ARRIVAL, s.outcome)
        assertEquals("안전하게 귀가했어요", s.status)
        // One plain summary: a stored report's old lines and caveats are not shown (ADR 0017 amendment).
        assertEquals(AiState.Ready(summary.summary), s.ai)
    }

    @Test
    fun aFailureKeepsTheTimelineAndRetryCanRecover() = runTest {
        val vm = vm(ScriptedSummaryApi(CloudError.Http(502, null), summary))
        advanceUntilIdle()
        assertEquals(AiState.Failed, vm.state.value.ai)
        assertEquals(4, vm.state.value.timeline.size)

        vm.retry()
        assertEquals(AiState.Loading, vm.state.value.ai)
        advanceUntilIdle()
        assertTrue(vm.state.value.ai is AiState.Ready)
    }

    @Test
    fun retryDoesNothingUnlessItFailed() = runTest {
        val api = ScriptedSummaryApi(summary)
        val vm = vm(api)
        vm.retry() // still loading
        advanceUntilIdle()
        vm.retry() // ready
        advanceUntilIdle()
        assertEquals(1, api.tokens.size)
    }

    @Test
    fun withoutACloudTheAiPartIsOff() = runTest {
        val vm = vm(null)
        advanceUntilIdle()
        assertEquals(AiState.Off, vm.state.value.ai)
    }

    @Test
    fun anEarlyEndTurnsTheStatusAmber() = runTest {
        val early = listOf(
            EventRecord(1, T0, null, null, TripEvent.TripStarted()),
            EventRecord(2, T0 + 20.minutes, null, null, TripEvent.TripEnded(EndReason.ENDED_EARLY, "목적지 밖에서 운행 종료 · 1.2km 남음")),
        )
        val vm = ReportViewModel(trip, ReportRepository({ Result.success(early) }, null), SEOUL, {}, this)
        advanceUntilIdle()
        assertEquals(ReportOutcome.ENDED_EARLY, vm.state.value.outcome)
        assertTrue(vm.state.value.caution)
    }

    @Test
    fun anUnreadableLogShowsAnEmptyTimeline() = runTest {
        val vm = ReportViewModel(
            trip, ReportRepository({ Result.failure(IllegalStateException("bad row")) }, null), SEOUL, {}, this,
        )
        advanceUntilIdle()
        assertTrue(vm.state.value.timelineLoaded)
        assertTrue(vm.state.value.timeline.isEmpty())
        assertEquals(ReportOutcome.UNKNOWN, vm.state.value.outcome)
    }

    @Test
    fun closeGoesBack() = runTest {
        vm(null).close()
        assertEquals(1, closed)
    }
}
