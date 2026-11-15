package com.coral.gwigaon.trip

import com.coral.gwigaon.route.RouteEta
import com.coral.gwigaon.vehicle.Destination
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** The driver panel's content (D3b, `design/d2/proto/driving.html` and `checkin.html`) from the trip and its log. */
class DrivingPanelTest {
    private val zone = ZoneId.of("Asia/Seoul")
    private val t0 = Instant.parse("2026-11-13T15:58:00Z") // 00:58 in Seoul
    private val home = Destination("역삼동 · 집", 37.500, 127.036)
    private val arrival = ArrivalInstructions("지하 2층 B구역", "101동 출입구", "차 키는 승객에게")

    private val trip = StoredTrip(
        id = "t1", status = TripStatus.ACTIVE, destination = home, contactLabel = "언니", pinHash = "",
        pinAttempts = PinAttempts(), startedAt = t0, endedAt = null, lastSeenAt = t0, arrival = arrival,
    )

    private var seq = 0

    private fun at(d: Duration, event: TripEvent, lat: Double? = null, lng: Double? = null) =
        EventRecord(++seq, t0 + d, lat, lng, event)

    /** Replays [log] through the state machine, so every test runs on a state the app could really be in. */
    private fun active(vararg log: EventRecord): ActiveTrip {
        val state = TripStateMachine.replay(log.map { it.event }).getOrThrow()
        return ActiveTrip(trip, state, log.toList())
    }

    private fun panel(a: ActiveTrip, now: Duration, eta: RouteEta? = null) =
        DrivingPanelRule.build(a, eta, t0 + now, zone, checkInTimeout = 30.seconds)

    private val started = at(Duration.ZERO, TripEvent.TripStarted(StartSource.BUTTON))

    @Test
    fun calmTripSaysItIsGoingWellAndShowsTheStart() {
        val p = panel(active(started), 5.minutes)
        assertEquals("안전하게 가고 있어요", p.status)
        assertEquals("역삼동 · 집", p.destination)
        assertFalse(p.caution)
        assertEquals(listOf(PanelEvent(PanelEventKind.START, "출발", null, "00:58", live = false)), p.timeline)
        assertNull(p.checkIn)
        assertTrue(p.arrivalNotes.isEmpty())
    }

    @Test
    fun withoutAnArrivalTimeTheNumeralHoldsItsPlace() {
        val p = panel(active(started), 5.minutes)
        assertEquals("--:--", p.eta)
        assertEquals("도착 시간 확인 중", p.etaDetail)
        assertEquals(0f, p.progress)
    }

    @Test
    fun arrivalTimeIsTheClockTimeWithTheDistanceLeft() {
        val eta = RouteEta(remainM = 6200, remainS = 26 * 60, totalM = 10_000, at = t0 + 0.minutes)
        val p = panel(active(started), 0.minutes, eta)
        assertEquals("01:24", p.eta)
        assertEquals("도착 · 6.2km", p.etaDetail)
        assertEquals(0.38f, p.progress, 0.001f)
    }

    @Test
    fun liveDeviationIsAnAmberEventWithItsDistanceAndHowLong() {
        val dev = at(11.minutes, TripEvent.RouteDeviation(320, "경로 이탈"), 37.45, 127.03)
        val p = panel(active(started, dev), 13.minutes)
        assertEquals("경로를 벗어났어요", p.status)
        assertEquals(PanelEvent(PanelEventKind.DEVIATION, "경로 이탈", "경로에서 320m", "2분째", live = true), p.timeline.first())
        assertEquals("출발", p.timeline.last().title)
    }

    @Test
    fun clearedDeviationAgesIntoOneLineWithItsTimeRange() {
        val dev = at(11.minutes, TripEvent.RouteDeviation(320))
        val back = at(13.minutes, TripEvent.DeviationCleared())
        val p = panel(active(started, dev, back), 20.minutes)
        assertEquals("안전하게 가고 있어요", p.status)
        assertEquals(PanelEvent(PanelEventKind.DEVIATION, "경로 이탈 → 복귀", null, "01:09–01:11", live = false), p.timeline.first())
    }

    @Test
    fun longStopCountsFromWhenTheCarStopped() {
        // LONG_STOP is logged once the car has stood 5 minutes; the panel counts from the stop itself.
        val stop = at(20.minutes, TripEvent.LongStop(durationS = 300))
        val p = panel(active(started, stop), 21.minutes)
        assertEquals("6분째 멈춰 있어요", p.status)
        assertEquals(PanelEvent(PanelEventKind.STOP, "정차", "비상 연락처에게 알렸어요", "6분째", live = true), p.timeline.first())
    }

