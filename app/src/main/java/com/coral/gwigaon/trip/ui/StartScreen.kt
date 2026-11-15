package com.coral.gwigaon.trip.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import com.coral.gwigaon.trip.BriefRow
import com.coral.gwigaon.trip.HomeTexts
import com.coral.gwigaon.trip.Settings
import com.coral.gwigaon.trip.StartBriefing
import com.coral.gwigaon.ui.components.DayNightPreview
import com.coral.gwigaon.ui.components.GwigaonIcons
import com.coral.gwigaon.ui.components.turnSignalHint
import com.coral.gwigaon.ui.theme.Gwigaon
import com.coral.gwigaon.ui.theme.GwigaonRadius
import com.coral.gwigaon.ui.theme.GwigaonSize
import com.coral.gwigaon.ui.theme.GwigaonSpace
import com.coral.gwigaon.ui.theme.GwigaonText
import com.coral.gwigaon.ui.theme.GwigaonTheme
import com.coral.gwigaon.ui.theme.palette
import com.coral.gwigaon.vehicle.Destination

/**
 * The driver's start screen (`design/d2/proto/start.html`), parked only with no trip. The destination is 설정's 집
 * and can't be changed here; the briefing follows 설정; 운행 시작 sits where the SOS bar will be once the trip runs.
 * Full width (귀가온 alone), a guide explains how to split the screen so the navigation opens beside it.
 */
@Composable
fun StartScreen(
    state: HomeUiState,
    onStart: () -> Unit,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = Gwigaon.palette
    BoxWithConstraints(modifier.fillMaxSize().background(p.window).testTag("start_screen")) {
        val full = maxWidth >= GwigaonSize.fullFrom
        val ready = state.settings.isComplete
        Row(
            Modifier.fillMaxSize().padding(start = GwigaonSpace.panelSide, end = if (full) GwigaonSpace.xl else GwigaonSpace.panelSide, top = GwigaonSpace.panelTop, bottom = GwigaonSpace.lg),
            horizontalArrangement = Arrangement.spacedBy(GwigaonSpace.xl),
        ) {
            Column(if (full) Modifier.width(GwigaonSize.sideColumn).fillMaxHeight() else Modifier.weight(1f).fillMaxHeight()) {
                BackButton("뒤로", onBack, Modifier.testTag("start_back"))
                if (ready) Ready(state, full, onStart) else NotSetUp(state.settings, onOpenSettings)
            }
            if (full && ready) SplitGuide(Modifier.weight(1f).fillMaxHeight())
        }
    }
}

@Composable
private fun ColumnScope.Ready(state: HomeUiState, full: Boolean, onStart: () -> Unit) {
    val p = Gwigaon.palette
    val scroll = rememberScrollState()
    Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll).padding(top = GwigaonSpace.md, bottom = GwigaonSpace.md)) {
        ScreenTitle("안녕하세요, 귀가온입니다", "오늘도 따듯한 귀가를 지켜 드릴게요")
        Route(state.settings.home?.name.orEmpty(), Modifier.padding(top = GwigaonSpace.lg))
        val line = p.line
        Column(
            Modifier
                .padding(top = GwigaonSpace.xl)
                .drawBehind { drawLine(line, Offset.Zero, Offset(size.width, 0f), GwigaonSize.divider.toPx()) }
                .padding(top = GwigaonSpace.sm),
        ) {
            StartBriefing.rows(state.settings).forEachIndexed { i, row -> Brief(row, divided = i > 0) }
        }
    }
    val line = p.line
    Column(
        Modifier.then(
            if (scroll.maxValue > 0) {
                Modifier.drawBehind { drawLine(line, Offset.Zero, Offset(size.width, 0f), GwigaonSize.divider.toPx()) }.padding(top = GwigaonSpace.md)
            } else {
                Modifier
            },
        ),
    ) {
        state.startError?.let {
            Text(it, style = GwigaonText.labelStrong, color = p.cautionText, modifier = Modifier.padding(bottom = GwigaonSpace.sm).testTag("start_error"))
        }
        val (title, sub) = when {
            state.starting -> "시작하는 중" to "내비게이션에 목적지를 보내고 있어요"
            full -> HomeTexts.GO_ALONE to null
            else -> "운행 시작" to HomeTexts.GEAR_HINT
        }
        GoButton(title, sub, quiet = full, busy = state.starting, onClick = onStart)
    }
}

/** 출발 → 목적지 on a dashed line; read-only, set in 설정. */
@Composable
private fun Route(home: String, modifier: Modifier = Modifier) {
    val p = Gwigaon.palette
    val dot = p.textTertiary
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(GwigaonRadius.card))
            .background(p.surface)
            .padding(horizontal = GwigaonSpace.md + GwigaonSpace.xs / 2, vertical = GwigaonSpace.md)
            .semantics(mergeDescendants = true) {} // read as one: 출발 지금 위치, 목적지 <home>
            .testTag("destination"),
    ) {
        RouteRow("출발", "지금 위치") {
            Box(Modifier.size(GwigaonSize.routeDot).border(GwigaonSize.outline, dot, CircleShape))
        }
        Row {
            Spacer(Modifier.width(ROUTE_LABEL))
            Canvas(Modifier.padding(horizontal = GwigaonSpace.md).width(GwigaonSize.routeDot).height(GwigaonSize.routeLine).padding(vertical = GwigaonSpace.xs)) {
                drawLine(
                    dot, Offset(size.width / 2, 0f), Offset(size.width / 2, size.height), GwigaonSize.ringLine.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)),
                )
            }
        }
        RouteRow("목적지", home) {
            Box(Modifier.size(GwigaonSize.routeDot).clip(CircleShape).background(dot))
        }
    }
}

