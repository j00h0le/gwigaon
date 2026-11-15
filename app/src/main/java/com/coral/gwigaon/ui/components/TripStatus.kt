package com.coral.gwigaon.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import com.coral.gwigaon.ui.theme.Gwigaon
import com.coral.gwigaon.ui.theme.GwigaonRadius
import com.coral.gwigaon.ui.theme.GwigaonSize
import com.coral.gwigaon.ui.theme.GwigaonSpace
import com.coral.gwigaon.ui.theme.GwigaonText
import com.coral.gwigaon.ui.theme.palette

/**
 * The top of the glance zone: one status line (52/800) and the destination under it. [caution] turns the status
 * amber (the arrival check while it is open). [trailing] sits at the end of the destination row (the mic pill);
 * with it the row keeps a fixed height, so nothing moves when the pill goes away.
 */
@Composable
fun StatusBlock(
    status: String,
    destination: String?,
    modifier: Modifier = Modifier,
    caution: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
    fixedRow: Boolean = false,
) {
    val p = Gwigaon.palette
    Column(modifier.testTag("status_block")) {
        Text(
            status,
            style = GwigaonText.status,
            color = if (caution) p.cautionText else p.textPrimary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.testTag("status_line"),
        )
        if (destination != null || trailing != null) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = if (fixedRow) GwigaonSpace.xs else GwigaonSpace.sm)
                    .then(if (fixedRow) Modifier.height(GwigaonSize.destinationRow) else Modifier),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(GwigaonSpace.sm),
            ) {
                Text(
                    destination.orEmpty(),
                    style = GwigaonText.body,
                    color = p.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).testTag("destination"),
                )
                trailing?.invoke()
            }
        }
    }
}

/** The arrival time as the large tabular numeral, with what it means beside it ("도착 · 6.2 km"). */
@Composable
fun ArrivalTime(eta: String, detail: String, modifier: Modifier = Modifier) {
    val p = Gwigaon.palette
    Row(modifier) {
        Text(eta, style = GwigaonText.numeral, color = p.textPrimary, modifier = Modifier.alignByBaseline())
        Spacer(Modifier.width(GwigaonSpace.md))
        Text(
            detail,
            style = GwigaonText.bodyTime,
            color = p.textTertiary,
            modifier = Modifier.alignByBaseline(),
        )
    }
}

/** A caution event placed where it happened on the way home, as fractions of the route. */
data class ProgressMark(val start: Float, val end: Float, val live: Boolean)

/**
 * Trip progress: purple for distance covered, amber marks where a caution happened (dimmed once over), each cut out
 * from its neighbours by a gap in the window colour.
 */
@Composable
fun TripProgressBar(fraction: Float, marks: List<ProgressMark>, modifier: Modifier = Modifier) {
    val p = Gwigaon.palette
    val shape = RoundedCornerShape(GwigaonRadius.progress)
    val done = fraction.coerceIn(0f, 1f)
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(GwigaonSize.progressBar)
            .clip(shape)
            .background(p.surface)
            .testTag("trip_progress")
            .semantics { progressBarRangeInfo = ProgressBarRangeInfo(done, 0f..1f) },
    ) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(done).clip(shape).background(p.progress))
        marks.forEach { m ->
            val start = m.start.coerceIn(0f, 1f)
            val end = m.end.coerceIn(start, 1f)
            Box(
                Modifier
                    .offset(x = maxWidth * start - GwigaonSize.markGap)
                    .width(maxWidth * (end - start) + GwigaonSize.markGap * 2)
                    .fillMaxHeight()
                    .background(p.window)
                    .padding(horizontal = GwigaonSize.markGap)
                    .background(if (m.live) p.caution else p.cautionPast)
                    .testTag(if (m.live) "progress_mark_live" else "progress_mark_past"),
            )
        }
    }
}

@DayNightPreview
@Composable
internal fun TripStatusPreview() = PreviewPanel {
    Column {
        StatusBlock("4분째 멈춰 있어요", "역삼동 · 집 (예시)")
        ArrivalTime("01:27", "도착 · 6.2 km", Modifier.padding(top = GwigaonSpace.lg))
        TripProgressBar(
            0.62f,
            listOf(ProgressMark(0.54f, 0.57f, live = false), ProgressMark(0.61f, 0.62f, live = true)),
            Modifier.padding(top = GwigaonSpace.md),
        )
    }
}
