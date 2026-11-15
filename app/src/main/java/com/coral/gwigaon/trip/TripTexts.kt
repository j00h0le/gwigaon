package com.coral.gwigaon.trip

import com.coral.gwigaon.route.Geo
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** How the status line is colored: normal, caution (amber accent), or alert (SOS red). */
enum class StatusTone { NORMAL, CAUTION, ALERT }

/** Korean sentences the trip core writes into events and the status notification. */
object TripTexts {
    const val RESUMED = "모니터링 재개"

    /** "설정 변경: 집 주소, 보호자" — what changed, never the values. */
    fun settingsChanged(fields: Collection<SettingsField>): String =
        "설정 변경: " + fields.sortedBy { it.ordinal }.joinToString(", ") { settingsFieldName(it) }

    fun settingsFieldName(field: SettingsField): String = when (field) {
        SettingsField.HOME -> "집 주소"
        SettingsField.CONTACTS -> "비상 연락처"
        SettingsField.CUES -> "대화 감지"
        SettingsField.ARRIVAL -> "도착 안내"
        SettingsField.PIN -> "PIN"
    }

    /** Same wording as the seed data the contact page was built on: "앱 재시작으로 2분간 모니터링 중단". */
    fun interrupted(gap: Duration): String = "앱 재시작으로 ${duration(gap)}간 모니터링 중단"

    /** "45초", "2분", "2분 30초", "1시간 5분"; seconds are dropped from an hour up. */
    fun duration(d: Duration): String {
        val total = d.coerceAtLeast(Duration.ZERO).inWholeSeconds
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        val parts = buildList {
            if (h > 0) add("${h}시간")
            if (m > 0) add("${m}분")
            if (s > 0 && h == 0L) add("${s}초")
        }
        return parts.ifEmpty { listOf("0초") }.joinToString(" ")
    }

    /** The ongoing notification while an ended trip's last events go up. */
    const val NOTIFICATION_UPLOADING = "운행 기록을 보내는 중"

    fun notificationTitle(destinationName: String): String = "귀가온 운행 중 · $destinationName"

    /** One calm line for the ongoing notification; the most urgent condition wins. */
    fun notificationStatus(state: TripState): String = when {
        state.sosActive -> "SOS 발생"
        state.status == TripStatus.SAFE_ARRIVAL_CHECK -> "승객 상태를 확인해 주세요"
        state.safeArrivalClosedBy == SafeArrivalCloser.DRIVER_CONFIRMED -> "승객 하차를 기다리는 중"
        state.interrupted -> "모니터링 재연결 중"
        state.deviating -> "목적지와 다른 방향으로 이동 중"
        state.longStopped -> "목적지 외 장소에서 정차 중"
        state.status == TripStatus.ARRIVING -> if (state.arrived) "목적지 도착" else "목적지 근처"
        else -> "안심 귀가 모니터링 중"
    }

    /** Matches [notificationStatus]: SOS is an alert; anything the passenger or contact should notice is a caution. */
    fun statusTone(state: TripState): StatusTone = when {
        state.sosActive -> StatusTone.ALERT
        state.status == TripStatus.SAFE_ARRIVAL_CHECK || state.interrupted || state.deviating || state.longStopped ->
            StatusTone.CAUTION
        else -> StatusTone.NORMAL
    }

    /** "목적지와 다른 방향으로 1.2km 이동" (same wording as the seed data the contact page was built on). */
    fun deviation(excessM: Double): String = "목적지와 다른 방향으로 ${Geo.formatDistance(excessM)} 이동"

    const val DEVIATION_CLEARED = "경로로 복귀"

    /** "목적지 외 장소에서 7분 정차"; whole minutes (the rules check every few seconds, so seconds would be noise). */
    fun longStop(stopped: Duration): String = "목적지 외 장소에서 ${minutesFloor(stopped)} 정차"

    fun longStopCleared(stopped: Duration): String = "${minutesFloor(stopped)} 정차 후 다시 출발"

    private fun minutesFloor(d: Duration): String =
        if (d < 1.minutes) duration(d) else duration(d.inWholeMinutes.minutes)

    /** Same wording as the seed data the contact page was built on. */
    const val SOS_TURN_SIGNAL = "방향지시등 패턴으로 SOS"
    const val SOS_ON_SCREEN = "화면 SOS 버튼으로 SOS"
    const val SOS_CANCELLED = "오작동으로 취소"

    const val ENDED_BY_PASSENGER = "승객이 운행을 종료함"
    const val ENDED_AT_ARRIVAL = "도착 후 운행 종료"

    /** "목적지 밖에서 운행 종료 · 1.2km 남음"; without a fix, the first part only. */
    /** "1.6 km 남음" for the early-end dialog; null without a distance. */
    fun left(remainingM: Int?): String? = remainingM?.let { "${Geo.formatDistance(it.toDouble())} 남음" }

    fun endedEarly(remainingM: Int?): String =
        "목적지 밖에서 운행 종료" + (remainingM?.let { " · ${Geo.formatDistance(it.toDouble())} 남음" } ?: "")
    const val END_TRIP_FAILED = "운행을 종료하지 못했어요. 도착 확인 중이면 먼저 확인을 마쳐 주세요."
    const val SAFE_ARRIVAL_PIN_FAILED = "확인을 저장하지 못했어요. 다시 시도해 주세요."

    fun pinActionFailed(endingTrip: Boolean): String = if (endingTrip) END_TRIP_FAILED else SAFE_ARRIVAL_PIN_FAILED

    /** Message under the PIN field after a wrong try or during the lockout; null when the PIN was accepted. */
    fun pinMessage(result: PinResult, now: Instant): String? = when (result) {
        is PinResult.Accepted -> null
        is PinResult.Wrong -> "PIN이 맞지 않습니다 (남은 시도 ${result.triesLeft}회)"
        is PinResult.LockedOut -> {
            // Rounded up, so the screen never says "0초" while still locked.
            val waitS = ((result.until - now).inWholeMilliseconds + 999).floorDiv(1000).coerceAtLeast(1)
            "PIN을 여러 번 틀렸습니다. ${duration(waitS.seconds)} 후에 다시 시도해 주세요"
        }
    }

    /** "PIN 5회 오입력 · 30초 잠금" (`PIN_LOCKOUT`, shown on the contact's timeline). */
    fun pinLockout(failures: Int, locked: Duration): String = "PIN ${failures}회 오입력 · ${duration(locked)} 잠금"

    internal fun gapSeconds(gap: Duration): Int = gap.coerceIn(Duration.ZERO, Int.MAX_VALUE.seconds).inWholeSeconds.toInt()
}
