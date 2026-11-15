package com.coral.gwigaon.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import com.coral.gwigaon.ui.theme.Gwigaon
import com.coral.gwigaon.ui.theme.GwigaonRadius
import com.coral.gwigaon.ui.theme.GwigaonSize
import com.coral.gwigaon.ui.theme.GwigaonSpace
import com.coral.gwigaon.ui.theme.GwigaonText
import com.coral.gwigaon.ui.theme.palette

/** The strongest action on a screen: the window's inverse, 120 high, with an optional second line. */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    sub: String? = null,
    height: Dp = GwigaonSize.button,
    icon: ImageVector? = null,
    style: TextStyle = GwigaonText.button,
) {
    val p = Gwigaon.palette
    val content = if (enabled) p.onPrimary else p.textTertiary
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().heightIn(min = height).semantics { role = Role.Button },
        shape = RoundedCornerShape(GwigaonRadius.card),
        color = if (enabled) p.primary else p.surface,
        contentColor = content,
    ) {
        Column(
            Modifier.padding(horizontal = GwigaonSpace.md, vertical = GwigaonSpace.sm),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(GwigaonSpace.sm)) {
                if (icon != null) Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(GwigaonSize.okIcon))
                Text(text, style = style, color = content)
            }
            if (sub != null) Text(sub, style = GwigaonText.buttonSub, color = content.copy(alpha = 0.72f))
        }
    }
}

/** A quieter action beside or below [PrimaryButton]. */
@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    height: Dp = GwigaonSize.button,
    icon: ImageVector? = null,
) {
    val p = Gwigaon.palette
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().heightIn(min = height).semantics { role = Role.Button },
        shape = RoundedCornerShape(GwigaonRadius.card),
        color = p.surface,
        contentColor = if (enabled) p.textPrimary else p.textTertiary,
    ) {
        Row(
            Modifier.padding(horizontal = GwigaonSpace.md),
            horizontalArrangement = Arrangement.spacedBy(GwigaonSpace.sm, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) Icon(icon, contentDescription = null, modifier = Modifier.size(GwigaonSize.buttonIcon))
            Text(text, style = GwigaonText.buttonSecondary)
        }
    }
}

/** A pill chip (72 high). Selected chips invert; [add] draws the outlined "+ 추가" style. */
@Composable
fun GwigaonChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    add: Boolean = false,
) {
    val p = Gwigaon.palette
    Surface(
        selected = selected,
        onClick = onClick,
        modifier = modifier.height(GwigaonSize.chip),
        shape = CircleShape,
        color = when {
            selected -> p.primary
            add -> Color.Transparent
            else -> p.surface
        },
        contentColor = if (selected) p.onPrimary else p.textSecondary,
        border = if (add && !selected) BorderStroke(GwigaonSize.chipBorder, p.surfaceHigh) else null,
    ) {
        Box(Modifier.padding(horizontal = GwigaonSize.chipPadding), contentAlignment = Alignment.Center) {
            Text(text, style = GwigaonText.chip)
        }
    }
}

/** A settings row with an on/off track (132 × 76): title, a line of detail, green when on. */
@Composable
fun SettingSwitch(
    title: String,
    detail: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = Gwigaon.palette
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(GwigaonRadius.card))
            .background(p.surface)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(horizontal = GwigaonSize.switchPaddingH, vertical = GwigaonSize.switchPaddingV),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(GwigaonSpace.md),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = GwigaonText.title, color = p.textPrimary)
            if (detail != null) {
                Text(
                    detail,
                    style = GwigaonText.label,
                    color = p.textTertiary,
                    modifier = Modifier.padding(top = GwigaonSpace.xs),
                )
            }
        }
        Box(
            Modifier
                .size(GwigaonSize.switchTrackWidth, GwigaonSize.switchTrackHeight)
                .clip(CircleShape)
                .background(if (checked) p.safe else p.switchOff),
        ) {
            val travel = GwigaonSize.switchTrackWidth - GwigaonSize.switchKnob - GwigaonSize.switchKnobInset * 2
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .offset(x = GwigaonSize.switchKnobInset + if (checked) travel else GwigaonSize.none)
                    .size(GwigaonSize.switchKnob)
                    .clip(CircleShape)
                    .background(p.knob),
            )
        }
    }
}

@DayNightPreview
@Composable
internal fun ActionsPreview() = PreviewPanel {
    PrimaryButton("운행 시작", onClick = {}, sub = "기어를 P에서 빼도 시작돼요")
    PrimaryButton("저장", onClick = {}, enabled = false)
    SecondaryButton("나중에", onClick = {})
    Row(horizontalArrangement = Arrangement.spacedBy(GwigaonSpace.sm)) {
        GwigaonChip("엄마", selected = true, onClick = {})
        GwigaonChip("언니", selected = false, onClick = {})
        GwigaonChip("+ 추가", selected = false, onClick = {}, add = true)
    }
    SettingSwitch("대화 감지", "폭언이나 다툼이 들리면 확인해요", checked = true, onCheckedChange = {})
    SettingSwitch("PIN으로 운행 종료", null, checked = false, onCheckedChange = {})
}
