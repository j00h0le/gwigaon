package com.coral.gwigaon.report

import com.coral.gwigaon.trip.EndReason
import com.coral.gwigaon.trip.EventRecord
import com.coral.gwigaon.trip.CheckInAnswer
import com.coral.gwigaon.trip.EventType
import com.coral.gwigaon.trip.SafeArrivalCloser
import com.coral.gwigaon.trip.TripEvent
import com.coral.gwigaon.trip.TripTexts
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** How a timeline row is marked; same four tones as the contact page (web/contact/state.js). */
enum class ReportTone { NEUTRAL, OK, WARN, DANGER }

/** One measured fact: what the car logged, when, and the rule's own sentence for why. */
data class TimelineItem(val seq: Int, val time: String, val label: String, val reason: String?, val tone: ReportTone)

/** Korean wording for the journey report (SPEC journey-report, ADR 0017). Labels match the contact page's. */
object ReportTexts {
    const val REPORT_TITLE = "귀가온 리포트"
    /** Beside the heading, the same quiet style as [LOG_HINT]: the text is the AI's reading, not a fact (SPEC). */
    const val AI_HINT = "AI가 쓴 요약"
    const val AI_LOADING = "기록을 읽고 있어요"
    const val AI_FAILED = "귀가온 리포트를 가져오지 못했어요. 운행 로그는 그대로 볼 수 있어요."
    const val AI_OFF = "이 빌드는 클라우드에 연결되지 않아 귀가온 리포트가 없어요."
    const val LOG_TITLE = "운행 로그"
    const val LOG_HINT = "차량 신호와 규칙이 기록"
    const val SHARED = "비상 연락처도 같은 기록을 볼 수 있어요"
    const val RETRY = "다시 시도"
    const val CLOSE = "닫기"
    const val NO_EVENTS = "기록된 이벤트가 없어요."

    fun kicker(destination: String): String = "운행 기록 · $destination"

    fun status(outcome: ReportOutcome): String = when (outcome) {
        ReportOutcome.SAFE_ARRIVAL -> "안전하게 귀가했어요"
        ReportOutcome.ENDED_BY_PASSENGER -> "승객이 PIN으로 운행을 끝냈어요"
        ReportOutcome.ENDED_AT_ARRIVAL -> "목적지에서 운행을 끝냈어요"
        ReportOutcome.ENDED_EARLY -> "목적지 전에 운행이 끝났어요"
        ReportOutcome.UNKNOWN -> "운행이 끝났어요"
    }

    /** "21:03 → 21:41 · 38분"; an open end (unreadable log) shows the start only. */
    fun period(startedAt: Instant, endedAt: Instant?, zone: ZoneId): String {
        val start = time(startedAt, zone)
        if (endedAt == null) return "$start 시작"
        return "$start → ${time(endedAt, zone)} · ${TripTexts.duration(endedAt - startedAt)}"
    }

    fun time(at: Instant, zone: ZoneId): String =
        HH_MM.format(java.time.Instant.ofEpochMilli(at.toEpochMilliseconds()).atZone(zone))

    /** The short 운행 로그 ([ReportLog]) in seq order, with local times, labels and the rule's own reasons. */
    fun shortLog(log: List<EventRecord>, zone: ZoneId): List<TimelineItem> =
        ReportLog.filter(log).sortedBy { it.seq }.map { r ->
            val (label, tone) = meta(r.event)
            val e = r.event
            TimelineItem(r.seq, time(r.occurredAt, zone), label, if (e is TripEvent.LongStop) stopped(e.durationS) else detail(e.reason, label), tone)
        }

    /** "5분 동안 정차했어요": how long the car stood, in whole minutes (never seconds), not the live wording. */
    fun stopped(durationS: Int): String = "${TripTexts.duration((durationS / 60).coerceAtLeast(1).minutes)} 동안 정차했어요"

    /** The rule's sentence under the label, without repeating it: "목적지 밖에서 운행 종료 · 1.2km 남음" → "1.2km 남음". */
    private fun detail(reason: String?, label: String): String? =
        reason?.removePrefix("$label · ")?.takeIf { it.isNotBlank() && it != label }

    /** A driver's "승객 반응 있음" is not a safe arrival (ADR 0007), so it reads as a caution, like on the contact page. */
    fun meta(event: TripEvent): Pair<String, ReportTone> {
        if (event is TripEvent.SafeArrivalConfirmed && event.closedBy == SafeArrivalCloser.DRIVER_CONFIRMED) {
            return "기사 확인 (하차 전)" to ReportTone.WARN
        }
        if (event is TripEvent.CheckInAnswered) {
            return when (event.answer) {
                CheckInAnswer.OK -> "상태 확인: 괜찮음" to ReportTone.OK
                CheckInAnswer.SOS -> "상태 확인: SOS 선택" to ReportTone.DANGER
                CheckInAnswer.NO_RESPONSE -> "상태 확인: 응답 없음" to ReportTone.WARN
            }
        }
        if (event is TripEvent.TripEnded) {
            return when (event.endReason) {
                EndReason.SAFE_ARRIVAL -> "안전 귀가" to ReportTone.OK
                EndReason.ENDED_EARLY -> "목적지 밖에서 운행 종료" to ReportTone.WARN
                EndReason.ENDED_AT_ARRIVAL -> "도착 후 운행 종료" to ReportTone.NEUTRAL
                EndReason.ENDED_BY_PASSENGER -> "운행 종료" to ReportTone.NEUTRAL
            }
        }
        return when (event.type) {
            EventType.TRIP_STARTED -> "운행 시작" to ReportTone.NEUTRAL
            EventType.ROUTE_DEVIATION -> "경로 이탈" to ReportTone.WARN
            EventType.DEVIATION_CLEARED -> "경로 복귀" to ReportTone.OK
            EventType.LONG_STOP -> "장시간 정차" to ReportTone.WARN
            EventType.LONG_STOP_CLEARED -> "다시 출발" to ReportTone.OK
            EventType.SOS -> "SOS 비상 신호" to ReportTone.DANGER
            EventType.SOS_CANCELLED -> "SOS 취소 (오작동)" to ReportTone.NEUTRAL
            EventType.MONITORING_INTERRUPTED -> "모니터링 중단" to ReportTone.WARN
            EventType.MONITORING_RESUMED -> "모니터링 재개" to ReportTone.OK
            EventType.NEAR_DESTINATION -> "목적지 근처 도착" to ReportTone.NEUTRAL
            EventType.ARRIVED -> "목적지 도착" to ReportTone.OK
            // A caution here (ADR 0017: amber); the contact page, which alerts the 보호자, keeps it red.
            EventType.SAFE_ARRIVAL_CHECK_OPENED -> "승객 하차 확인 필요" to ReportTone.WARN
            EventType.SAFE_ARRIVAL_CONFIRMED -> "안전 하차 확인" to ReportTone.OK
            EventType.CHECK_IN_PROMPTED -> "상태 확인 요청" to ReportTone.WARN
            EventType.CHECK_IN_ANSWERED -> "상태 확인 응답" to ReportTone.OK
            EventType.TRIP_ENDED -> "운행 종료" to ReportTone.NEUTRAL
            EventType.PIN_LOCKOUT -> "PIN 잠김 (여러 번 틀림)" to ReportTone.WARN
            EventType.SETTINGS_CHANGED -> "설정 변경" to ReportTone.NEUTRAL
        }
    }

    private val HH_MM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
}
