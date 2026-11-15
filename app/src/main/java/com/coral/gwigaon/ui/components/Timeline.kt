package com.coral.gwigaon.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import com.coral.gwigaon.ui.theme.Gwigaon
import com.coral.gwigaon.ui.theme.GwigaonRadius
import com.coral.gwigaon.ui.theme.GwigaonSize
import com.coral.gwigaon.ui.theme.GwigaonSpace
import com.coral.gwigaon.ui.theme.GwigaonText
import com.coral.gwigaon.ui.theme.palette

/** How far an event has aged: live (happening now), past, or the oldest past event (weight 500). */
enum class EventAge { Live, Past, Oldest }

/** A 72 rounded-square icon tile (radius 22); [outline] draws the quiet past style instead of a fill. */
@Composable
fun IconTile(
    icon: ImageVector,
    background: Color,
    tint: Color,
    modifier: Modifier = Modifier,
    outline: Color? = null,
    iconSize: Dp = GwigaonSize.tileIcon,
) {
    val shape = RoundedCornerShape(GwigaonRadius.tile)
    Box(
        modifier
            .size(GwigaonSize.tile)
            .clip(shape)
            .background(background)
            .then(if (outline != null) Modifier.border(GwigaonSize.outline, outline, shape) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(iconSize))
    }
}

/**
 * One trip event (DESIGN.md timeline). Live: amber tile and title, 84 % detail, white counter, no band (D3f D4). Past: one line, outlined grey tile, title 32 at 64 % and the time range it ran; the oldest drops to 500.
 * Past events go quiet through size, weight and outline, never through contrast.
 */
@Composable
fun TimelineEvent(
    icon: ImageVector,
    title: String,
    detail: String?,
    time: String,
    age: EventAge,
    modifier: Modifier = Modifier,
    /** Draws the line down to the next event's tile. */
    connector: Boolean = false,
) {
    val p = Gwigaon.palette
    val live = age == EventAge.Live
    val line = if (connector) {
        Modifier.drawBehind {
            val x = GwigaonSize.tile.toPx() / 2
            val top = GwigaonSize.tile.toPx()
            drawLine(
                p.surface, Offset(x, top), Offset(x, size.height + GwigaonSize.eventGap.toPx()),
                strokeWidth = GwigaonSize.connector.toPx(),
            )
        }
    } else {
        Modifier
    }
    Row(
        modifier
            .then(line)
            .testTag("timeline_event")
            .semantics(mergeDescendants = true) { stateDescription = if (live) "진행 중" else "지난 일" },
        horizontalArrangement = Arrangement.spacedBy(GwigaonSpace.md),
    ) {
        if (live) {
            IconTile(icon, background = p.caution, tint = p.onAccent)
        } else {
            IconTile(
                icon, background = Color.Transparent, tint = p.textTertiary,
                outline = p.surfaceHigh, iconSize = GwigaonSize.pastIcon,
            )
        }
        Column(Modifier.weight(1f).padding(top = if (live) GwigaonSize.none else GwigaonSpace.sm)) {
            Text(
                title,
                style = when (age) {
                    EventAge.Live -> GwigaonText.title
                    EventAge.Past -> GwigaonText.pastTitle
                    EventAge.Oldest -> GwigaonText.pastTitleOldest
                },
                color = if (live) p.cautionText else p.textTertiary,
            )
            if (live && detail != null) {
                Text(
                    detail,
                    style = GwigaonText.label,
                    color = p.textSecondary,
                    modifier = Modifier.padding(top = GwigaonSpace.xs),
                )
            }
        }
        Text(
            time,
            style = if (live) GwigaonText.timeStrong else GwigaonText.time,
            color = if (live) p.textPrimary else p.textTertiary,
            modifier = Modifier.padding(top = if (live) GwigaonSpace.xs else GwigaonSpace.sm),
        )
    }
}

/** One row of a [Timeline]. */
data class TimelineRow(val icon: ImageVector, val title: String, val detail: String?, val time: String, val live: Boolean)

/** Events newest first, joined by a connector; the last past row is the oldest and drops to weight 500. */
@Composable
fun Timeline(rows: List<TimelineRow>, modifier: Modifier = Modifier) {
    // Only whole rows: when the panel is short (check-in card above, half panel) the oldest rows drop out instead of
    // being cut through mid-line, and the last row shown loses its connector. Rows that don't fit are never placed,
    // and the next frame stops composing them.
    BoxWithConstraints(modifier.testTag("timeline")) {
        var shown by remember(rows, constraints.maxHeight) { mutableIntStateOf(rows.size) }
        Layout(content = { Rows(rows, shown) }) { measurables, c ->
            val loose = c.copy(minHeight = 0)
            val gap = GwigaonSize.eventGap.roundToPx()
            val placeables = measurables.map { it.measure(loose) }
            var used = 0
            var fit = 0
            for (p in placeables) {
                val next = used + (if (fit > 0) gap else 0) + p.height
                if (next > c.maxHeight) break
                used = next
                fit++
            }
            if (fit < shown) shown = fit
            val width = if (c.hasBoundedWidth) c.maxWidth else placeables.maxOfOrNull { it.width } ?: 0
            layout(width, used) {
                var y = 0
                placeables.take(fit).forEach { p ->
                    p.placeRelative(0, y)
                    y += p.height + gap
                }
            }
        }
    }
}

/** The first [count] of [rows], aged against the whole list so the oldest shown keeps its quieter weight. */
@Composable
private fun Rows(rows: List<TimelineRow>, count: Int) {
    rows.take(count).forEachIndexed { i, r ->
        val age = when {
            r.live -> EventAge.Live
            i == rows.lastIndex && rows.size > 1 -> EventAge.Oldest
            else -> EventAge.Past
        }
        TimelineEvent(r.icon, r.title, r.detail, r.time, age, connector = i < count - 1)
    }
}

@DayNightPreview
@Composable
internal fun TimelinePreview() = PreviewPanel {
    Timeline(
        listOf(
            TimelineRow(GwigaonIcons.Stop, "정차", "비상 연락처에게 알렸어요", "4분째", live = true),
            TimelineRow(GwigaonIcons.Done, "경로 이탈 → 복귀", null, "01:09–01:11", live = false),
            TimelineRow(GwigaonIcons.Start, "출발", null, "00:58", live = false),
        ),
    )
}
