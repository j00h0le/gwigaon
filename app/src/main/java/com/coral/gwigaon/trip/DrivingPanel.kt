package com.coral.gwigaon.trip

import com.coral.gwigaon.route.Geo
import com.coral.gwigaon.route.LatLng
import com.coral.gwigaon.route.RouteEta
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.time.toJavaInstant

enum class PanelEventKind { START, DEVIATION, STOP, CHECK_OK, NO_RESPONSE, INTERRUPTED, SOS_CANCELLED }

/** One timeline row: live rows are amber with a detail and a running count, past rows are one quiet line. */
data class PanelEvent(val kind: PanelEventKind, val title: String, val detail: String?, val time: String, val live: Boolean)

/** A caution on the progress bar, as fractions of the way home. */
data class PanelMark(val start: Float, val end: Float, val live: Boolean)

enum class PanelNoteKind { PARKING, ENTRANCE, KEY }

/** One of the passenger's arrival notes: the fact and what it is about. */
data class PanelNote(val kind: PanelNoteKind, val fact: String, val label: String)

/** The open check-in: which cue asked, its level (2 = repeated, amber), and the seconds before "no response". */
data class CheckInView(val cue: CueKind, val level: Int, val secondsLeft: Int)

/**
 * Everything the driver panel shows (`design/d2/proto/driving.html`, `checkin.html`). The glance zone (status,
 * destination, arrival time, progress) is always filled, so state changes swap content, never positions.
 */
data class DrivingPanel(
    val status: String,
    /** Amber status: the safe-arrival check only. */
    val caution: Boolean,
    val destination: String,
    /** The line under the status: the destination, or what to do while off route / stopped (D3f D4, D5). */
    val subtext: String,
    val eta: String,
    val etaDetail: String,
    val progress: Float,
    val marks: List<PanelMark>,
    /** Newest first, live first, at most [DrivingPanelRule.MAX_EVENTS]. */
    val timeline: List<PanelEvent>,
    /** Near the destination: the passenger's notes take the timeline's place. */
    val arrivalNotes: List<PanelNote>,
    val checkIn: CheckInView?,
)

/** Pure: the panel from the trip, its log, the arrival time and the clock. */
object DrivingPanelRule {
    const val MAX_EVENTS = 3

    /** A long stop's mark is a short notch at the place it happened. */
    private const val STOP_MARK = 0.01f

    const val OFF_ROUTE = "경로로 돌아가 주세요"
    const val STOPPED = "예상치 못한 정차가 감지됐어요"

    /**
     * [remainingM]: straight-line distance left now; beyond [plainEndM] the trip no longer counts as near the
     * destination (no notes, no "목적지 근처예요"), even after it came near once (ADR 0020).
     */
    fun build(
        active: ActiveTrip,
        eta: RouteEta?,
        now: Instant,
        zone: ZoneId,
        checkInTimeout: Duration,
        remainingM: Int? = null,
        plainEndM: Double = MonitoringConfig.DEFAULT.plainEndMeters,
    ): DrivingPanel {
        val clock = DateTimeFormatter.ofPattern("HH:mm").withZone(zone)
        fun hm(t: Instant) = clock.format(t.toJavaInstant())
        val state = active.state
        val log = active.log
        val items = timeline(log, now, ::hm)
        val arriving = state.status == TripStatus.ARRIVING && TripEndRule.near(state, remainingM, plainEndM)
        val checkIn = if (state.checkInPending) {
            log.lastOrNull { it.event is TripEvent.CheckInPrompted }?.let { r ->
                val e = r.event as TripEvent.CheckInPrompted
                val left = (checkInTimeout - (now - r.occurredAt)).inWholeSeconds.coerceAtLeast(0).toInt()
                CheckInView(e.cue, e.level, left)
            }
        } else {
            null
        }
        val stoppedFor = openStop(log)?.let { (r, e) -> now - r.occurredAt + e.durationS.seconds }
        val status = when {
            state.status == TripStatus.SAFE_ARRIVAL_CHECK -> "승객 상태를 확인해 주세요"
            state.safeArrivalClosedBy == SafeArrivalCloser.DRIVER_CONFIRMED -> "승객 하차를 기다리는 중"
            checkIn != null -> "괜찮으신가요?"
            state.deviating && state.longStopped -> "경로를 벗어나 멈춰 있어요"
            state.longStopped && stoppedFor != null -> "${stoppedFor.inWholeMinutes}분째 멈춰 있어요"
            state.deviating -> "경로를 벗어났어요"
            state.interrupted -> "모니터링 재연결 중"
            arriving -> if (state.arrived) "목적지에 도착했어요" else "목적지 근처예요"
            else -> "안전하게 가고 있어요"
        }
        return DrivingPanel(
            status = status,
            caution = state.status == TripStatus.SAFE_ARRIVAL_CHECK,
            destination = active.trip.destination.name,
            subtext = when {
                state.deviating -> OFF_ROUTE
                state.longStopped -> STOPPED
                else -> active.trip.destination.name
            },
            eta = eta?.let { hm(it.arrival) } ?: "--:--",
            etaDetail = eta?.let { "도착 · ${Geo.formatDistance(it.remainM.toDouble())}" } ?: "도착 시간 확인 중",
            progress = eta?.fraction ?: 0f,
            marks = marks(log, active.trip.destination.let { LatLng(it.lat, it.lng) }, eta),
            timeline = items,
            arrivalNotes = if (arriving) notes(active.trip.arrival) else emptyList(),
            checkIn = checkIn,
        )
    }

