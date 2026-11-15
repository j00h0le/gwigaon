package com.coral.gwigaon.trip.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import com.coral.gwigaon.ui.components.GwigaonIcons
import com.coral.gwigaon.ui.components.IconTile
import com.coral.gwigaon.ui.theme.Gwigaon
import com.coral.gwigaon.ui.theme.GwigaonRadius
import com.coral.gwigaon.ui.theme.GwigaonSize
import com.coral.gwigaon.ui.theme.GwigaonSpace
import com.coral.gwigaon.ui.theme.GwigaonText
import com.coral.gwigaon.ui.theme.palette

/** Big title of a parked screen (52 / 800) with its lede below. */
@Composable
internal fun ScreenTitle(title: String, lede: String?, modifier: Modifier = Modifier) {
    val p = Gwigaon.palette
    Column(modifier) {
        Text(title, style = GwigaonText.status, color = p.textPrimary)
        if (lede != null) {
            Text(lede, style = GwigaonText.body, color = p.textSecondary, modifier = Modifier.padding(top = GwigaonSpace.sm))
        }
    }
}

/** One fact per row (icon tile, title, detail, optional end slot), split by faint lines: no cards in cards. */
@Composable
internal fun InfoRow(
    icon: ImageVector,
    title: AnnotatedString,
    detail: AnnotatedString?,
    modifier: Modifier = Modifier,
    divided: Boolean = false,
    tile: Color = Gwigaon.palette.surfaceHigh,
    tint: Color = Gwigaon.palette.textPrimary,
    detailColor: Color = Gwigaon.palette.textTertiary,
    end: (@Composable RowScope.() -> Unit)? = null,
) {
    val line = Gwigaon.palette.lineFaint
    Row(
        modifier
            .fillMaxWidth()
            .drawBehind { if (divided) drawLine(line, Offset.Zero, Offset(size.width, 0f), GwigaonSize.divider.toPx()) }
            .heightIn(min = GwigaonSize.infoRow)
            .padding(vertical = GwigaonSize.rowPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(GwigaonSpace.md),
    ) {
        IconTile(icon, tile, tint)
        Column(Modifier.weight(1f)) {
            Text(title, style = GwigaonText.rowTitle, color = Gwigaon.palette.textPrimary)
            if (detail != null && detail.isNotEmpty()) {
                Text(detail, style = GwigaonText.label, color = detailColor, modifier = Modifier.padding(top = GwigaonSpace.xs / 2))
            }
        }
        end?.invoke(this)
    }
}

@Composable
internal fun InfoRow(
    icon: ImageVector,
    title: String,
    detail: String?,
    modifier: Modifier = Modifier,
    divided: Boolean = false,
    detailColor: Color = Gwigaon.palette.textTertiary,
    end: (@Composable RowScope.() -> Unit)? = null,
) = InfoRow(
    icon, AnnotatedString(title), detail?.let(::AnnotatedString), modifier, divided,
    detailColor = detailColor, end = end,
)

/** 필수 (inverse) or 선택 (surface) on an intro row. */
@Composable
internal fun Tag(text: String, strong: Boolean) {
    val p = Gwigaon.palette
    Box(
        Modifier
            .height(GwigaonSize.tag)
            .clip(CircleShape)
            .background(if (strong) p.primary else p.surfaceHigh)
            .padding(horizontal = GwigaonSpace.sm),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = GwigaonText.buttonSub, color = if (strong) p.onPrimary else p.textSecondary)
    }
}

/** The small surface action at a row's end (변경, 등록, 지우기). */
@Composable
internal fun RowAction(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, color: Color = Gwigaon.palette.surface) {
    val p = Gwigaon.palette
    Surface(
        onClick = onClick,
        modifier = modifier.heightIn(min = GwigaonSize.chip),
        shape = RoundedCornerShape(GwigaonRadius.control),
        color = color,
        contentColor = p.textPrimary,
    ) {
        Box(Modifier.padding(horizontal = GwigaonSize.chipPadding), contentAlignment = Alignment.Center) {
            Text(text, style = GwigaonText.labelStrong)
        }
    }
}

/** Square back / close button of the 설정 header. */
@Composable
internal fun BackButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val p = Gwigaon.palette
    Surface(
        onClick = onClick,
        modifier = modifier.size(GwigaonSize.back).semantics { contentDescription = label },
        shape = RoundedCornerShape(GwigaonRadius.control),
        color = p.surface,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(GwigaonIcons.Back, contentDescription = null, tint = p.textPrimary, modifier = Modifier.size(GwigaonSize.backIcon))
        }
    }
}

/**
 * The right half of a full-width screen: what the other person will see (settings.html `.side`). Lit gradient,
 * who it is for, a title, and a quiet footnote at the bottom.
 */
@Composable
internal fun SidePanel(
    who: String?,
    icon: ImageVector?,
    title: String?,
    modifier: Modifier = Modifier,
    text: String? = null,
    foot: String? = null,
    content: @Composable ColumnScope.() -> Unit = {},
) {
    val p = Gwigaon.palette
    Column(
        modifier
            .clip(RoundedCornerShape(GwigaonRadius.card))
            .background(Brush.verticalGradient(listOf(p.litTop, p.litBottom)))
            .padding(horizontal = GwigaonSpace.lg, vertical = GwigaonSpace.lg),
    ) {
        if (who != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(GwigaonSpace.sm)) {
                if (icon != null) Icon(icon, contentDescription = null, tint = p.textTertiary, modifier = Modifier.size(GwigaonSize.backIcon))
                Text(who, style = GwigaonText.labelStrong, color = p.textTertiary)
            }
        }
        if (title != null) {
            Text(title, style = GwigaonText.title, color = p.textPrimary, modifier = Modifier.padding(top = GwigaonSpace.sm))
        }
        if (text != null) {
            Text(text, style = GwigaonText.detail, color = p.textSecondary, modifier = Modifier.padding(top = GwigaonSpace.sm))
        }
        content()
        if (foot != null) {
            Spacer(Modifier.weight(1f))
            val line = p.line
            Text(
                foot,
                style = GwigaonText.label,
                color = p.textTertiary,
                modifier = Modifier
                    .fillMaxWidth()
                    .drawBehind { drawLine(line, Offset.Zero, Offset(size.width, 0f), GwigaonSize.divider.toPx()) }
                    .padding(top = GwigaonSpace.md),
            )
        }
    }
}

/** A scaled-down slice of the driver panel inside [SidePanel]. */
@Composable
internal fun MiniPanel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .padding(top = GwigaonSpace.lg)
            .fillMaxWidth()
            .clip(RoundedCornerShape(GwigaonRadius.card))
            .background(Gwigaon.palette.window)
            .padding(GwigaonSpace.lg),
        content = content,
    )
}

/** Radio mark for a picked row. */
@Composable
internal fun Radio(selected: Boolean) {
    val p = Gwigaon.palette
    Box(
        Modifier
            .size(GwigaonSize.radio)
            .clip(CircleShape)
            .then(if (selected) Modifier.background(p.primary) else Modifier.border(GwigaonSize.outline, p.surfaceHigh, CircleShape)),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) Box(Modifier.size(GwigaonSize.radioDot).clip(CircleShape).background(p.window))
    }
}
