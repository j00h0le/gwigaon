package com.coral.gwigaon.trip

import com.coral.gwigaon.vehicle.Destination
import com.coral.gwigaon.vehicle.Gear
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/** Refused because 설정 lacks 집 주소 or a 보호자: nothing starts (SPEC trip-core). */
class IncompleteSettingsException : IllegalStateException("settings are incomplete")

/**
 * Starts the trip 설정 describes (ADR 0015), from the driver's 운행 시작 or from shifting out of P. The destination is
 * always 집 주소; changes since the last trip are logged as `SETTINGS_CHANGED`; the navigation gets the destination.
 */
class TripStarter(
    private val sessions: TripSessionRepository,
    private val settings: SettingsRepository,
    private val sendToNavigation: suspend (Destination) -> Result<Unit>,
    /** Turns conflict cues on for the trip that just started (설정's 대화 감지 is on). */
    private val enableCues: (tripId: String) -> Unit,
    private val log: (String, Throwable?) -> Unit = { _, _ -> },
) {
    suspend fun start(source: StartSource): Result<ActiveTrip> {
        val current = settings.settings.value
        if (!current.isComplete) return Result.failure(IncompleteSettingsException())
        val changed = settings.changesSinceLastTrip().getOrElse { e ->
            log("settings baseline unreadable; no SETTINGS_CHANGED this trip", e)
            emptySet()
        }
        return sessions.start(TripSetup.from(current), source, changed).onSuccess { active ->
            settings.markTripStarted().onFailure { e -> log("settings baseline not moved", e) }
            if (current.cues) enableCues(active.trip.id)
            // The trip is logged either way; the car navigation is a convenience.
            sendToNavigation(checkNotNull(current.home)).onFailure { e -> log("navigation not set", e) }
        }
    }

    /** Saves [draft] as 설정, then starts from it with the button (one call for tools that set and start at once). */
    suspend fun saveAndStart(draft: SettingsDraft): Result<ActiveTrip> =
        settings.save(draft).fold({ start(StartSource.BUTTON) }, { Result.failure(it) })

    /**
     * Gear start: shifting out of P while [ready] (the home or start screen is up, 설정 complete, no trip) starts
     * the trip once. Only a P → not-P change counts, so a gear that was already in D, or a later P → D during the
     * trip (not ready then), starts nothing.
     */
    fun watchGear(gear: Flow<Gear?>, ready: () -> Boolean, scope: CoroutineScope): Job = scope.launch {
        var previous: Gear? = null
        gear.collect { next ->
            if (GearStart.shouldStart(previous, next, ready())) {
                start(StartSource.GEAR).onFailure { e -> log("gear start refused", e) }
            }
            previous = next
        }
    }
}

/** Pure part of the gear start. */
object GearStart {
    fun shouldStart(previous: Gear?, next: Gear?, ready: Boolean): Boolean =
        ready && previous == Gear.PARK && next != null && next != Gear.PARK
}
