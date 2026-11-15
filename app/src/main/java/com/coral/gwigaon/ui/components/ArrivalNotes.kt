package com.coral.gwigaon.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import com.coral.gwigaon.ui.theme.Gwigaon
import com.coral.gwigaon.ui.theme.GwigaonRadius
import com.coral.gwigaon.ui.theme.GwigaonSize
import com.coral.gwigaon.ui.theme.GwigaonSpace
import com.coral.gwigaon.ui.theme.GwigaonText
import com.coral.gwigaon.ui.theme.palette

/** One note the passenger left: the fact ("지하 2층 B구역") and its label ("주차"). */
data class ArrivalNote(val icon: ImageVector, val fact: String, val label: String)

/** The green "음성 안내" pill; tappable to replay when [onClick] is set. */
@Composable
fun VoicePill(text: String, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    val p = Gwigaon.palette
    Row(
        modifier
            .height(GwigaonSize.voicePill)
            .clip(RoundedCornerShape(percent = 50))
            .background(p.safeTint)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(horizontal = GwigaonSize.voicePillPadding)
            .testTag("voice_pill"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(GwigaonSpace.xs),
    ) {
        Icon(GwigaonIcons.Speaker, contentDescription = null, tint = p.safeText, modifier = Modifier.size(GwigaonSize.voiceIcon))
        Text(text, style = GwigaonText.pill, color = p.safeText)
    }
}

/**
 * The arrival card (DESIGN.md): a softly lit surface, header with the voice pill, one row per note split by faint
 * dividers. Shown near the destination and on arrival.
 */
@Composable
fun ArrivalNotesCard(
    notes: List<ArrivalNote>,
    modifier: Modifier = Modifier,
    title: String = "승객이 남긴 도착 안내",
    onReplay: (() -> Unit)? = null,
    /** "음성 안내" while driving; "다시 듣기" at the destination (D4), where it replays the notes. */
    voiceLabel: String = "음성 안내",
    /** Tighter padding where the card shares the panel with more buttons (parked mid-trip, D3f D10): all notes fit. */
    dense: Boolean = false,
) {
    val p = Gwigaon.palette
    val rowPad = if (dense) GwigaonSpace.xs else GwigaonSize.rowPadding
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(GwigaonRadius.card))
            .background(Brush.verticalGradient(listOf(p.litTop, p.litBottom)))
            .drawWithContent {
                drawContent()
                drawLine(p.line, Offset(0f, 0.5f), Offset(size.width, 0.5f), strokeWidth = GwigaonSize.hairline.toPx())
            }
            .padding(
                start = GwigaonSize.cardPaddingH, end = GwigaonSize.cardPaddingH,
                top = if (dense) GwigaonSpace.md else GwigaonSize.cardPaddingTop,
                bottom = if (dense) GwigaonSpace.sm else GwigaonSize.cardPaddingBottom,
            )
            .testTag("arrival_card"),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(bottom = if (dense) GwigaonSpace.sm else GwigaonSpace.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(title, style = GwigaonText.title, color = p.textPrimary, modifier = Modifier.weight(1f))
            VoicePill(voiceLabel, onClick = onReplay)
        }
        HorizontalDivider(thickness = GwigaonSize.divider, color = p.line)
        notes.forEachIndexed { i, note ->
            if (i > 0) HorizontalDivider(thickness = GwigaonSize.divider, color = p.lineFaint)
            Row(
                Modifier.padding(vertical = rowPad).testTag("arrival_line"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(GwigaonSpace.md),
            ) {
                IconTile(note.icon, background = p.surfaceHigh, tint = p.textPrimary)
                Column {
                    Text(note.fact, style = GwigaonText.title, color = p.textPrimary)
                    Text(note.label, style = GwigaonText.detail, color = p.textTertiary)
                }
            }
        }
    }
}

@DayNightPreview
@Composable
internal fun ArrivalNotesPreview() = PreviewPanel {
    ArrivalNotesCard(
        listOf(
            ArrivalNote(GwigaonIcons.Pin, "지하 2층 B구역", "주차"),
            ArrivalNote(GwigaonIcons.Door, "101동 출입구", "비밀번호는 승객에게"),
            ArrivalNote(GwigaonIcons.Key, "차 키", "승객에게 직접"),
        ),
        onReplay = {},
    )
}
