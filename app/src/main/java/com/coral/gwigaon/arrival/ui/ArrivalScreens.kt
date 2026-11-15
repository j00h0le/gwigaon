package com.coral.gwigaon.arrival.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import com.coral.gwigaon.arrival.ArrivalRule
import com.coral.gwigaon.arrival.ArrivalStage
import com.coral.gwigaon.arrival.ArrivalView
import com.coral.gwigaon.trip.MonitoringConfig
import com.coral.gwigaon.trip.PanelNoteKind
import com.coral.gwigaon.ui.components.ArrivalNote
import com.coral.gwigaon.ui.components.ArrivalNotesCard
import com.coral.gwigaon.ui.components.GwigaonIcons
import com.coral.gwigaon.ui.components.IconTile
import com.coral.gwigaon.ui.components.PrimaryButton
import com.coral.gwigaon.ui.components.SosHoldBar
import com.coral.gwigaon.ui.components.SosSquare
import com.coral.gwigaon.ui.components.StatusBlock
import com.coral.gwigaon.ui.theme.Gwigaon
import com.coral.gwigaon.ui.theme.GwigaonRadius
import com.coral.gwigaon.ui.theme.GwigaonSize
import com.coral.gwigaon.ui.theme.GwigaonSpace
import com.coral.gwigaon.ui.theme.GwigaonText
import com.coral.gwigaon.ui.theme.palette
import kotlin.time.Duration

/** What the arrival screens can do; each stage uses the ones it shows. */
class ArrivalActions(
    /** D4 PIN off: 운행 종료 (one press, `ended_at_arrival`). */
    val onEnd: () -> Unit = {},
    /** PIN trips: PIN으로 종료하기 — the passenger types the PIN (D6). On the wait screen it closes the open check. */
    val onEndWithPin: () -> Unit = {},
    /** D4: 다시 듣기, or null when there is nothing to say. */
    val onReplay: (() -> Unit)? = null,
    val onSos: () -> Unit = {},
)

/**
 * Parked at the destination (ADR 0020), one layout per [ArrivalView.stage]: D4 도착, or the count-up wait (amber)
 * once the passenger stayed seated. Parked only: [com.coral.gwigaon.trip.ScreenGate] replaces it the moment the car
 * drives. SOS stays on both: a square beside the end action on D4, the full bar last on the wait screen.
 */
@Composable
fun ArrivalScreen(
    view: ArrivalView,
    actions: ArrivalActions,
    modifier: Modifier = Modifier,
    sosHold: Duration = MonitoringConfig.DEFAULT.sosHold,
    micIndicator: (@Composable () -> Unit)? = null,
    checkIn: (@Composable () -> Unit)? = null,
) {
    val p = Gwigaon.palette
    val waiting = view.stage == ArrivalStage.WAITING
    Column(
        modifier
            .fillMaxSize()
            .background(p.window)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(start = GwigaonSpace.panelSide, end = GwigaonSpace.panelSide, top = GwigaonSpace.panelTop, bottom = GwigaonSpace.panelSide)
            .testTag(if (waiting) "arrival_wait_screen" else "arrival_screen"),
    ) {
        StatusBlock(
            view.title, view.subline,
            Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }.testTag("arrival_title"),
            caution = waiting, trailing = micIndicator,
        )
        if (checkIn != null) Column(Modifier.padding(top = GwigaonSpace.md)) { checkIn() }
        if (waiting) Waiting(view, actions, sosHold) else Arrived(view, actions, sosHold)
    }
}