    @Test
    fun deviationAndStopTogether() {
        val dev = at(11.minutes, TripEvent.RouteDeviation(320))
        val stop = at(20.minutes, TripEvent.LongStop(durationS = 300))
        val p = panel(active(started, dev, stop), 21.minutes)
        assertEquals("경로를 벗어나 멈춰 있어요", p.status)
        assertEquals(listOf(PanelEventKind.STOP, PanelEventKind.DEVIATION, PanelEventKind.START), p.timeline.map { it.kind })
        assertTrue(p.timeline.take(2).all { it.live })
    }

    @Test
    fun timelineKeepsTheThreeNewestWithLiveOnesFirst() {
        val log = listOf(
            started,
            at(5.minutes, TripEvent.RouteDeviation(320)),
            at(6.minutes, TripEvent.DeviationCleared()),
            at(8.minutes, TripEvent.CheckInPrompted(1, CueKind.LOUD)),
            at(8.minutes + 5.seconds, TripEvent.CheckInAnswered(CheckInAnswer.OK)),
            at(12.minutes, TripEvent.LongStop(durationS = 300)),
        )
        val p = panel(active(*log.toTypedArray()), 13.minutes)
        assertEquals(listOf("정차", "확인 · 괜찮아요", "경로 이탈 → 복귀"), p.timeline.map { it.title })
    }

    @Test
    fun noResponseIsAPastRowNamingWhatWasHeard() {
        // D3f D6: no lasting headline, no record line; the row says what was heard.
        val asked = at(10.minutes, TripEvent.CheckInPrompted(1, CueKind.LOUD))
        val none = at(10.minutes + 30.seconds, TripEvent.CheckInAnswered(CheckInAnswer.NO_RESPONSE))
        val fresh = panel(active(started, asked, none), 12.minutes)
        assertEquals("안전하게 가고 있어요", fresh.status)
        assertEquals(PanelEvent(PanelEventKind.NO_RESPONSE, "큰 목소리 감지", null, "01:08", live = false), fresh.timeline.first())
        assertFalse(fresh.timeline.first().live)

        val swore = at(20.minutes, TripEvent.CheckInPrompted(2, CueKind.PROFANITY))
        val none2 = at(20.minutes + 30.seconds, TripEvent.CheckInAnswered(CheckInAnswer.NO_RESPONSE))
        assertEquals("폭언 감지", panel(active(started, asked, none, swore, none2), 21.minutes).timeline.first().title)
        assertEquals("도움 요청 감지", DrivingPanelRule.heard(CueKind.DISTRESS))
        assertEquals("확인 응답 없음", DrivingPanelRule.heard(null))
    }

    @Test
    fun offRouteAndStoppedSayWhatToDoUnderTheStatus() {
        // D3f D4, D5: the line under the status replaces the destination.
        assertEquals("역삼동 · 집", panel(active(started), 5.minutes).subtext)
        val off = at(10.minutes, TripEvent.RouteDeviation(320))
        assertEquals("경로로 돌아가 주세요", panel(active(started, off), 11.minutes).subtext)
        val stop = at(10.minutes, TripEvent.LongStop(300))
        assertEquals("예상치 못한 정차가 감지됐어요", panel(active(started, stop), 11.minutes).subtext)
    }

    @Test
    fun beyondOneKilometreTheTripNoLongerCountsAsNear() {
        // ADR 0020 / D3f D11: came near once, then drove 1.6 km away again.
        val near = at(30.minutes, TripEvent.NearDestination())
        val far = DrivingPanelRule.build(active(started, near), null, t0 + 31.minutes, zone, 30.seconds, remainingM = 1600)
        assertEquals("안전하게 가고 있어요", far.status)
        assertTrue(far.arrivalNotes.isEmpty())
        val close = DrivingPanelRule.build(active(started, near), null, t0 + 31.minutes, zone, 30.seconds, remainingM = 400)
        assertEquals("목적지 근처예요", close.status)
        assertTrue(close.arrivalNotes.isNotEmpty())
    }

    @Test
    fun openCheckInAsksAndCountsDown() {
        val asked = at(10.minutes, TripEvent.CheckInPrompted(2, CueKind.PROFANITY))
        val p = panel(active(started, asked), 10.minutes + 12.seconds)
        assertEquals("괜찮으신가요?", p.status)
        assertEquals(CheckInView(CueKind.PROFANITY, level = 2, secondsLeft = 18), p.checkIn)
    }