    private fun openStop(log: List<EventRecord>): Pair<EventRecord, TripEvent.LongStop>? {
        val last = log.lastOrNull { it.event is TripEvent.LongStop || it.event is TripEvent.LongStopCleared } ?: return null
        return (last.event as? TripEvent.LongStop)?.let { last to it }
    }

    private fun running(since: Instant, now: Instant): String {
        val m = (now - since).inWholeMinutes
        return if (m < 1) "방금" else "${m}분째"
    }

    private fun timeline(log: List<EventRecord>, now: Instant, hm: (Instant) -> String): List<PanelEvent> {
        // (when it started, the row) in log order; open deviation/stop rows are replaced when they clear.
        val rows = mutableListOf<Pair<Instant, PanelEvent>>()
        var deviation: Pair<Int, EventRecord>? = null
        var stop: Pair<Int, EventRecord>? = null
        var interrupted: Pair<Int, EventRecord>? = null
        var asked: CueKind? = null
        for (r in log) {
            when (val e = r.event) {
                is TripEvent.CheckInPrompted -> asked = e.cue
                is TripEvent.TripStarted -> rows += r.occurredAt to PanelEvent(PanelEventKind.START, "출발", null, hm(r.occurredAt), false)
                is TripEvent.RouteDeviation -> {
                    deviation = rows.size to r
                    rows += r.occurredAt to PanelEvent(
                        PanelEventKind.DEVIATION, "경로 이탈", "경로에서 ${Geo.formatDistance(e.distanceM.toDouble())}",
                        running(r.occurredAt, now), true,
                    )
                }
                is TripEvent.DeviationCleared -> deviation?.let { (i, open) ->
                    rows[i] = open.occurredAt to PanelEvent(
                        PanelEventKind.DEVIATION, "경로 이탈 → 복귀", null, "${hm(open.occurredAt)}–${hm(r.occurredAt)}", false,
                    )
                    deviation = null
                }
                is TripEvent.LongStop -> {
                    val since = r.occurredAt - e.durationS.seconds
                    stop = rows.size to r
                    rows += since to PanelEvent(PanelEventKind.STOP, "정차", "비상 연락처에게 알렸어요", running(since, now), true)
                }
                is TripEvent.LongStopCleared -> stop?.let { (i, open) ->
                    val since = open.occurredAt - (open.event as TripEvent.LongStop).durationS.seconds
                    rows[i] = since to PanelEvent(PanelEventKind.STOP, "정차 → 출발", null, "${hm(since)}–${hm(r.occurredAt)}", false)
                    stop = null
                }
                is TripEvent.CheckInAnswered -> when (e.answer) {
                    CheckInAnswer.OK ->
                        rows += r.occurredAt to PanelEvent(PanelEventKind.CHECK_OK, "확인 · 괜찮아요", null, hm(r.occurredAt), false)
                    // What was heard, like any other past event (D3f D6): no lasting headline, no record line.
                    CheckInAnswer.NO_RESPONSE ->
                        rows += r.occurredAt to PanelEvent(PanelEventKind.NO_RESPONSE, heard(asked), null, hm(r.occurredAt), false)
                    CheckInAnswer.SOS -> Unit
                }
                is TripEvent.MonitoringInterrupted -> {
                    interrupted = rows.size to r
                    rows += r.occurredAt to PanelEvent(PanelEventKind.INTERRUPTED, "모니터링 끊김", null, hm(r.occurredAt), false)
                }
                is TripEvent.MonitoringResumed -> interrupted?.let { (i, open) ->
                    rows[i] = open.occurredAt to rows[i].second.copy(time = "${hm(open.occurredAt)}–${hm(r.occurredAt)}")
                    interrupted = null
                }
                is TripEvent.SosCancelled ->
                    rows += r.occurredAt to PanelEvent(PanelEventKind.SOS_CANCELLED, "SOS 취소 · 잘못 누름", null, hm(r.occurredAt), false)
                else -> Unit
            }
        }
        return rows
            .sortedWith(compareByDescending<Pair<Instant, PanelEvent>> { it.second.live }.thenByDescending { it.first })
            .take(MAX_EVENTS)
            .map { it.second }
    }

