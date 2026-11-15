package com.coral.gwigaon.arrival

import com.coral.gwigaon.trip.ActiveTrip
import com.coral.gwigaon.trip.DrivingPanelRule
import com.coral.gwigaon.trip.PanelNote
import com.coral.gwigaon.trip.SafeArrivalCloser
import com.coral.gwigaon.trip.TripEvent
import com.coral.gwigaon.trip.TripStatus
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.time.toJavaInstant

/** The parked screens at the destination (`design/d2/proto/arrival.html`, ADR 0020). */
enum class ArrivalStage {
    /** D4 도착: arrived, passenger still seated, the check not open yet. */
    ARRIVED,

    /**
     * 하차 대기 (ADR 0020, replaces D5 and D7): the passenger stayed seated past the delay, the contact was told; a
     * count-up nobody has to answer. Ends when the seat empties, or by the passenger's PIN on a PIN trip.
     */
    WAITING,
}

/** What D4 and the wait screen show; every line is already worded. */
data class ArrivalView(
    val stage: ArrivalStage,
    /** The trip has a PIN (ADR 0015): the PIN action shows, and the copy says so. */
    val pinOn: Boolean,
    /** D4 "도착했어요"; waiting "3분째 하차하지 않음", counting up from the arrival. */
    val title: String,
    /** "역삼동 · 집 · 01:31 도착". */
    val subline: String,
    /** D4: the passenger's notes for the arrival card (empty = no card). */
    val notes: List<PanelNote>,
    /** How the trip ends from here. */
    val howItEnds: String,
    /** D4: a soft suggestion, nothing to press (ADR 0020); null on the wait screen. */
    val hint: String?,
    /** Waiting: who was told ("엄마 · 동생", may be empty) and when. */
    val contacts: String,
    val checkOpenedAt: String?,
)

/** Pure: which arrival screen the running trip is on, if any, and its lines. */
object ArrivalRule {
    const val ARRIVED_TITLE = "도착했어요"
    const val HINT = "하차 전에 승객 상태를 한 번 확인해 주세요"
    const val ENDS_WHEN_OUT = "승객이 내리면 운행이 끝나요."
    const val ENDS_WITH_PIN = "PIN을 입력해야 운행이 끝나요"
    const val ENDS_OUT_OR_PIN = "승객이 내리거나 PIN을 입력하면 운행이 끝나요."
    const val NOTIFIED = "비상 연락처에게 알렸어요"

    fun stage(active: ActiveTrip): ArrivalStage? {
        val s = active.state
        return when {
            s.status == TripStatus.SAFE_ARRIVAL_CHECK -> ArrivalStage.WAITING
            s.status != TripStatus.ARRIVING || !s.arrived -> null
            // A trip stored before ADR 0020 whose driver confirmed: it still waits for the seat to empty.
            s.safeArrivalClosedBy == SafeArrivalCloser.DRIVER_CONFIRMED -> ArrivalStage.WAITING
            !s.safeArrivalConfirmed -> ArrivalStage.ARRIVED
            else -> null // seat empty or the passenger's PIN: the trip ends right away
        }
    }

    fun build(active: ActiveTrip, now: Instant, zone: ZoneId, safeArrivalDelay: Duration): ArrivalView? {
        val stage = stage(active) ?: return null
        val clock = DateTimeFormatter.ofPattern("HH:mm").withZone(zone)
        fun hm(t: Instant) = clock.format(t.toJavaInstant())
        val arrivedAt = active.log.lastOrNull { it.event is TripEvent.Arrived }?.occurredAt
        val openedAt = active.log.lastOrNull { it.event is TripEvent.SafeArrivalCheckOpened }?.occurredAt
        val pinOn = active.trip.pinOn
        return ArrivalView(
            stage = stage,
            pinOn = pinOn,
            title = when (stage) {
                ArrivalStage.ARRIVED -> ARRIVED_TITLE
                ArrivalStage.WAITING -> "${seated(arrivedAt?.let { now - it } ?: safeArrivalDelay)}째 하차하지 않음"
            },
            subline = listOfNotNull(active.trip.destination.name, arrivedAt?.let { "${hm(it)} 도착" }).joinToString(" · "),
            notes = if (stage == ArrivalStage.ARRIVED) DrivingPanelRule.notes(active.trip.arrival) else emptyList(),
            // D4 with a PIN says what the end button needs (owner's copy, A2); the wait screen says both ways out.
            howItEnds = when {
                !pinOn -> ENDS_WHEN_OUT
                stage == ArrivalStage.ARRIVED -> ENDS_WITH_PIN
                else -> ENDS_OUT_OR_PIN
            },
            hint = if (stage == ArrivalStage.ARRIVED) HINT else null,
            contacts = contacts(active.trip.contactLabel),
            checkOpenedAt = openedAt?.let(::hm),
        )
    }

    /** "3분" in whole minutes, or "30초" under a minute (the demo's shortened delay). */
    fun seated(d: Duration): String {
        val c = d.coerceAtLeast(Duration.ZERO)
        return if (c < 1.minutes) "${c.inWholeSeconds}초" else "${c.inWholeMinutes}분"
    }

    /** 설정 stores the 보호자 as "엄마, 동생"; the screen lists them as "엄마 · 동생". */
    fun contacts(label: String): String =
        label.split(',').map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" · ")

    /** What 다시 듣기 says: the notes alone, without the "목적지 근처" lead-in of the first announcement. */
    fun replay(active: ActiveTrip): String? =
        if (active.trip.arrival.isEmpty) null else "승객이 남긴 안내입니다. ${ArrivalTexts.request(active.trip.arrival)}"
}
