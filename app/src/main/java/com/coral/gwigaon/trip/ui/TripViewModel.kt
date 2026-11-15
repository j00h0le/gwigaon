package com.coral.gwigaon.trip.ui

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.coral.gwigaon.arrival.ArrivalRule
import com.coral.gwigaon.arrival.ArrivalTexts
import com.coral.gwigaon.arrival.ArrivalView
import com.coral.gwigaon.arrival.Speaker
import com.coral.gwigaon.cues.CueConfig
import com.coral.gwigaon.cues.CueMonitor
import com.coral.gwigaon.route.RouteEta
import com.coral.gwigaon.sos.SosController
import com.coral.gwigaon.trip.ActiveTrip
import com.coral.gwigaon.trip.CheckInAnswer
import com.coral.gwigaon.trip.TripEndRule
import com.coral.gwigaon.trip.DrivingPanel
import com.coral.gwigaon.trip.DrivingPanelRule
import com.coral.gwigaon.trip.EndOption
import com.coral.gwigaon.trip.MonitoringConfig
import com.coral.gwigaon.trip.PinEntry
import com.coral.gwigaon.trip.PinLock
import com.coral.gwigaon.trip.PinPurpose
import com.coral.gwigaon.trip.PinResult
import com.coral.gwigaon.trip.SafeArrivalCloser
import com.coral.gwigaon.trip.SosSource
import com.coral.gwigaon.trip.TripSessionRepository
import com.coral.gwigaon.trip.TripTexts
import com.coral.gwigaon.vehicle.DrivingState
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import java.time.ZoneId

data class EndTripUiState(
    val open: Boolean = false,
    val purpose: PinPurpose = PinPurpose.END_TRIP,
    val pin: String = "",
    val message: String? = null,
    val busy: Boolean = false,
    /** A trip without a PIN (ADR 0015): plain, hold or blocked (the dialog); null asks the PIN (D6). */
    val noPin: EndOption? = null,
)

/**
 * The running trip's actions. SOS ([fireSos], [cancelSos]) works driving or parked and goes through [SosController].
 * On a trip with a PIN, ending needs it, checked by [TripSessionRepository.endByPassenger] (the lockout lives there
 * too); the same PIN screen (D6) lets the passenger close the safe-arrival wait ([PinPurpose.SAFE_ARRIVAL]); the driver
 * is asked nothing (ADR 0020). PIN entry is parked-only: it closes and
 * forgets the typed digits when driving starts. Without a PIN, D4's 운행 종료 ends at once ([endAtArrival]).
 */
