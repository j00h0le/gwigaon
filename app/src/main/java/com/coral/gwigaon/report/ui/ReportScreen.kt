package com.coral.gwigaon.report.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.coral.gwigaon.report.AiState
import com.coral.gwigaon.report.ReportOutcome
import com.coral.gwigaon.report.ReportTexts
import com.coral.gwigaon.report.ReportTone
import com.coral.gwigaon.report.ReportUiState
import com.coral.gwigaon.report.ReportViewModel
import com.coral.gwigaon.report.TimelineItem
import com.coral.gwigaon.ui.components.DayNightPreview
import com.coral.gwigaon.ui.components.PrimaryButton
import com.coral.gwigaon.ui.components.SecondaryButton
import com.coral.gwigaon.ui.components.StatusBlock
import com.coral.gwigaon.ui.theme.Gwigaon
import com.coral.gwigaon.ui.theme.GwigaonRadius
import com.coral.gwigaon.ui.theme.GwigaonSize
import com.coral.gwigaon.ui.theme.GwigaonSpace
import com.coral.gwigaon.ui.theme.GwigaonText
import com.coral.gwigaon.ui.theme.GwigaonTheme
import com.coral.gwigaon.ui.theme.palette

@Composable
fun ReportRoute(viewModel: ReportViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    ReportScreen(state, onRetry = viewModel::retry, onClose = viewModel::close)
}

/**
 * Journey report, parked only (`design/d2/proto/report.html`, ADR 0017 + amendment): how the trip ended, the short
 * 운행 로그, then 귀가온 리포트 as plain text under its heading. The log never waits for the AI part. Half panel: one
 * scroll; full width: the log on the left, the report on the right.
 */
@Composable
fun ReportScreen(state: ReportUiState, onRetry: () -> Unit, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val p = Gwigaon.palette
    Column(
        modifier
            .fillMaxSize()
            .background(p.window)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(start = GwigaonSpace.panelSide, end = GwigaonSpace.panelSide, top = GwigaonSpace.panelTop, bottom = GwigaonSpace.panelSide)
            .testTag("report_screen"),
    ) {
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val full = maxWidth >= GwigaonSize.fullFrom
            if (full) {
                Column(Modifier.fillMaxSize()) {
                    Header(state)
                    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(GwigaonSpace.xl)) {
                        Column(Modifier.weight(1f).fillMaxHeight().scrolled(rememberScrollState())) {
                            LogSection(state)
                        }
                        Column(Modifier.weight(1f).fillMaxHeight().scrolled(rememberScrollState())) {
                            AiSection(state.ai, onRetry)
                        }
                    }
                }
            } else {
                Column(Modifier.fillMaxSize().scrolled(rememberScrollState())) {
                    Header(state)
                    LogSection(state)
                    Spacer(Modifier.height(GwigaonSpace.lg))
                    AiSection(state.ai, onRetry)
                }
            }
        }
        Footer(shared = state.ai != AiState.Off, onClose = onClose)
    }
}

private fun Modifier.scrolled(scroll: ScrollState) = verticalScroll(scroll).padding(bottom = GwigaonSpace.md)

@Composable
private fun Header(state: ReportUiState) {
    val p = Gwigaon.palette
    Text(state.kicker, style = GwigaonText.labelStrong, color = p.textTertiary, modifier = Modifier.testTag("report_kicker"))
    StatusBlock(
        state.status, state.period,
        Modifier.padding(top = GwigaonSpace.xs).semantics(mergeDescendants = true) { heading() }.testTag("report_status"),
        caution = state.caution,
    )
}

@Composable
private fun SectionLabel(title: String, hint: String? = null) {
    val p = Gwigaon.palette
    Row(
        Modifier.fillMaxWidth().padding(top = GwigaonSpace.lg, bottom = GwigaonSpace.sm),
        horizontalArrangement = Arrangement.spacedBy(GwigaonSpace.sm),
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(title, style = GwigaonText.title, color = p.textPrimary, modifier = Modifier.weight(1f).semantics { heading() })
        if (hint != null) Text(hint, style = GwigaonText.label, color = p.textTertiary)
    }
}

/** 귀가온 리포트: the heading, a quiet "AI가 쓴 요약" beside it, and the AI's summary as plain text. */
@Composable
private fun AiSection(ai: AiState, onRetry: () -> Unit) {
    val p = Gwigaon.palette
    Column(Modifier.fillMaxWidth().testTag("report_ai")) {
        SectionLabel(ReportTexts.REPORT_TITLE, ReportTexts.AI_HINT.takeIf { ai is AiState.Ready })
        when (ai) {
            AiState.Loading -> Loading()
            AiState.Off -> Text(ReportTexts.AI_OFF, style = GwigaonText.body, color = p.textSecondary, modifier = Modifier.testTag("ai_off"))
            AiState.Failed -> {
                Text(ReportTexts.AI_FAILED, style = GwigaonText.body, color = p.textPrimary, modifier = Modifier.testTag("ai_failed"))
                SecondaryButton(
                    ReportTexts.RETRY, onRetry, Modifier.padding(top = GwigaonSpace.md).testTag("ai_retry"), height = GwigaonSize.retry,
                )
            }
            is AiState.Ready -> Text(
                ai.summary, style = GwigaonText.paragraph, color = p.textPrimary,
                modifier = Modifier.padding(top = GwigaonSpace.xs).testTag("ai_summary"),
            )
        }
    }
}

