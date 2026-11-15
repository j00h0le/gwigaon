package com.coral.gwigaon.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import com.coral.gwigaon.trip.MonitoringConfig
import com.coral.gwigaon.ui.theme.Gwigaon
import com.coral.gwigaon.ui.theme.GwigaonRadius
import com.coral.gwigaon.ui.theme.GwigaonSize
import com.coral.gwigaon.ui.theme.GwigaonSpace
import com.coral.gwigaon.ui.theme.GwigaonText
import com.coral.gwigaon.ui.theme.palette
import kotlin.time.Duration

private fun seconds(d: Duration): String {
    val tenths = (d.inWholeMilliseconds + 50) / 100
    return if (tenths % 10 == 0L) "${tenths / 10}" else "${tenths / 10}.${tenths % 10}"
}

/** The turn-signal fallback, from the same config the detector uses (the window isn't named, D3f D2). */
fun turnSignalHint(config: MonitoringConfig = MonitoringConfig.DEFAULT): String =
    "또는 좌우 깜빡이를 ${config.sosTurnSignalChanges}번 번갈아 켜주세요"

/** Track, clockwise fill and a core that grows with [progress] (DESIGN.md SOS hold bar). */
@Composable
private fun SosRing(progress: Float, size: Dp, tag: String, content: @Composable () -> Unit = {}) {
    val p = Gwigaon.palette
    Box(
        Modifier
            .size(size)
            .testTag(tag)
            .semantics { progressBarRangeInfo = ProgressBarRangeInfo(progress, 0f..1f) },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = GwigaonSize.ringStroke.toPx()
            val inset = stroke / 2
            val arc = Size(this.size.width - 2 * inset, this.size.height - 2 * inset)
            val origin = Offset(inset, inset)
            drawArc(p.sosTrack, 0f, 360f, false, origin, arc, style = Stroke(stroke))
            drawArc(p.sos, -90f, 360f * progress, false, origin, arc, style = Stroke(stroke, cap = StrokeCap.Round))
            val core = if (progress >= 1f) 1f else progress * 0.6f
            drawCircle(p.sos, radius = arc.width / 2 * 0.62f * core)
        }
        content()
    }
}

/**
 * The driving SOS (DESIGN.md): full width, 150 high, dark red ground; hold [hold] to fire. "SOS" sits inside the
 * ring like [SosSquare]; the hint names the turn-signal fallback at the 32 dp driving floor, in the SOS colour.
 * [compact] (near the destination, where the notes need the room, D3f D9): 112 high, no hint. Accessibility
 * services get a "SOS 보내기" action.
 */
@Composable
fun SosHoldBar(
    onFire: () -> Unit,
    modifier: Modifier = Modifier,
    hold: Duration = MonitoringConfig.DEFAULT.sosHold,
    hint: String = turnSignalHint(),
    compact: Boolean = false,
) {
    val p = Gwigaon.palette
    val progress = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val fire = rememberUpdatedState(onFire)
    val value = progress.value
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = if (compact) GwigaonSize.key else GwigaonSize.sosBar)
            .clip(RoundedCornerShape(GwigaonRadius.window))
            .background(if (value > 0f) p.sosGroundPressed else p.sosGround)
            .semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = "SOS, ${seconds(hold)}초 길게 누르기"
                stateDescription = if (value > 0f) "누르는 중" else ""
                customActions = listOf(CustomAccessibilityAction("SOS 보내기") { fire.value(); true })
            }
            .holdToFire(progress, hold.inWholeMilliseconds, scope, fire)
            .padding(start = GwigaonSize.sosBarStart, end = GwigaonSpace.lg, top = GwigaonSpace.sm, bottom = GwigaonSpace.sm)
            .testTag("sos_button"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(GwigaonSize.sosBarGap),
    ) {
        SosRing(value, if (compact) GwigaonSize.sosRingCompact else GwigaonSize.sosRing, "sos_ring") {
            Text("SOS", style = GwigaonText.small, color = p.sosText)
        }
        Column {
            Text("SOS 길게 누르기", style = GwigaonText.action, color = p.sosText)
            if (!compact) {
                Text(
                    hint,
                    style = GwigaonText.sosHint,
                    color = p.sosText,
                    modifier = Modifier.padding(top = GwigaonSpace.xs).testTag("sos_hold_hint"),
                )
            }
        }
    }
}

/** The parked SOS at the destination (D4 bottom row): a 150 square beside the trip-ending action. */
@Composable
fun SosSquare(
    onFire: () -> Unit,
    modifier: Modifier = Modifier,
    hold: Duration = MonitoringConfig.DEFAULT.sosHold,
) {
    val p = Gwigaon.palette
    val progress = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val fire = rememberUpdatedState(onFire)
    val value = progress.value
    Box(
        modifier
            .size(GwigaonSize.sosBar)
            .clip(RoundedCornerShape(GwigaonRadius.window))
            .background(if (value > 0f) p.sosGroundPressed else p.sosGround)
            .semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = "SOS, ${seconds(hold)}초 길게 누르기"
                customActions = listOf(CustomAccessibilityAction("SOS 보내기") { fire.value(); true })
            }
            .holdToFire(progress, hold.inWholeMilliseconds, scope, fire)
            .testTag("sos_square"),
        contentAlignment = Alignment.Center,
    ) {
        SosRing(value, GwigaonSize.sosSquareRing, "sos_square_ring") {
            Text("SOS", style = GwigaonText.small, color = p.sosText)
        }
    }
}

@DayNightPreview
@Composable
internal fun SosPreview() = PreviewPanel {
    SosHoldBar(onFire = {})
    SosSquare(onFire = {})
}
