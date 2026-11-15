package com.coral.gwigaon.cues.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.coral.gwigaon.cues.CueTexts
import androidx.compose.foundation.clickable
import androidx.compose.material3.Icon
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import com.coral.gwigaon.trip.CheckInView
import com.coral.gwigaon.trip.CueKind
import com.coral.gwigaon.ui.components.DayNightPreview
import com.coral.gwigaon.ui.components.GwigaonIcons
import com.coral.gwigaon.ui.components.PreviewPanel
import com.coral.gwigaon.ui.components.PrimaryButton
import com.coral.gwigaon.ui.theme.GwigaonRadius
import com.coral.gwigaon.ui.theme.GwigaonSize
import com.coral.gwigaon.ui.theme.GwigaonSpace
import com.coral.gwigaon.ui.theme.GwigaonText
import com.coral.gwigaon.ui.theme.palette
import com.coral.gwigaon.ui.theme.Gwigaon

/**
 * The check-in (SPEC conflict-cues, `design/d2/proto/checkin.html`): a question to the driver, never spoken, never
 * an alert to the 보호자. The chip names the cue, level 2 turns the card amber, the counter says when an unanswered
 * check-in is recorded. One answer here, 괜찮아요; SOS is the hold bar under the card, as everywhere else.
 */
@Composable
fun CheckInCard(card: CheckInView, onOk: () -> Unit, modifier: Modifier = Modifier, sosBelow: Boolean = true) {
    val p = Gwigaon.palette
    val repeated = card.level >= 2
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(GwigaonRadius.card))
            .background(if (repeated) p.cautionTint else p.surface)
            .padding(
                start = GwigaonSize.cardPaddingH, end = GwigaonSize.cardPaddingH,
                top = GwigaonSize.cardPaddingBottom, bottom = GwigaonSize.cardPaddingH,
            )
            .semantics { liveRegion = LiveRegionMode.Polite }
            .testTag("check_in_card"),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier
                    .height(GwigaonSize.cueChip)
                    .clip(CircleShape)
                    .background(if (repeated) p.caution else p.surfaceHigh)
                    .padding(start = GwigaonSpace.sm, end = GwigaonSize.voicePillPadding),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(GwigaonSpace.xs),
            ) {
                val ink = if (repeated) p.onAccent else p.textPrimary
                Icon(
                    if (card.cue == CueKind.LOUD) GwigaonIcons.Loud else GwigaonIcons.Word, contentDescription = null,
                    tint = ink, modifier = Modifier.size(GwigaonSize.cueIcon),
                )
                Text(CueTexts.cueChip(card.cue), style = GwigaonText.labelStrong, color = ink)
            }
            Spacer(Modifier.weight(1f))
            Text(
                CueTexts.countdown(card.secondsLeft),
                style = GwigaonText.time.copy(fontWeight = GwigaonText.detail.fontWeight),
                color = p.textTertiary,
                modifier = Modifier.testTag("check_in_countdown"),
            )
        }
        Text(
            CueTexts.cardText(card.cue, card.level, sosBelow),
            style = GwigaonText.body,
            color = p.textSecondary,
            modifier = Modifier.padding(top = GwigaonSpace.md),
        )
        PrimaryButton(
            CueTexts.CARD_OK, onOk,
            Modifier.padding(top = GwigaonSpace.md).testTag("check_in_ok"),
            icon = GwigaonIcons.Done, style = GwigaonText.action,
        )
    }
}

/**
 * Shown on every trip screen while the mic is on: a green pill at the end of the destination row. The whole pill is
 * one target (76 dp): one tap turns listening off for the rest of the trip.
 */
@Composable
fun MicIndicator(onTurnOff: () -> Unit, modifier: Modifier = Modifier) {
    val p = Gwigaon.palette
    Row(
        modifier
            .height(GwigaonSize.destinationRow)
            .clip(CircleShape) // no pill ground (D3f D3): a dot and the words
            .clickable(role = Role.Button, onClick = onTurnOff)
            .semantics(mergeDescendants = true) { contentDescription = "${CueTexts.INDICATOR}, ${CueTexts.INDICATOR_OFF_HINT}" }
            .padding(start = GwigaonSize.voicePillPadding, end = GwigaonSpace.md)
            .testTag("mic_indicator"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(GwigaonSpace.sm),
    ) {
        Box(Modifier.size(GwigaonSize.micDot).background(p.safe, CircleShape))
        Text(CueTexts.INDICATOR, style = GwigaonText.labelStrong, color = p.safeText, maxLines = 1)
    }
}

@DayNightPreview
@Composable
internal fun CheckInPreview() = PreviewPanel {
    CheckInCard(CheckInView(CueKind.LOUD, level = 1, secondsLeft = 24), onOk = {})
    CheckInCard(CheckInView(CueKind.PROFANITY, level = 2, secondsLeft = 18), onOk = {})
    MicIndicator(onTurnOff = {})
}
