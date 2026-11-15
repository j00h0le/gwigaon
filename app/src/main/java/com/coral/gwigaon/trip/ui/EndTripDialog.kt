package com.coral.gwigaon.trip.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.window.Dialog
import com.coral.gwigaon.trip.EndOption
import com.coral.gwigaon.ui.components.PrimaryButton
import com.coral.gwigaon.ui.components.SecondaryButton
import com.coral.gwigaon.ui.components.holdToFire
import com.coral.gwigaon.ui.theme.Gwigaon
import com.coral.gwigaon.ui.theme.GwigaonRadius
import com.coral.gwigaon.ui.theme.GwigaonSize
import com.coral.gwigaon.ui.theme.GwigaonSpace
import com.coral.gwigaon.ui.theme.GwigaonText
import com.coral.gwigaon.ui.theme.palette
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Parked only, for a trip without a PIN (ADR 0015): the mid-trip end. The PIN itself is typed on [PinEntryScreen]
 * (D6), not here. [EndTripUiState.noPin] says which end applies.
 */
@Composable
fun EndTripDialog(
    state: EndTripUiState,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val option = state.noPin ?: return
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(GwigaonRadius.window),
            color = Gwigaon.palette.surface,
            modifier = Modifier.widthIn(max = DIALOG_WIDTH).testTag("end_trip_dialog"),
        ) {
            NoPinEnd(option, state, onConfirm, onDismiss)
        }
    }
}

/** How long the early end is held (ADR 0015): long enough that a brush never ends a trip. */
val END_HOLD: Duration = 2.seconds

/** Title and line under it (D3f D11): plain "운행을 종료할까요?"; away "목적지 밖이에요" + "1.6km 남음". */
internal fun endDialogTexts(option: EndOption): Pair<String, String?> = when (option) {
    is EndOption.Hold -> "목적지 밖이에요" to option.left
    EndOption.Blocked -> "아직 종료할 수 없어요" to "하차 확인이 끝나면 종료할 수 있어요"
    else -> "운행을 종료할까요?" to null
}

/**
 * A trip without a PIN (ADR 0015, 0020): plain 운행 종료 within 1 km of the destination, a 2 s hold farther away,
 * or why it can't end yet while the safe-arrival check is open. On arrival, D4's own button ends it.
 */
@Composable
private fun NoPinEnd(option: EndOption, state: EndTripUiState, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val p = Gwigaon.palette
    val (title, line) = endDialogTexts(option)
    Column(Modifier.padding(GwigaonSpace.xl)) {
        Text(title, style = GwigaonText.headline, color = p.textPrimary, modifier = Modifier.testTag("end_trip_title"))
        if (line != null) {
            Text(line, style = GwigaonText.body, color = p.textSecondary, modifier = Modifier.padding(top = GwigaonSpace.sm).testTag("end_trip_reason"))
        }
        state.message?.let { Text(it, style = GwigaonText.labelStrong, color = p.sosText, modifier = Modifier.padding(top = GwigaonSpace.sm)) }
        Row(Modifier.padding(top = GwigaonSpace.xl), horizontalArrangement = Arrangement.spacedBy(GwigaonSpace.md)) {
            SecondaryButton("취소", onDismiss, Modifier.weight(1f).testTag("end_trip_cancel"))
            when (option) {
                is EndOption.Hold -> HoldButton("길게 눌러 종료", onConfirm, Modifier.weight(1f))
                EndOption.Plain -> PrimaryButton(
                    text = if (state.busy) "종료 중…" else "운행 종료",
                    onClick = onConfirm,
                    enabled = !state.busy,
                    modifier = Modifier.weight(1f).testTag("end_trip_confirm"),
                )
                else -> Unit
            }
        }
    }
}

/** Fires once after [END_HOLD] of holding, filling from the left as it goes; an early release does nothing. */
@Composable
private fun HoldButton(text: String, onHeld: () -> Unit, modifier: Modifier) {
    val p = Gwigaon.palette
    val progress = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val held = rememberUpdatedState(onHeld)
    Box(
        modifier
            // A fixed height: the fill below is fillMaxHeight, which a min-height-only box lets grow to the dialog.
            .height(GwigaonSize.button)
            .clip(RoundedCornerShape(GwigaonRadius.card))
            .background(p.sosGround)
            .semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = text
                customActions = listOf(CustomAccessibilityAction("운행 종료") { held.value(); true })
            }
            .holdToFire(progress, END_HOLD.inWholeMilliseconds, scope, held)
            .testTag("end_trip_hold"),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.align(Alignment.CenterStart).fillMaxHeight().fillMaxWidth(progress.value).background(p.sosGroundPressed))
        Text(text, style = GwigaonText.button, color = p.sosText)
    }
}

private val DIALOG_WIDTH = GwigaonSize.sideColumn
