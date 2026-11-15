package com.coral.gwigaon.trip.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import com.coral.gwigaon.trip.PinEntry
import com.coral.gwigaon.trip.PinHasher
import com.coral.gwigaon.trip.PinLock
import com.coral.gwigaon.ui.components.GwigaonIcons
import com.coral.gwigaon.ui.components.Keypad
import com.coral.gwigaon.ui.components.PinDots
import com.coral.gwigaon.ui.theme.Gwigaon
import com.coral.gwigaon.ui.theme.GwigaonRadius
import com.coral.gwigaon.ui.theme.GwigaonSize
import com.coral.gwigaon.ui.theme.GwigaonSpace
import com.coral.gwigaon.ui.theme.GwigaonText
import com.coral.gwigaon.ui.theme.palette

/**
 * D6 (`design/d2/proto/arrival.html`): the passenger types the trip's PIN, to end the trip or to close the
 * safe-arrival check. Parked only, like every screen it opens from. The 4th digit checks it; a wrong PIN clears
 * the dots and says how many tries are left; a lockout greys the keypad and counts down ([lock]). No SOS bar here:
 * the screen is in the passenger's hands, and the turn-signal SOS still works. 닫기 returns to the driver's screen.
 */
@Composable
fun PinEntryScreen(
    state: EndTripUiState,
    lock: PinLock?,
    onDigit: (Char) -> Unit,
    onDelete: () -> Unit,
    onClear: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    /** The mic indicator while conflict cues listen: the mic stays on here too, so the passenger sees it (SPEC). */
    micIndicator: (@Composable () -> Unit)? = null,
) {
    val p = Gwigaon.palette
    Box(
        modifier
            .fillMaxSize()
            .background(p.window)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(start = GwigaonSpace.panelSide, end = GwigaonSpace.panelSide, top = GwigaonSpace.panelTop, bottom = GwigaonSpace.panelSide)
            .testTag("pin_entry_screen"),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(Modifier.widthIn(max = GwigaonSize.sideColumn).fillMaxSize()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(GwigaonSpace.md)) {
                Surface(
                    onClick = onClose,
                    modifier = Modifier
                        .size(GwigaonSize.pinClose)
                        .semantics { contentDescription = "닫고 기사님 화면으로" }
                        .testTag("pin_entry_close"),
                    shape = RoundedCornerShape(GwigaonRadius.control),
                    color = p.surface,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(GwigaonIcons.Close, contentDescription = null, tint = p.textPrimary, modifier = Modifier.size(GwigaonSize.pinCloseIcon))
                    }
                }
                Text("승객이 직접 입력해요", style = GwigaonText.labelStrong, color = p.textTertiary, modifier = Modifier.weight(1f))
                micIndicator?.invoke()
            }
            val center = Modifier.fillMaxWidth().padding(top = GwigaonSpace.sm)
            Text(
                if (lock != null) "잠시 후 다시 입력해 주세요" else "PIN을 입력해 주세요",
                style = GwigaonText.status,
                color = p.textPrimary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = GwigaonSpace.lg).testTag("pin_entry_title"),
            )
            Text(
                if (lock != null) PinEntry.locked(lock.failures) else "설정에서 정한 ${PinHasher.PIN_LENGTH}자리예요",
                style = GwigaonText.body,
                color = p.textSecondary,
                textAlign = TextAlign.Center,
                modifier = center,
            )
            if (lock != null) {
                Column(
                    Modifier.fillMaxWidth().padding(top = GwigaonSpace.lg),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(GwigaonSpace.xs),
                ) {
                    Text(PinEntry.countdown(lock.left), style = GwigaonText.numeral, color = p.textPrimary, modifier = Modifier.testTag("pin_lock_countdown"))
                    Text("후에 다시 입력할 수 있어요", style = GwigaonText.detail, color = p.textTertiary)
                }
            } else {
                PinDots(state.pin.length, Modifier.fillMaxWidth().padding(top = GwigaonSpace.lg))
                Text(
                    state.message.orEmpty(),
                    style = GwigaonText.detail,
                    color = if (state.message != null) p.sosText else p.textTertiary,
                    textAlign = TextAlign.Center,
                    minLines = 1,
                    modifier = center
                        .padding(top = GwigaonSpace.xs)
                        .semantics { liveRegion = LiveRegionMode.Assertive }
                        .testTag("pin_message"),
                )
            }
            Spacer(Modifier.weight(1f))
            Keypad(
                onDigit, onDelete,
                Modifier.fillMaxWidth().padding(top = GwigaonSpace.md),
                enabled = lock == null, // typing while the PIN is checked is ignored by the ViewModel
                onClear = onClear,
            )
        }
    }
}
