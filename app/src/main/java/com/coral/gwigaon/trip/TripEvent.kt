package com.coral.gwigaon.trip

import kotlin.time.Instant

/**
 * Event wire names, exactly as the `trip_events.type` CHECK constraint lists them
 * (the trip_events migration in supabase/migrations). Never rename an entry: the cloud and contact page match on it.
 */
enum class EventType(val wire: String) {
    TRIP_STARTED("TRIP_STARTED"),
    ROUTE_DEVIATION("ROUTE_DEVIATION"),
    DEVIATION_CLEARED("DEVIATION_CLEARED"),
    LONG_STOP("LONG_STOP"),
    LONG_STOP_CLEARED("LONG_STOP_CLEARED"),
    SOS("SOS"),
    SOS_CANCELLED("SOS_CANCELLED"),
    MONITORING_INTERRUPTED("MONITORING_INTERRUPTED"),
    MONITORING_RESUMED("MONITORING_RESUMED"),
    NEAR_DESTINATION("NEAR_DESTINATION"),
    ARRIVED("ARRIVED"),
    SAFE_ARRIVAL_CHECK_OPENED("SAFE_ARRIVAL_CHECK_OPENED"),
    SAFE_ARRIVAL_CONFIRMED("SAFE_ARRIVAL_CONFIRMED"),
    CHECK_IN_PROMPTED("CHECK_IN_PROMPTED"),
    CHECK_IN_ANSWERED("CHECK_IN_ANSWERED"),
    TRIP_ENDED("TRIP_ENDED"),

    /** Added after the first schema (T16, owner decision at Checkpoint C); the pin_lockout_event migration allows it. */
    PIN_LOCKOUT("PIN_LOCKOUT"),

    /** 설정 changed since the last trip (ADR 0015); the settings_changed_event migration allows it. */
    SETTINGS_CHANGED("SETTINGS_CHANGED"),
}

/** What started the trip (ADR 0015): the driver's 운행 시작 button, or shifting out of P with 설정 complete. */
enum class StartSource(val wire: String) { BUTTON("button"), GEAR("gear") }

enum class SosSource(val wire: String) { TURN_SIGNAL("turn_signal"), ON_SCREEN("on_screen") }

/** Who closed the safe-arrival check (SPEC: seat empty, passenger PIN, or driver confirms "승객 반응 있음"). */
enum class SafeArrivalCloser(val wire: String) {
    SEAT_EMPTY("seat_empty"),
    PASSENGER_PIN("passenger_pin"),
    DRIVER_CONFIRMED("driver_confirmed"),
}

/** What raised a check-in (SPEC conflict-cues): sustained loudness, a distress phrase, or profanity. */
enum class CueKind(val wire: String) { LOUD("loud"), DISTRESS("distress"), PROFANITY("profanity") }

/** `no_response`: nobody answered the card before it timed out. */
enum class CheckInAnswer(val wire: String) { OK("ok"), SOS("sos"), NO_RESPONSE("no_response") }

/**
 * `TRIP_ENDED.payload.reason`; the contact page shows "안전 귀가" for `safe_arrival`. Without a PIN (ADR 0015) the
 * driver ends the trip: `ended_at_arrival` once arriving or after the check (owner 2026-11-13), `ended_early`
 * anywhere else, which the 보호자 sees as a warning.
 */
enum class EndReason(val wire: String) {
    SAFE_ARRIVAL("safe_arrival"),
    ENDED_BY_PASSENGER("ended_by_passenger"),
    ENDED_AT_ARRIVAL("ended_at_arrival"),
    ENDED_EARLY("ended_early"),
}

/**
 * Everything that can happen on a trip. [reason] is the plain Korean sentence shown to people
 * (contact page, report); the rule that raises the event writes it.
 */
sealed interface TripEvent {
    val type: EventType
    val reason: String?

    /** [source] is null on trips logged before ADR 0015 (payload `{}`). */
    data class TripStarted(val source: StartSource? = null, override val reason: String? = null) : TripEvent {
        override val type get() = EventType.TRIP_STARTED
    }

    data class RouteDeviation(val distanceM: Int, override val reason: String? = null) : TripEvent {
        override val type get() = EventType.ROUTE_DEVIATION
    }

    data class DeviationCleared(override val reason: String? = null) : TripEvent {
        override val type get() = EventType.DEVIATION_CLEARED
    }

    data class LongStop(val durationS: Int, override val reason: String? = null) : TripEvent {
        override val type get() = EventType.LONG_STOP
    }

    data class LongStopCleared(override val reason: String? = null) : TripEvent {
        override val type get() = EventType.LONG_STOP_CLEARED
    }

    data class Sos(val source: SosSource, override val reason: String? = null) : TripEvent {
        override val type get() = EventType.SOS
    }

    data class SosCancelled(override val reason: String? = null) : TripEvent {
        override val type get() = EventType.SOS_CANCELLED
    }

    data class MonitoringInterrupted(val gapS: Int, override val reason: String? = null) : TripEvent {
        override val type get() = EventType.MONITORING_INTERRUPTED
    }

    data class MonitoringResumed(override val reason: String? = null) : TripEvent {
        override val type get() = EventType.MONITORING_RESUMED
    }

    data class NearDestination(override val reason: String? = null) : TripEvent {
        override val type get() = EventType.NEAR_DESTINATION
    }

    data class Arrived(override val reason: String? = null) : TripEvent {
        override val type get() = EventType.ARRIVED
    }

    data class SafeArrivalCheckOpened(override val reason: String? = null) : TripEvent {
        override val type get() = EventType.SAFE_ARRIVAL_CHECK_OPENED
    }

    data class SafeArrivalConfirmed(val closedBy: SafeArrivalCloser, override val reason: String? = null) : TripEvent {
        override val type get() = EventType.SAFE_ARRIVAL_CONFIRMED
    }

    data class CheckInPrompted(val level: Int, val cue: CueKind, override val reason: String? = null) : TripEvent {
        override val type get() = EventType.CHECK_IN_PROMPTED
    }

    data class CheckInAnswered(val answer: CheckInAnswer, override val reason: String? = null) : TripEvent {
        override val type get() = EventType.CHECK_IN_ANSWERED
    }

    /**
     * Too many wrong PINs: the PIN is locked for [lockedS] seconds after [failures] wrong tries in all. Someone in the
     * car tried to end the trip or close the arrival check without knowing the PIN, so the contact sees it.
     */
    data class PinLockout(val failures: Int, val lockedS: Int, override val reason: String? = null) : TripEvent {
        override val type get() = EventType.PIN_LOCKOUT
    }

    /** Which parts of 설정 changed since the last trip; never the values (SPEC trip-core). */
    data class SettingsChanged(val fields: List<SettingsField>, override val reason: String? = null) : TripEvent {
        override val type get() = EventType.SETTINGS_CHANGED
    }

    data class TripEnded(val endReason: EndReason, override val reason: String? = null) : TripEvent {
        override val type get() = EventType.TRIP_ENDED
    }
}

/** An event as logged: one `trip_events` row without the trip id. [seq] is assigned by the event log (T7). */
data class EventRecord(
    val seq: Int,
    val occurredAt: Instant,
    val lat: Double?,
    val lng: Double?,
    val event: TripEvent,
)
