package com.coral.gwigaon.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.coral.gwigaon.arrival.ui.ArrivalActions
import com.coral.gwigaon.arrival.ui.ArrivalScreen
import com.coral.gwigaon.cues.ui.CheckInCard
import com.coral.gwigaon.cues.ui.MicIndicator
import com.coral.gwigaon.sos.ui.SosScreen
import com.coral.gwigaon.trip.ActiveTrip
import com.coral.gwigaon.trip.DrivingPanel
import com.coral.gwigaon.trip.Screen
import com.coral.gwigaon.trip.ScreenGate
import com.coral.gwigaon.trip.TripEvent
import com.coral.gwigaon.trip.TripStatus
import com.coral.gwigaon.trip.ui.EndTripDialog
import com.coral.gwigaon.trip.ui.PinEntryScreen
import com.coral.gwigaon.trip.ui.TripViewModel
import com.coral.gwigaon.ui.components.SecondaryButton
import com.coral.gwigaon.ui.components.SosHoldBar
import com.coral.gwigaon.vehicle.DrivingState
import com.coral.gwigaon.vehicle.DrivingStateRule
import com.coral.gwigaon.vehicle.Signal
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.time.toJavaInstant

/**
 * Picks the screen from the trip and the driving state on every change ([ScreenGate]); there is no back stack
 * to open a parked-only screen from, and one that is showing is replaced the moment the car starts driving.
 * An open SOS takes over the whole app, driving or parked, until it is cancelled.
 */
@Composable
fun AppNavigation(
    active: ActiveTrip?,
    driving: DrivingState,
    unavailable: Set<Signal>,
    tripViewModel: TripViewModel,
    /** The ended trip's report, or null when there is none to show (T19). Parked only, like setup. */
    report: (@Composable () -> Unit)? = null,
    /** The mic is on for this trip (conflict cues): the indicator shows on every trip screen. */
    listening: Boolean = false,
    setup: @Composable () -> Unit,
) {
    val panel by tripViewModel.panel.collectAsStateWithLifecycle()
    val card = panel?.checkIn
    val cues = CueSlots(
        indicator = if (listening && active != null) ({ MicIndicator(tripViewModel::turnOffCues) }) else null,
        checkIn = if (active?.state?.checkInPending == true && card != null) {
            { CheckInCard(card, onOk = tripViewModel::answerCheckInOk) }
        } else {
            null
        },
    )
    if (active?.state?.sosActive == true) {
        val sentAt = active.log.lastOrNull { it.event is TripEvent.Sos }?.occurredAt
        SosScreen(
            tripViewModel::cancelSos,
            sentAt = sentAt?.let { TIME.format(it.toJavaInstant()) },
            listening = listening,
        )
        return
    }
    val checkOpen = active?.state?.status == TripStatus.SAFE_ARRIVAL_CHECK
    val arrival by tripViewModel.arrival.collectAsStateWithLifecycle()
    val endTrip by tripViewModel.endTrip.collectAsStateWithLifecycle()
    val screen = ScreenGate.screenFor(driving, active != null, checkOpen, reportPending = report != null, arrived = arrival != null)
    // D6: the passenger's PIN takes the whole app over any parked trip screen; driving closes it (TripViewModel).
    if (active != null && screen != Screen.DRIVING && endTrip.open && endTrip.noPin == null) {
        val lock by tripViewModel.pinLock.collectAsStateWithLifecycle()
        PinEntryScreen(
            endTrip, lock, tripViewModel::onPinDigit, tripViewModel::onPinDelete, tripViewModel::onPinClear,
            tripViewModel::dismissEndTrip,
            micIndicator = cues.indicator,
        )
        return
    }
    when (screen) {
        Screen.DRIVING -> DrivingScreen(
            panel.takeIf { active != null },
            Modifier.testTag("driving_screen"),
            DrivingStateRule.cannotTell(unavailable),
            sos = { if (active != null) Sos(tripViewModel, active, compact = panel?.arrivalNotes?.isNotEmpty() == true) },
            micIndicator = cues.indicator,
            checkIn = cues.checkIn,
        )
        Screen.SETUP -> setup()
        Screen.TRIP -> if (active != null) ParkedTrip(active, panel, tripViewModel, cues) else setup()
        Screen.SAFE_ARRIVAL_CHECK, Screen.ARRIVAL -> {
            val view = arrival
            if (active != null && view != null) {
                ArrivalScreen(
                    view,
                    ArrivalActions(
                        onEnd = tripViewModel::endAtArrival,
                        // With the check open the PIN closes it (and the trip ends); otherwise it ends the trip.
                        onEndWithPin = if (checkOpen) tripViewModel::openSafeArrivalPin else tripViewModel::openEndTrip,
                        onReplay = tripViewModel::replayArrival.takeIf { view.notes.isNotEmpty() },
                        onSos = if (active.state.checkInPending) tripViewModel::answerCheckInSos else tripViewModel::fireSos,
                    ),
                    sosHold = tripViewModel.sosHold,
                    micIndicator = cues.indicator,
                    checkIn = cues.checkIn,
                )
                // Only the failure of D4's one-press end opens this (with the reason).
                if (endTrip.open) EndTripDialog(endTrip, tripViewModel::confirmEndWithoutPin, tripViewModel::dismissEndTrip)
            } else {
                setup()
            }
        }
        Screen.REPORT -> report?.invoke() ?: setup()
    }
}

/** The running trip while parked away from the arrival: the driving view plus 운행 종료 (no per-trip QR, ADR 0019). */
@Composable
private fun ParkedTrip(active: ActiveTrip, panel: DrivingPanel?, vm: TripViewModel, cues: CueSlots) {
    val endTrip by vm.endTrip.collectAsStateWithLifecycle()
    DrivingScreen(
        panel,
        Modifier.testTag("trip_screen"),
        sos = { Sos(vm, active, compact = panel?.arrivalNotes?.isNotEmpty() == true) },
        parkedActions = {
            SecondaryButton("운행 종료", vm::openEndTrip, Modifier.testTag("end_trip"))
        },
        micIndicator = cues.indicator,
        checkIn = cues.checkIn,
    )
    if (endTrip.open) EndTripDialog(endTrip, vm::confirmEndWithoutPin, vm::dismissEndTrip)
}

/** Conflict cues on the trip screens: the mic indicator while listening, the check-in card while one is open. */
private class CueSlots(
    val indicator: (@Composable () -> Unit)?,
    val checkIn: (@Composable () -> Unit)?,
)

/**
 * SOS on every active-trip screen (SPEC sos). While a check-in is open the bar is its SOS answer: logged as the
 * answer, then a normal SOS. [compact] while the arrival notes show, so they keep their room (D3f D9, D10).
 */
@Composable
private fun Sos(vm: TripViewModel, active: ActiveTrip, compact: Boolean = false) {
    SosHoldBar(
        if (active.state.checkInPending) vm::answerCheckInSos else vm::fireSos,
        Modifier.fillMaxWidth(),
        hold = vm.sosHold,
        compact = compact,
    )
}

private val TIME = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())