/** Parked only: one calm spinner and static placeholder lines. */
@Composable
private fun Loading() {
    val p = Gwigaon.palette
    Column(Modifier.testTag("ai_loading")) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(GwigaonSpace.sm),
        ) {
            CircularProgressIndicator(
                Modifier.size(GwigaonSize.spinner), color = p.textPrimary, trackColor = p.surfaceHigh, strokeWidth = GwigaonSize.ringStroke / 2,
            )
            Text(ReportTexts.AI_LOADING, style = GwigaonText.body, color = p.textSecondary)
        }
        SKELETON.forEach { fraction ->
            Box(
                Modifier
                    .padding(top = GwigaonSpace.sm)
                    .fillMaxWidth(fraction)
                    .height(GwigaonSize.skeleton)
                    .background(p.surface, RoundedCornerShape(GwigaonRadius.progress)),
            )
        }
    }
}

private val SKELETON = listOf(0.92f, 0.84f, 0.6f)

/** 운행 로그: time | mark | what, with connectors between the marks. */
@Composable
private fun LogSection(state: ReportUiState) {
    val p = Gwigaon.palette
    Column(Modifier.fillMaxWidth().testTag("report_facts")) {
        SectionLabel(ReportTexts.LOG_TITLE, ReportTexts.LOG_HINT)
        if (state.timelineLoaded && state.timeline.isEmpty()) {
            Text(ReportTexts.NO_EVENTS, style = GwigaonText.body, color = p.textSecondary)
        }
        state.timeline.forEachIndexed { i, item ->
            LogRow(item, first = i == 0, last = i == state.timeline.lastIndex)
        }
    }
}

@Composable
private fun LogRow(item: TimelineItem, first: Boolean, last: Boolean) {
    val p = Gwigaon.palette
    val mark = when (item.tone) {
        ReportTone.NEUTRAL -> p.surfaceHigh
        ReportTone.OK -> p.safe
        ReportTone.WARN -> p.cautionPast
        ReportTone.DANGER -> p.sos
    }
    val connector = p.surface
    Row(
        Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .padding(horizontal = GwigaonSpace.sm)
            .semantics(mergeDescendants = true) {}
            .testTag("timeline_${item.seq}"),
        horizontalArrangement = Arrangement.spacedBy(GwigaonSpace.sm),
    ) {
        Text(
            item.time, style = GwigaonText.time, color = p.textTertiary,
            modifier = Modifier.width(GwigaonSize.logTime).padding(vertical = GwigaonSize.logRowPadding),
        )
        Box(
            Modifier
                .width(GwigaonSize.logMarkColumn)
                .fillMaxHeight()
                .drawBehind {
                    val x = size.width / 2
                    val top = (GwigaonSize.logRowPadding + GwigaonSize.logMarkTop).toPx()
                    val bottom = top + GwigaonSize.logMark.toPx()
                    val w = GwigaonSize.connector.toPx()
                    if (!first) drawLine(connector, Offset(x, 0f), Offset(x, top), w)
                    if (!last) drawLine(connector, Offset(x, bottom), Offset(x, size.height), w)
                },
            contentAlignment = Alignment.TopCenter,
        ) {
            Box(
                Modifier
                    .padding(top = GwigaonSize.logRowPadding + GwigaonSize.logMarkTop)
                    .size(GwigaonSize.logMark)
                    .background(mark, CircleShape),
            )
        }
        Column(Modifier.weight(1f).padding(vertical = GwigaonSize.logRowPadding)) {
            Text(item.label, style = GwigaonText.logTitle, color = p.textPrimary)
            if (item.reason != null) {
                Text(item.reason, style = GwigaonText.label, color = p.textTertiary, modifier = Modifier.padding(top = GwigaonSpace.xs / 2))
            }
        }
    }
}

@Composable
private fun Footer(shared: Boolean, onClose: () -> Unit) {
    val p = Gwigaon.palette
    HorizontalDivider(thickness = GwigaonSize.divider, color = p.line)
    if (shared) {
        Text(
            ReportTexts.SHARED, style = GwigaonText.label, color = p.textTertiary, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = GwigaonSpace.md),
        )
    }
    PrimaryButton(
        ReportTexts.CLOSE, onClose, Modifier.padding(top = GwigaonSpace.sm).testTag("report_close"), style = GwigaonText.action,
    )
}

private val SAMPLE = ReportUiState(
    kicker = "운행 기록 · 집",
    period = "00:58 → 01:38 · 40분",
    outcome = ReportOutcome.SAFE_ARRIVAL,
    timeline = listOf(
        TimelineItem(1, "00:58", "운행 시작", null, ReportTone.NEUTRAL),
        TimelineItem(2, "01:09", "경로 이탈", "안내 경로에서 320m 벗어났어요", ReportTone.WARN),
        TimelineItem(5, "01:31", "목적지 도착", null, ReportTone.OK),
        TimelineItem(8, "01:38", "안전 귀가", null, ReportTone.OK),
    ),
    timelineLoaded = true,
    ai = AiState.Ready("40분 동안 큰 문제 없이 집에 도착했어요. 01:09에 경로를 잠깐 벗어났다가 2분 만에 돌아왔어요."),
)

@DayNightPreview
@Composable
private fun ReportScreenPreview() = GwigaonTheme { ReportScreen(SAMPLE, {}, {}) }