private val ROUTE_LABEL = GwigaonSize.tile + GwigaonSpace.md

@Composable
private fun RouteRow(label: String, value: String, mark: @Composable () -> Unit) {
    val p = Gwigaon.palette
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = GwigaonText.labelStrong, color = p.textTertiary, modifier = Modifier.width(ROUTE_LABEL))
        Box(Modifier.padding(horizontal = GwigaonSpace.md)) { mark() }
        Text(value, style = GwigaonText.detail, color = p.textSecondary, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun Brief(row: BriefRow, divided: Boolean) {
    val p = Gwigaon.palette
    val (title, detail) = when (row) {
        BriefRow.RECORD -> "운행이 기록으로 남아요" to "차가 경로와 정차를 기록하고, 비상 연락처도 함께 봐요"
        BriefRow.CUES -> "대화 감지가 켜져 있어요" to "큰 소리나 폭언이 들리면 대처해요"
        BriefRow.END_AUTO -> "도착해서 승객이 내리면 끝나요" to "안 내리면 승객 상태를 확인해 달라고 알려 드려요"
        BriefRow.END_PIN -> "도착하면 승객에게 PIN을 물어보세요" to "승객이 내리거나 PIN을 입력하면 운행이 끝나요"
        BriefRow.SOS -> "" to ""
    }
    val icon = when (row) {
        BriefRow.RECORD -> GwigaonIcons.Shield
        BriefRow.CUES -> GwigaonIcons.Mic
        BriefRow.END_AUTO -> GwigaonIcons.Flag
        BriefRow.END_PIN -> GwigaonIcons.Key
        BriefRow.SOS -> GwigaonIcons.Alert
    }
    val tag = Modifier.testTag("brief_${row.name.lowercase()}")
    if (row == BriefRow.SOS) {
        InfoRow(
            icon,
            buildAnnotatedString {
                append("위험하면 ")
                withStyle(SpanStyle(color = p.sosText)) { append("SOS") }
                append("를 눌러 주세요")
            },
            buildAnnotatedString { withStyle(SpanStyle(color = p.textSecondary, fontWeight = FontWeight.Bold)) { append(turnSignalHint()) } },
            tag, divided, tile = p.sosGround, tint = p.sosText,
        )
    } else {
        InfoRow(icon, title, detail, tag, divided)
    }
}

/** 150 high, where the SOS bar will be; inverse when half, surface when 귀가온 is alone (the split guide leads). */
@Composable
private fun GoButton(title: String, sub: String?, quiet: Boolean, busy: Boolean, onClick: () -> Unit) {
    val p = Gwigaon.palette
    val content = if (quiet) p.textPrimary else p.onPrimary
    Surface(
        onClick = { if (!busy) onClick() },
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = GwigaonSize.sosBar)
            .testTag("start_trip")
            .semantics {
                role = Role.Button
                if (busy) stateDescription = "시작하는 중"
            },
        shape = RoundedCornerShape(GwigaonRadius.window),
        color = if (quiet) p.surfaceHigh else p.primary,
    ) {
        Column(
            Modifier.padding(GwigaonSpace.md),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(GwigaonSpace.sm)) {
                Text(title, style = GwigaonText.action, color = content)
                if (busy) CircularProgressIndicator(Modifier.size(GwigaonSize.spinner), color = content, strokeWidth = GwigaonSize.outline)
            }
            if (sub != null) {
                Text(sub, style = GwigaonText.label, color = content.copy(alpha = 0.72f), modifier = Modifier.padding(top = GwigaonSpace.xs / 2))
            }
        }
    }
}

/** 설정 missing: nothing for the driver to do but drive without 귀가온. */
@Composable
private fun ColumnScope.NotSetUp(settings: Settings, onOpenSettings: () -> Unit) {
    val p = Gwigaon.palette
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(top = GwigaonSpace.md)) {
        ScreenTitle(HomeTexts.NOT_SET_UP_TITLE, HomeTexts.NOT_SET_UP_LEDE, Modifier.testTag("not_set_up"))
        Column(Modifier.padding(top = GwigaonSpace.lg)) {
            InfoRow(GwigaonIcons.Home, "주소", settings.home?.name ?: "아직 없어요")
            InfoRow(GwigaonIcons.People, "모니터링 QR", "QR로 연결한 휴대폰에서 함께 봐요", divided = true)
        }
    }
    Text(
        buildAnnotatedString { withStyle(SpanStyle(textDecoration = TextDecoration.Underline)) { append(HomeTexts.OPEN_SETTINGS) } },
        style = GwigaonText.detail,
        color = p.textSecondary,
        modifier = Modifier
            .clip(RoundedCornerShape(GwigaonRadius.tile))
            .clickable(role = Role.Button, onClick = onOpenSettings)
            .heightIn(min = GwigaonSize.back)
            .padding(vertical = GwigaonSpace.sm)
            .testTag("open_settings"),
    )
}

