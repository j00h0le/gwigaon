package com.coral.gwigaon.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.coral.gwigaon.ui.theme.Gwigaon
import com.coral.gwigaon.ui.theme.GwigaonRadius
import com.coral.gwigaon.ui.theme.GwigaonSize
import com.coral.gwigaon.ui.theme.GwigaonText
import com.coral.gwigaon.ui.theme.palette

/** Disabled keys fade to the prototype's 32 %. */
private const val LOCKED_ALPHA = 0.32f

/** The PIN entered so far as [total] dots; filled dots are solid, empty ones outlined. */
@Composable
fun PinDots(
    filled: Int,
    modifier: Modifier = Modifier,
    total: Int = 4,
    arrangement: Arrangement.Horizontal = Arrangement.spacedBy(GwigaonSize.pinDotGap, Alignment.CenterHorizontally),
) {
    val p = Gwigaon.palette
    Row(
        modifier.testTag("pin_dots").semantics { contentDescription = "$total 자리 중 $filled 자리 입력" },
        horizontalArrangement = arrangement,
    ) {
        repeat(total) { i ->
            val dot = Modifier.size(GwigaonSize.pinDot).background(
                if (i < filled) p.textPrimary else Color.Transparent,
                CircleShape,
            )
            Box(if (i < filled) dot else dot.border(GwigaonSize.outline, p.surfaceHigh, CircleShape))
        }
    }
}

/**
 * 3 × 4 number pad (keys [keyHeight] high, 112 by default): 1–9, blank (or 지우기 with [onClear]), 0, delete.
 * [enabled] false is the lockout state.
 */
@Composable
fun Keypad(
    onDigit: (Char) -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    keyColor: Color = Gwigaon.palette.surface,
    keyHeight: Dp = GwigaonSize.key,
    onClear: (() -> Unit)? = null,
) {
    val rows = listOf("123", "456", "789")
    Column(modifier.alpha(if (enabled) 1f else LOCKED_ALPHA), verticalArrangement = Arrangement.spacedBy(GwigaonSize.keyGap)) {
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(GwigaonSize.keyGap)) {
                row.forEach { d -> DigitKey(d, keyColor, keyHeight, enabled, onDigit) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(GwigaonSize.keyGap)) {
            if (onClear != null) {
                Key(Color.Transparent, keyHeight, enabled, onClear, Modifier.testTag("key_clear").semantics { contentDescription = "모두 지우기" }) {
                    Text("지우기", style = GwigaonText.labelStrong, color = Gwigaon.palette.textSecondary)
                }
            } else {
                Spacer(Modifier.weight(1f).height(keyHeight))
            }
            DigitKey('0', keyColor, keyHeight, enabled, onDigit)
            Key(Color.Transparent, keyHeight, enabled, onDelete, Modifier.testTag("key_delete").semantics { contentDescription = "한 자리 지우기" }) {
                Icon(
                    GwigaonIcons.Delete,
                    contentDescription = null,
                    tint = Gwigaon.palette.textSecondary,
                    modifier = Modifier.size(GwigaonSize.keyIcon),
                )
            }
        }
    }
}

@Composable
private fun RowScope.DigitKey(d: Char, color: Color, height: Dp, enabled: Boolean, onDigit: (Char) -> Unit) =
    Key(color, height, enabled, { onDigit(d) }, Modifier.testTag("key_$d")) {
        Text(d.toString(), style = GwigaonText.key, color = Gwigaon.palette.textPrimary)
    }

@Composable
private fun RowScope.Key(
    color: Color,
    height: Dp,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
    content: @Composable () -> Unit,
) = Surface(
    onClick = onClick,
    enabled = enabled,
    modifier = modifier.weight(1f).height(height),
    shape = RoundedCornerShape(GwigaonRadius.key),
    color = color,
) {
    Box(contentAlignment = Alignment.Center) { content() }
}

@DayNightPreview
@Composable
internal fun KeypadPreview() = PreviewPanel {
    PinDots(filled = 2)
    Keypad(onDigit = {}, onDelete = {})
}