/** D4: the passenger's notes with 다시 듣기, how the trip ends and a soft hint under them, then square SOS + the end action. */
@Composable
private fun ColumnScope.Arrived(view: ArrivalView, actions: ArrivalActions, sosHold: Duration) {
    Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
        if (view.notes.isNotEmpty()) {
            ArrivalNotesCard(
                view.notes.map { n ->
                    val icon = when (n.kind) {
                        PanelNoteKind.PARKING -> GwigaonIcons.Pin
                        PanelNoteKind.ENTRANCE -> GwigaonIcons.Door
                        PanelNoteKind.KEY -> GwigaonIcons.Key
                    }
                    ArrivalNote(icon, n.fact, n.label)
                },
                Modifier.padding(top = GwigaonSpace.lg),
                onReplay = actions.onReplay,
                voiceLabel = "다시 듣기",
            )
        }
        // A1: under the card with clear space, not squeezed above the end row.
        HowItEnds(
            if (view.pinOn) GwigaonIcons.Lock else GwigaonIcons.Seat, view.howItEnds, view.hint,
            Modifier.padding(top = if (view.notes.isNotEmpty()) GwigaonSpace.xl else GwigaonSpace.lg),
        )
    }
    Row(
        Modifier.padding(top = GwigaonSpace.md).fillMaxWidth().height(GwigaonSize.sosBar),
        horizontalArrangement = Arrangement.spacedBy(GwigaonSpace.sm),
    ) {
        SosSquare(actions.onSos, hold = sosHold)
        PrimaryButton(
            if (view.pinOn) PIN_END else "운행 종료",
            if (view.pinOn) actions.onEndWithPin else actions.onEnd,
            Modifier.weight(1f).testTag("arrival_end"),
            height = GwigaonSize.sosBar,
            style = GwigaonText.action,
        )
    }
}

/**
 * The wait (ADR 0020): who was told and when, how it ends; nobody is asked to do anything. A PIN trip gets
 * PIN으로 종료하기; a trip without one has no end button here (one tap must not switch monitoring off, ADR 0007).
 */
@Composable
private fun ColumnScope.Waiting(view: ArrivalView, actions: ArrivalActions, sosHold: Duration) {
    val p = Gwigaon.palette
    Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
        Row(
            Modifier
                .padding(top = GwigaonSpace.lg)
                .fillMaxWidth()
                .background(p.cautionTint, RoundedCornerShape(GwigaonRadius.card))
                .padding(horizontal = GwigaonSpace.md, vertical = GwigaonSize.eventGap)
                .testTag("safe_arrival_notified"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(GwigaonSpace.md),
        ) {
            IconTile(GwigaonIcons.Warn, background = p.caution, tint = p.onAccent)
            Column(Modifier.weight(1f)) {
                Text(ArrivalRule.NOTIFIED, style = GwigaonText.rowTitle, color = p.cautionText)
                if (view.contacts.isNotEmpty()) Text(view.contacts, style = GwigaonText.label, color = p.textSecondary)
            }
            view.checkOpenedAt?.let { Text(it, style = GwigaonText.timeStrong, color = p.textPrimary) }
        }
        HowItEnds(
            if (view.pinOn) GwigaonIcons.Lock else GwigaonIcons.Seat, view.howItEnds, null,
            Modifier.padding(top = GwigaonSpace.xl),
        )
    }
    if (view.pinOn) {
        PrimaryButton(PIN_END, actions.onEndWithPin, Modifier.padding(top = GwigaonSpace.md).testTag("arrival_end"))
    }
    SosHoldBar(actions.onSos, Modifier.padding(top = GwigaonSpace.sm), hold = sosHold)
}

private const val PIN_END = "PIN으로 종료하기"

/** How the trip ends from here, with an optional soft line under it: not a card, nothing to press. */
@Composable
private fun HowItEnds(icon: ImageVector, text: String, hint: String?, modifier: Modifier = Modifier) {
    val p = Gwigaon.palette
    Column(modifier.fillMaxWidth().semantics(mergeDescendants = true) {}.testTag("arrival_how")) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(GwigaonSpace.sm)) {
            Icon(icon, contentDescription = null, tint = p.textSecondary, modifier = Modifier.size(GwigaonSize.howIcon))
            Text(text, style = GwigaonText.note, color = p.textPrimary, modifier = Modifier.weight(1f))
        }
        if (hint != null) {
            Text(
                hint,
                style = GwigaonText.label,
                color = p.textTertiary,
                modifier = Modifier.padding(top = GwigaonSpace.xs, start = GwigaonSize.howIcon + GwigaonSpace.sm).testTag("arrival_hint"),
            )
        }
    }
}