/** Full width only: drag the window bar so the navigation opens beside 귀가온. */
@Composable
private fun SplitGuide(modifier: Modifier) {
    val p = Gwigaon.palette
    Column(
        modifier
            .clip(RoundedCornerShape(GwigaonRadius.card))
            .background(Brush.verticalGradient(listOf(p.litTop, p.litBottom)))
            .padding(GwigaonSpace.xl)
            .testTag("split_guide"),
    ) {
        Text("먼저 화면을 나눠 주세요", style = GwigaonText.status, color = p.textPrimary)
        Row(Modifier.padding(top = GwigaonSpace.md), horizontalArrangement = Arrangement.spacedBy(GwigaonSpace.sm)) {
            Icon(
                GwigaonIcons.Info, contentDescription = null, tint = p.textSecondary,
                modifier = Modifier.padding(top = GwigaonSpace.xs / 2).size(GwigaonSize.backIcon),
            )
            Text(HomeTexts.SPLIT_HINT, style = GwigaonText.body, color = p.textSecondary, modifier = Modifier.testTag("split_hint"))
        }
        SplitDiagram(Modifier.padding(vertical = GwigaonSpace.lg).fillMaxWidth().height(GwigaonSize.splitDiagram))
        Spacer(Modifier.weight(1f))
        val line = p.line
        Text(
            "이미 내비게이션이 덮었다면, 같은 막대를 끌면 귀가온이 옆으로 돌아와요.",
            style = GwigaonText.detail,
            color = p.textTertiary,
            modifier = Modifier
                .fillMaxWidth()
                .drawBehind { drawLine(line, Offset.Zero, Offset(size.width, 0f), GwigaonSize.divider.toPx()) }
                .padding(top = GwigaonSpace.md),
        )
    }
}

/**
 * What the split looks like (owner, D3f H6): the navigation opens on the left, 귀가온 moves to the right; the bar is
 * dragged from the right edge to the middle.
 */
@Composable
private fun SplitDiagram(modifier: Modifier) {
    val p = Gwigaon.palette
    Row(
        modifier
            .clip(RoundedCornerShape(GwigaonRadius.key))
            .background(p.window)
            .border(GwigaonSize.outline, p.surfaceHigh, RoundedCornerShape(GwigaonRadius.key))
            .padding(GwigaonSpace.md)
            .testTag("split_diagram"),
        horizontalArrangement = Arrangement.spacedBy(GwigaonSpace.md),
    ) {
        val outline = p.progress
        Box(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .drawBehind {
                    drawRoundRect(
                        outline,
                        cornerRadius = CornerRadius(GwigaonRadius.tile.toPx()),
                        style = Stroke(GwigaonSize.outline.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(28f, 20f))),
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            Text("내비게이션", style = GwigaonText.labelStrong, color = p.textSecondary)
        }
        val handle = p.textPrimary
        Box(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .clip(RoundedCornerShape(GwigaonRadius.tile))
                .background(p.surface)
                .drawBehind {
                    val y = size.height / 2
                    val bar = GwigaonSize.splitHandle.toPx()
                    drawLine(handle, Offset(bar / 2, y - 50f), Offset(bar / 2, y + 50f), bar, StrokeCap.Round)
                    drawLine(handle, Offset(size.width - GwigaonSpace.md.toPx(), y), Offset(GwigaonSpace.lg.toPx(), y), GwigaonSize.outline.toPx() * 1.5f, StrokeCap.Round)
                },
            contentAlignment = Alignment.TopCenter,
        ) {
            Text("귀가온", style = GwigaonText.rowTitle, color = p.textPrimary, modifier = Modifier.padding(top = GwigaonSpace.lg))
            Icon(
                GwigaonIcons.Back,
                contentDescription = null,
                tint = handle,
                modifier = Modifier.align(Alignment.CenterStart).padding(start = GwigaonSpace.md).size(GwigaonSize.backIcon),
            )
        }
    }
}

private val PREVIEW = HomeUiState(
    settings = Settings(home = Destination("양재역", 37.4846, 127.0342), contacts = listOf("엄마"), cues = true),
)

@DayNightPreview
@Composable
internal fun StartPreview() = GwigaonTheme { StartScreen(PREVIEW, {}, {}, {}) }

@Preview(widthDp = 1682, heightDp = 1210)
@Composable
internal fun StartFullPreview() = GwigaonTheme { StartScreen(PREVIEW, {}, {}, {}) }

@Preview(widthDp = 842, heightDp = 1210)
@Composable
internal fun StartNotSetUpPreview() = GwigaonTheme { StartScreen(HomeUiState(), {}, {}, {}) }
