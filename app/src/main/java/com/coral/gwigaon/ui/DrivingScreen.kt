package com.coral.gwigaon.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.testTag
import com.coral.gwigaon.trip.DrivingPanel
import com.coral.gwigaon.trip.PanelEvent
import com.coral.gwigaon.trip.PanelEventKind
import com.coral.gwigaon.trip.PanelNoteKind
import com.coral.gwigaon.ui.components.ArrivalNote
import com.coral.gwigaon.ui.components.ArrivalNotesCard
import com.coral.gwigaon.ui.components.ArrivalTime
import com.coral.gwigaon.ui.components.GwigaonIcons
import com.coral.gwigaon.ui.components.ProgressMark
import com.coral.gwigaon.ui.components.StatusBlock
import com.coral.gwigaon.ui.components.Timeline
import com.coral.gwigaon.ui.components.TimelineRow
import com.coral.gwigaon.ui.components.TripProgressBar
import com.coral.gwigaon.ui.theme.Gwigaon
import com.coral.gwigaon.ui.theme.GwigaonSpace
import com.coral.gwigaon.ui.theme.GwigaonText
import com.coral.gwigaon.ui.theme.palette

/**
 * The one screen allowed while driving (SPEC trip-core, `design/d2/proto/driving.html`), laid out for the half
 * panel beside the navigation (ADR 0003). The glance zone never moves: status → destination (with the mic pill) →
 * arrival time → progress. The middle shows the check-in card above the timeline, or the passenger's arrival notes
 * near the destination, or the timeline; the SOS bar is anchored at the bottom. Nothing to type, text ≥ 32 for
 * everything the driver reads. [parkedActions] is where the parked trip view adds its buttons and must stay empty
 * while driving.
 */
@Composable
fun DrivingScreen(
    /** null: driving with no trip. */
    panel: DrivingPanel?,
    modifier: Modifier = Modifier,
    /** Gear and location both unreadable: DRIVING is only the fail-safe, so the no-trip message says why. */
    cannotTell: Boolean = false,
    sos: @Composable () -> Unit = {},
    parkedActions: (@Composable ColumnScope.() -> Unit)? = null,
    /** The mic pill, while conflict cues listen (SPEC conflict-cues); at the end of the destination row. */
    micIndicator: (@Composable () -> Unit)? = null,
    /** The check-in card while one is open; it sits above the timeline. */
    checkIn: (@Composable () -> Unit)? = null,
) {
    val p = Gwigaon.palette
    Column(
        modifier
            .fillMaxSize()
            .background(p.window)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(
                start = GwigaonSpace.panelSide, end = GwigaonSpace.panelSide,
                top = GwigaonSpace.panelTop, bottom = GwigaonSpace.panelSide,
            ),
    ) {
        if (panel == null) {
            NoTripWhileDriving(cannotTell, Modifier.weight(1f))
            return@Column
        }
        StatusBlock(
            panel.status, panel.subtext, Modifier.testTag("trip_status"),
            caution = panel.caution, trailing = micIndicator, fixedRow = true,
        )
        ArrivalTime(panel.eta, panel.etaDetail, Modifier.padding(top = GwigaonSpace.md).testTag("arrival_time"))
        TripProgressBar(
            panel.progress,
            panel.marks.map { ProgressMark(it.start, it.end, it.live) },
            Modifier.padding(top = GwigaonSpace.md),
        )
        Column(Modifier.weight(1f).fillMaxWidth().clipToBounds()) {
            when {
                checkIn != null -> {
                    Column(Modifier.padding(top = GwigaonSpace.lg)) { checkIn() }
                    Timeline(panel.timeline.map(::row), Modifier.padding(top = GwigaonSpace.lg))
                }
                panel.arrivalNotes.isNotEmpty() -> ArrivalNotesCard(
                    panel.arrivalNotes.map { n ->
                        val icon = when (n.kind) {
                            PanelNoteKind.PARKING -> GwigaonIcons.Pin
                            PanelNoteKind.ENTRANCE -> GwigaonIcons.Door
                            PanelNoteKind.KEY -> GwigaonIcons.Key
                        }
                        ArrivalNote(icon, n.fact, n.label)
                    },
                    Modifier.padding(top = if (parkedActions != null) GwigaonSpace.md else GwigaonSpace.xl),
                    dense = parkedActions != null,
                )
                else -> Timeline(panel.timeline.map(::row), Modifier.padding(top = GwigaonSpace.xl))
            }
        }
        Column(Modifier.padding(top = GwigaonSpace.md)) { sos() }
        if (parkedActions != null) Column(Modifier.padding(top = GwigaonSpace.md), content = parkedActions)
    }
}

/** Live rows carry their own icon; past rows a check, except the start's dot. */
private fun row(e: PanelEvent) = TimelineRow(
    icon = when {
        e.kind == PanelEventKind.START -> GwigaonIcons.Start
        !e.live -> GwigaonIcons.Done
        e.kind == PanelEventKind.STOP -> GwigaonIcons.Stop
        e.kind == PanelEventKind.NO_RESPONSE -> GwigaonIcons.Ask
        else -> GwigaonIcons.Warn
    },
    title = e.title,
    detail = e.detail,
    time = e.time,
    live = e.live,
)

/**
 * Driving with no trip: setup is parked-only, so all there is to say is how to get to it. When the app can't
 * read the gear or location it can't see P at all, so it says how to fix that instead.
 */
@Composable
private fun NoTripWhileDriving(cannotTell: Boolean, modifier: Modifier = Modifier) {
    val p = Gwigaon.palette
    Column(modifier.fillMaxWidth()) {
        Text("주차(P) 후에 운행을 시작할 수 있어요", style = GwigaonText.status, color = p.textPrimary, maxLines = 3)
        if (cannotTell) {
            Text(
                "기어 신호를 읽을 수 없어요. 설정에서 귀가온의 위치 권한을 허용해 주세요",
                style = GwigaonText.body,
                color = p.textSecondary,
                maxLines = 3,
                modifier = Modifier.padding(top = GwigaonSpace.md).testTag("cannot_tell"),
            )
        }
    }
}
