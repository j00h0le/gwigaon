package com.coral.gwigaon.report

import com.coral.gwigaon.trip.CheckInAnswer
import com.coral.gwigaon.trip.EndReason
import com.coral.gwigaon.trip.EventRecord
import com.coral.gwigaon.trip.EventType
import com.coral.gwigaon.trip.TripEvent

/** How the trip ended, for the report's status line. [UNKNOWN] when the log has no end (unreadable or cut off). */
enum class ReportOutcome { SAFE_ARRIVAL, ENDED_BY_PASSENGER, ENDED_AT_ARRIVAL, ENDED_EARLY, UNKNOWN }

/**
 * The report screen's short 운행 로그 (ADR 0017). Pure. The stored log, the AI's input and the contact page keep
 * every event; only this screen shortens it.
 */
object ReportLog {
    /**
     * Shown: 운행 시작, 목적지 도착, 안전 귀가, the caution events, and SOS with its cancel (the log never hides an
     * SOS). Hidden: near destination, check-in prompts and answers other than no response, the driver's 승객 반응
     * 있음, cleared/resumed events, settings changes, and ends other than safe arrival or early.
     */
    fun shows(event: TripEvent): Boolean = when (event) {
        is TripEvent.TripEnded -> event.endReason == EndReason.SAFE_ARRIVAL || event.endReason == EndReason.ENDED_EARLY
        // A check-in answered with SOS also logs its own SOS, which shows.
        is TripEvent.CheckInAnswered -> event.answer == CheckInAnswer.NO_RESPONSE
        else -> event.type in ALWAYS
    }

    fun filter(log: List<EventRecord>): List<EventRecord> = log.filter { shows(it.event) }

    fun outcome(log: List<EventRecord>): ReportOutcome =
        when (log.lastOrNull { it.event is TripEvent.TripEnded }?.event?.let { (it as TripEvent.TripEnded).endReason }) {
            EndReason.SAFE_ARRIVAL -> ReportOutcome.SAFE_ARRIVAL
            EndReason.ENDED_BY_PASSENGER -> ReportOutcome.ENDED_BY_PASSENGER
            EndReason.ENDED_AT_ARRIVAL -> ReportOutcome.ENDED_AT_ARRIVAL
            EndReason.ENDED_EARLY -> ReportOutcome.ENDED_EARLY
            null -> ReportOutcome.UNKNOWN
        }

    private val ALWAYS = setOf(
        EventType.TRIP_STARTED,
        EventType.ARRIVED,
        EventType.ROUTE_DEVIATION,
        EventType.LONG_STOP,
        EventType.SAFE_ARRIVAL_CHECK_OPENED,
        EventType.MONITORING_INTERRUPTED,
        EventType.PIN_LOCKOUT,
        EventType.SOS,
        EventType.SOS_CANCELLED,
    )
}
