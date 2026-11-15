package com.coral.gwigaon.arrival

import com.coral.gwigaon.route.Geo
import com.coral.gwigaon.trip.ActiveTrip
import com.coral.gwigaon.trip.ArrivalInstructions
import com.coral.gwigaon.trip.SafeArrivalCloser
import com.coral.gwigaon.trip.TripStatus
import com.coral.gwigaon.trip.TripTexts
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/** What the arrival card shows and what TTS says (SPEC arrival). */
object ArrivalTexts {
    /** `NEAR_DESTINATION.reason`: "목적지 500m 이내", the wording of the seed data the contact page was built on. */
    fun nearDestination(thresholdM: Double): String = "목적지 ${Geo.formatDistance(thresholdM)} 이내"

    /**
     * One short line per instruction given, at most three (parking, entrance, key), so the card stays within the
     * driving limit of three lines. Key presets already start with "키는", so they aren't labeled again.
     */
    fun cardLines(arrival: ArrivalInstructions): List<String> = buildList {
        arrival.parking.trim().takeIf { it.isNotEmpty() }?.let { add("주차 · $it") }
        arrival.entrance.trim().takeIf { it.isNotEmpty() }?.let { add("출입 · $it") }
        arrival.keyNote.trim().takeIf { it.isNotEmpty() }?.let { add(if (it.startsWith("키")) it else "키 · $it") }
    }

    /** The card shows near the destination only, and only when the passenger left instructions. */
    fun card(active: ActiveTrip?): List<String> =
        if (active?.state?.status == TripStatus.ARRIVING) cardLines(active.trip.arrival) else emptyList()

    /** The first announcement: "목적지 근처입니다. " + [request]. */
    fun spoken(arrival: ArrivalInstructions): String =
        request(arrival).let { if (it.isEmpty()) "목적지 근처입니다." else "목적지 근처입니다. $it" }

    /** What is asked of the driver: Claude's stored sentence (ADR 0021), or the car's [template] without one. */
    fun request(arrival: ArrivalInstructions): String = arrival.spoken.trim().ifEmpty { template(arrival) }

    /**
     * The fallback when there is no stored sentence (offline save, notes changed): order 출입문, 주차, 차 키, ending
     * 감사합니다. "출입문은 101동 정문, 주차는 지하 2층, 차 키는 승객에게 직접 부탁드립니다. 감사합니다." A key note that
     * already starts with "키" isn't labeled again.
     */
    fun template(arrival: ArrivalInstructions): String {
        val parts = buildList {
            arrival.entrance.trim().takeIf { it.isNotEmpty() }?.let { add("출입문은 $it") }
            arrival.parking.trim().takeIf { it.isNotEmpty() }?.let { add("주차는 $it") }
            arrival.keyNote.trim().takeIf { it.isNotEmpty() }?.let { add(if (it.startsWith("키")) "차 $it" else "차 키는 $it") }
        }
        return if (parts.isEmpty()) "" else "${parts.joinToString(", ")} 부탁드립니다. 감사합니다."
    }

    const val ARRIVED = "목적지 도착"

    /** "도착 후 3분째 승객이 하차하지 않음"; whole minutes once there are any (the demo's 30 s reads "30초"). */
    fun checkOpened(seated: Duration): String {
        val shown = if (seated < 1.minutes) seated else seated.inWholeMinutes.minutes
        return "도착 후 ${TripTexts.duration(shown)}째 승객이 하차하지 않음"
    }

    fun confirmed(closedBy: SafeArrivalCloser): String = when (closedBy) {
        SafeArrivalCloser.SEAT_EMPTY -> "승객 하차 확인"
        SafeArrivalCloser.PASSENGER_PIN -> "승객이 PIN으로 상태 확인"
        SafeArrivalCloser.DRIVER_CONFIRMED -> "기사가 승객 반응 확인"
    }

    const val SAFE_ARRIVAL_ENDED = "안전 귀가"

    /** After a driver's confirmation the trip ends only once the passenger gets out (ADR 0007). */
    const val SAFE_ARRIVAL_ENDED_AFTER_DRIVER = "승객 하차 후 안전 귀가"
}