    @Test
    fun checkInCountdownStopsAtZero() {
        val asked = at(10.minutes, TripEvent.CheckInPrompted(1, CueKind.LOUD))
        assertEquals(0, panel(active(started, asked), 11.minutes).checkIn?.secondsLeft)
    }

    @Test
    fun nearTheDestinationTheArrivalNotesReplaceTheTimeline() {
        val near = at(30.minutes, TripEvent.NearDestination())
        val p = panel(active(started, near), 31.minutes)
        assertEquals("목적지 근처예요", p.status)
        assertEquals("역삼동 · 집", p.destination)
        assertEquals(
            listOf(
                PanelNote(PanelNoteKind.ENTRANCE, "101동 출입구", "출입문"),
                PanelNote(PanelNoteKind.PARKING, "지하 2층 B구역", "주차"),
                PanelNote(PanelNoteKind.KEY, "차 키는 승객에게", "차 키"),
            ),
            p.arrivalNotes,
        )
    }

    @Test
    fun arrivalNotesOnlyListWhatThePassengerLeft() {
        val near = at(30.minutes, TripEvent.NearDestination())
        val a = active(started, near).let { it.copy(trip = it.trip.copy(arrival = ArrivalInstructions(parking = "지하 1층"))) }
        assertEquals(listOf(PanelNote(PanelNoteKind.PARKING, "지하 1층", "주차")), panel(a, 31.minutes).arrivalNotes)
    }

    @Test
    fun cautionMarksSitWhereTheyHappenedAndDimOnceOver() {
        // Destination at lat 37.500; total 10 km. 1° of latitude ≈ 111.2 km.
        val dev = at(11.minutes, TripEvent.RouteDeviation(320), lat = 37.500 - 6.0 / 111.2, lng = 127.036)
        val back = at(13.minutes, TripEvent.DeviationCleared(), lat = 37.500 - 5.0 / 111.2, lng = 127.036)
        val stop = at(20.minutes, TripEvent.LongStop(durationS = 300), lat = 37.500 - 3.0 / 111.2, lng = 127.036)
        val eta = RouteEta(remainM = 3000, remainS = 600, totalM = 10_000, at = t0 + 21.minutes)
        val marks = panel(active(started, dev, back, stop), 21.minutes, eta).marks
        assertEquals(2, marks.size)
        assertEquals(0.4f, marks[0].start, 0.01f)
        assertEquals(0.5f, marks[0].end, 0.01f)
        assertFalse(marks[0].live)
        assertEquals(0.7f, marks[1].start, 0.01f)
        assertTrue(marks[1].end > marks[1].start)
        assertTrue(marks[1].live)
    }

    @Test
    fun noMarksWithoutAPositionOrATotal() {
        val dev = at(11.minutes, TripEvent.RouteDeviation(320))
        assertTrue(panel(active(started, dev), 12.minutes, RouteEta(3000, 600, 10_000, t0)).marks.isEmpty())
        val placed = at(11.minutes, TripEvent.RouteDeviation(320), 37.45, 127.036)
        assertTrue(panel(active(started, placed), 12.minutes, RouteEta(3000, 600, null, t0)).marks.isEmpty())
    }

    @Test
    fun cancelledSosAndAnInterruptionShowAsPastEvents() {
        val log = listOf(
            started,
            at(3.minutes, TripEvent.Sos(SosSource.ON_SCREEN)),
            at(3.minutes + 20.seconds, TripEvent.SosCancelled()),
            at(9.minutes, TripEvent.MonitoringInterrupted(gapS = 120)),
            at(11.minutes, TripEvent.MonitoringResumed()),
        )
        val p = panel(active(*log.toTypedArray()), 12.minutes)
        assertEquals(
            listOf(
                PanelEvent(PanelEventKind.INTERRUPTED, "모니터링 끊김", null, "01:07–01:09", live = false),
                PanelEvent(PanelEventKind.SOS_CANCELLED, "SOS 취소 · 잘못 누름", null, "01:01", live = false),
                PanelEvent(PanelEventKind.START, "출발", null, "00:58", live = false),
            ),
            p.timeline,
        )
    }

    @Test
    fun theSafeArrivalCheckIsAmber() {
        val log = listOf(
            started,
            at(30.minutes, TripEvent.NearDestination()),
            at(32.minutes, TripEvent.Arrived()),
            at(35.minutes, TripEvent.SafeArrivalCheckOpened()),
        )
        val p = panel(active(*log.toTypedArray()), 36.minutes)
        assertEquals("승객 상태를 확인해 주세요", p.status)
        assertTrue(p.caution)
    }
}