class TripViewModel(
    private val sessions: TripSessionRepository,
    private val drivingState: StateFlow<DrivingState>,
    private val clock: Clock,
    private val sos: SosController,
    val sosHold: Duration = MonitoringConfig.DEFAULT.sosHold,
    /** Conflict cues (check-in answers, the indicator's off), or null where the app runs without them. */
    private val cues: CueMonitor? = null,
    /** The arrival time for the panel (ADR 0018); null = not known yet. */
    eta: StateFlow<RouteEta?> = MutableStateFlow(null),
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val checkInTimeout: Duration = CueConfig.DEFAULT.checkInTimeout,
    /** How long the passenger may stay seated before the check opens, for the arrival screen's copy. */
    private val safeArrivalDelay: Duration = MonitoringConfig.DEFAULT.safeArrivalDelay,
    /** Speaks 다시 듣기 on arrival; null where the app runs without speech. */
    private val speaker: Speaker? = null,
) : ViewModel() {
    private val _endTrip = MutableStateFlow(EndTripUiState())
    val endTrip: StateFlow<EndTripUiState> = _endTrip.asStateFlow()

    init {
        viewModelScope.launch {
            drivingState.collect { if (it == DrivingState.DRIVING) _endTrip.value = EndTripUiState() }
        }
        // The keypad and the end dialog belong to one trip: a trip that ends under them (the seat empties during the
        // check) must not leave them open over the next one (Checkpoint F review R1).
        viewModelScope.launch {
            sessions.active.map { it?.trip?.id }.distinctUntilChanged().collect { _endTrip.value = EndTripUiState() }
        }
    }

    /**
     * Once a second while a trip runs: running counts ("3분째", the check-in's seconds) move without a new event.
     * One ticker shared by the panel, the arrival view and the PIN lock; with no trip it waits for one.
     */
    private val ticks = flow {
        while (currentCoroutineContext().isActive) {
            emit(clock.now())
            if (sessions.active.value == null) sessions.active.first { it != null } else delay(1.seconds)
        }
    }.shareIn(viewModelScope, SharingStarted.Eagerly, replay = 1)

    /** What the driver panel shows; null without a trip. */
    val panel: StateFlow<DrivingPanel?> = combine(sessions.active, eta, ticks) { active, e, now ->
        active?.let { DrivingPanelRule.build(it, e, now, zone, checkInTimeout, remainingM(it)) }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, sessions.active.value?.let { DrivingPanelRule.build(it, eta.value, clock.now(), zone, checkInTimeout, remainingM(it)) })

    private fun remainingM(active: ActiveTrip): Int? = TripEndRule.remainingM(sessions.lastFix, active.trip.destination)

    /** D4 / D5 / D7 while parked at the destination; null elsewhere. */
    val arrival: StateFlow<ArrivalView?> = combine(sessions.active, ticks) { active, now ->
        active?.let { ArrivalRule.build(it, now, zone, safeArrivalDelay) }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, sessions.active.value?.let { ArrivalRule.build(it, clock.now(), zone, safeArrivalDelay) })

    /** The PIN lockout with its countdown, read from the trip so it survives closing the PIN screen. */
    val pinLock: StateFlow<PinLock?> = combine(sessions.active, ticks) { active, now ->
        active?.let { PinEntry.lock(it.trip.pinAttempts, now) }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val parked get() = drivingState.value == DrivingState.PARKED

    /** Not cancelled with the screen: once the hold completed, the SOS gets stored. */
    fun fireSos() {
        viewModelScope.launch { withContext(NonCancellable) { sos.fire(SosSource.ON_SCREEN) } }
    }

    fun cancelSos() {
        viewModelScope.launch { withContext(NonCancellable) { sos.cancel() } }
    }

    /** The check-in card's 괜찮아요; not cancelled with the screen. */
    fun answerCheckInOk() = answerCheckIn(CheckInAnswer.OK)

    /** SOS while a check-in is open (the bar): logged as the answer, then a normal SOS. */
    fun answerCheckInSos() = answerCheckIn(CheckInAnswer.SOS)

    private fun answerCheckIn(answer: CheckInAnswer) {
        val monitor = cues ?: return
        viewModelScope.launch { withContext(NonCancellable) { monitor.answer(answer) } }
    }

    /** The mic indicator's one tap: listening off for the rest of the trip. */
    fun turnOffCues() {
        cues?.turnOff()
    }

    fun openEndTrip() {
        if (!parked) return
        val option = sessions.endOption(parked = true)
        _endTrip.value = EndTripUiState(open = true, noPin = option?.takeUnless { it == EndOption.NeedsPin })
    }

    /** The passenger's PIN closes the check only on a trip with a PIN (ADR 0015). */
    fun openSafeArrivalPin() {
        if (parked && sessions.active.value?.trip?.pinOn == true) {
            _endTrip.value = EndTripUiState(open = true, purpose = PinPurpose.SAFE_ARRIVAL)
        }
    }

    /** D4 without a PIN: 운행 종료 ends the trip with one press (`ended_at_arrival`); nothing to confirm. */
    fun endAtArrival() {
        if (!parked || _endTrip.value.busy || sessions.endOption(parked = true) != EndOption.Plain) return
        _endTrip.value = EndTripUiState(busy = true)
        viewModelScope.launch {
            sessions.endWithoutPin(parked).onSuccess { _endTrip.value = EndTripUiState() }.onFailure { e ->
                Log.e(TAG, "end at arrival failed", e)
                _endTrip.value = reopenAfterFailure { EndTripUiState(open = true, noPin = sessions.endOption(parked), message = TripTexts.END_TRIP_FAILED) }
            }
        }
    }

    /** D4's 다시 듣기: the passenger's notes again. Speech never throws into the screen. */
    fun replayArrival() {
        val text = sessions.active.value?.let(ArrivalRule::replay) ?: return
        runCatching { speaker?.speak(text) }.onFailure { Log.e(TAG, "replay not spoken", it) }
    }

    /** No-PIN end: one press near the destination, after the hold elsewhere (the dialog enforces the hold). */
    fun confirmEndWithoutPin() {
        val s = _endTrip.value
        if (!s.open || s.busy || !parked) return
        _endTrip.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            sessions.endWithoutPin(parked).onSuccess { _endTrip.value = EndTripUiState() }.onFailure { e ->
                Log.e(TAG, "end without PIN failed", e)
                _endTrip.value = reopenAfterFailure { EndTripUiState(open = true, noPin = sessions.endOption(parked), message = TripTexts.END_TRIP_FAILED) }
            }
        }
    }

    /** After a failed end, the dialog comes back only while there is still a trip to end. */
    private fun reopenAfterFailure(state: () -> EndTripUiState): EndTripUiState =
        if (sessions.active.value == null) EndTripUiState() else state()

    fun dismissEndTrip() {
        _endTrip.value = EndTripUiState()
    }

    /** D6 keypad: the 4th digit checks the PIN. Ignored while a check runs or the PIN is locked. */
    fun onPinDigit(d: Char) {
        val s = _endTrip.value
        if (!s.open || s.noPin != null || s.busy || locked()) return
        val pin = PinEntry.type(s.pin, d)
        _endTrip.value = s.copy(pin = pin, message = null)
        if (PinEntry.complete(pin)) confirmEndTrip()
    }

    private fun locked() = sessions.active.value?.let { PinEntry.lock(it.trip.pinAttempts, clock.now()) } != null

    fun onPinDelete() = _endTrip.update { if (it.busy) it else it.copy(pin = PinEntry.delete(it.pin)) }

    fun onPinClear() = _endTrip.update { if (it.busy) it else it.copy(pin = "") }

    /**
     * Checks the typed PIN; a wrong one clears the dots (D6). What it does comes from the trip's status now
     * ([PinEntry.closesCheck]), so a keypad opened from D4 still works after the check opens underneath it.
     */
    fun confirmEndTrip() {
        val s = _endTrip.value
        if (!s.open || s.busy || !parked || !PinEntry.complete(s.pin)) return
        val purpose = PinEntry.purpose(sessions.active.value?.state?.status)
        _endTrip.update { it.copy(busy = true, message = null, purpose = purpose) }
        viewModelScope.launch {
            val result = when (purpose) {
                PinPurpose.END_TRIP -> sessions.endByPassenger(s.pin)
                PinPurpose.SAFE_ARRIVAL ->
                    sessions.confirmSafeArrivalByPin(s.pin, ArrivalTexts.confirmed(SafeArrivalCloser.PASSENGER_PIN))
            }
            _endTrip.value = when {
                !parked || sessions.active.value == null -> EndTripUiState()
                result.isFailure -> {
                    Log.e(TAG, "end trip failed", result.exceptionOrNull())
                    EndTripUiState(open = true, purpose = purpose, message = TripTexts.pinActionFailed(purpose == PinPurpose.END_TRIP))
                }
                else -> when (val r = result.getOrThrow()) {
                    is PinResult.Accepted -> EndTripUiState()
                    is PinResult.Wrong -> EndTripUiState(open = true, purpose = purpose, message = PinEntry.wrong(r.triesLeft))
                    is PinResult.LockedOut -> EndTripUiState(open = true, purpose = purpose) // the lock view says it
                }
            }
        }
    }

    private companion object {
        const val TAG = "TripViewModel"
    }
}