    private fun marks(log: List<EventRecord>, dest: LatLng, eta: RouteEta?): List<PanelMark> {
        val total = eta?.totalM?.takeIf { it > 0 } ?: return emptyList()
        fun at(r: EventRecord): Float? {
            val lat = r.lat ?: return null
            val lng = r.lng ?: return null
            return (1f - (Geo.distanceM(LatLng(lat, lng), dest) / total).toFloat()).coerceIn(0f, 1f)
        }
        val marks = mutableListOf<PanelMark>()
        var deviationStart: Float? = null
        var deviationOpen = false
        for (r in log) {
            when (r.event) {
                is TripEvent.RouteDeviation -> {
                    deviationStart = at(r)
                    deviationOpen = deviationStart != null
                }
                is TripEvent.DeviationCleared -> {
                    val start = deviationStart
                    val end = at(r)
                    if (start != null && end != null) marks += PanelMark(minOf(start, end), maxOf(start, end), live = false)
                    deviationStart = null
                    deviationOpen = false
                }
                is TripEvent.LongStop -> at(r)?.let { marks += PanelMark(it, (it + STOP_MARK).coerceAtMost(1f), live = true) }
                is TripEvent.LongStopCleared -> marks.indexOfLast { it.live && it.end - it.start <= STOP_MARK + 1e-4f }
                    .takeIf { it >= 0 }?.let { marks[it] = marks[it].copy(live = false) }
                else -> Unit
            }
        }
        val start = deviationStart
        if (deviationOpen && start != null) {
            val now = eta.fraction ?: start
            marks += PanelMark(minOf(start, now), maxOf(start, now).coerceAtLeast(start + STOP_MARK).coerceAtMost(1f), live = true)
        }
        return marks.sortedBy { it.start }
    }

    /** The timeline title for an unanswered check-in: what was heard. */
    fun heard(cue: CueKind?): String = when (cue) {
        CueKind.LOUD -> "큰 목소리 감지"
        CueKind.PROFANITY -> "폭언 감지"
        CueKind.DISTRESS -> "도움 요청 감지"
        null -> "확인 응답 없음"
    }

    /** The passenger's arrival notes as the driver sees them (also the 설정 preview): 출입문, 주차, 차 키 (D3f D7). */
    fun notes(arrival: ArrivalInstructions): List<PanelNote> = buildList {
        arrival.entrance.trim().takeIf { it.isNotEmpty() }?.let { add(PanelNote(PanelNoteKind.ENTRANCE, it, "출입문")) }
        arrival.parking.trim().takeIf { it.isNotEmpty() }?.let { add(PanelNote(PanelNoteKind.PARKING, it, "주차")) }
        arrival.keyNote.trim().takeIf { it.isNotEmpty() }?.let { add(PanelNote(PanelNoteKind.KEY, it, "차 키")) }
    }
}
