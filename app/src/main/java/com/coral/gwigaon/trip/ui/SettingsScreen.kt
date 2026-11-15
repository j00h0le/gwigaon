package com.coral.gwigaon.trip.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.coral.gwigaon.sync.LinkConnection
import com.coral.gwigaon.trip.ArrivalInstructions
import com.coral.gwigaon.trip.Settings
import com.coral.gwigaon.trip.SettingsAccess
import com.coral.gwigaon.trip.SettingsDraft
import com.coral.gwigaon.trip.SettingsStep
import com.coral.gwigaon.trip.SettingsTexts
import com.coral.gwigaon.ui.components.GwigaonIcons
import com.coral.gwigaon.ui.components.IconTile
import com.coral.gwigaon.ui.components.Keypad
import com.coral.gwigaon.ui.components.PrimaryButton
import com.coral.gwigaon.ui.theme.Gwigaon
import com.coral.gwigaon.ui.theme.GwigaonRadius
import com.coral.gwigaon.ui.theme.GwigaonSize
import com.coral.gwigaon.ui.theme.GwigaonSpace
import com.coral.gwigaon.ui.theme.GwigaonText
import com.coral.gwigaon.ui.theme.GwigaonTheme
import com.coral.gwigaon.ui.theme.palette
import com.coral.gwigaon.vehicle.Destination

/** What the 설정 screens can ask for; [SettingsRoute] binds it to [SettingsViewModel]. */
data class SettingsActions(
    val next: () -> Unit = {},
    val back: () -> Unit = {},
    val edit: (SettingsStep) -> Unit = {},
    val setHome: (Destination) -> Unit = {},
    val askReplaceLink: () -> Unit = {},
    val cancelReplaceLink: () -> Unit = {},
    val replaceLink: () -> Unit = {},
    val setArrival: ((ArrivalInstructions) -> ArrivalInstructions) -> Unit = {},
    val setCues: (Boolean) -> Unit = {},
    val setPinOn: (Boolean) -> Unit = {},
    val pinDigit: (Char) -> Unit = {},
    val pinDelete: () -> Unit = {},
    val gateDigit: (Char) -> Unit = {},
    val gateDelete: () -> Unit = {},
)

@Composable
fun SettingsRoute(vm: SettingsViewModel, link: LinkViewModel?, onClose: () -> Unit) {
    val settings by vm.state.collectAsStateWithLifecycle()
    val linkState = link?.state?.collectAsStateWithLifecycle()?.value ?: LinkUiState(connection = LinkConnection.NoCloud)
    val state = settings.copy(link = linkState)
    // The QR shows on the 비상 연락처 step, the summary and 완료: keep it made, uploaded and its 연결됨 fresh there.
    val showsQr = state.access == SettingsAccess.OPEN && (state.step == null || state.step == SettingsStep.CONTACTS || state.step == SettingsStep.DONE)
    DisposableEffect(link, showsQr) {
        if (showsQr) link?.watch()
        onDispose { link?.stopWatching() }
    }
    SettingsScreen(
        state,
        SettingsActions(
            next = { vm.next(onDone = onClose) },
            back = { if (vm.back()) onClose() },
            edit = vm::edit,
            setHome = vm::setHome,
            askReplaceLink = { link?.askReplace() },
            cancelReplaceLink = { link?.cancelReplace() },
            replaceLink = { link?.replace() },
            setArrival = vm::setArrival,
            setCues = vm::setCues,
            setPinOn = vm::setPinOn,
            pinDigit = vm::pinDigit,
            pinDelete = vm::pinDelete,
            gateDigit = vm::gateDigit,
            gateDelete = vm::gateDelete,
        ),
        onClose = onClose,
    )
}

/**
 * 설정 (`design/d2/proto/settings.html`), parked only with no trip (SPEC trip-core, [com.coral.gwigaon.trip.SettingsGate]).
 * Full width it keeps the prototype's two halves: the step on the left, what the driver or 보호자 will see on the right.
 * Narrower, the right half drops except the PIN keypad, which moves under the step.
 */
@Composable
fun SettingsScreen(state: SettingsUiState, actions: SettingsActions, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val p = Gwigaon.palette
    BoxWithConstraints(modifier.fillMaxSize().background(p.window).imePadding().testTag("settings_screen")) {
        val full = maxWidth >= GwigaonSize.fullFrom
        val pad = Modifier.fillMaxSize().padding(start = GwigaonSpace.xl, end = GwigaonSpace.xl, top = GwigaonSpace.panelTop, bottom = GwigaonSpace.lg)
        when (state.access) {
            SettingsAccess.BLOCKED_TRIP, SettingsAccess.BLOCKED_DRIVING -> Blocked(state.access, pad)
            SettingsAccess.NEEDS_PIN -> TwoHalves(full, pad, side = { GateSide(state, actions, it) }) {
                Header(close = true, onBack = onClose)
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    ScreenTitle("설정을 열려면\nPIN을 입력해 주세요", "PIN으로 운행 종료가 켜져 있어서 설정도 잠겨 있어요.", Modifier.padding(top = GwigaonSpace.lg))
                    if (!full) {
                        GateDots(state, Modifier.padding(top = GwigaonSpace.lg))
                        Gate(state, actions, Modifier.padding(top = GwigaonSpace.lg))
                    }
                }
            }
            SettingsAccess.OPEN -> Open(state, actions, full, pad)
        }
    }
}

@Composable
private fun Gate(state: SettingsUiState, actions: SettingsActions, modifier: Modifier, keyColor: Color = Gwigaon.palette.surface) =
    Keypad(actions.gateDigit, actions.gateDelete, modifier.testTag("gate_keypad"), enabled = !state.gateLocked, keyColor = keyColor)

@Composable
private fun GateDots(state: SettingsUiState, modifier: Modifier) =
    PinDotsWithMessage(state.gateDigits.length, SettingsTexts.gate(state.gateResult, state.gateSecondsLeft), state.gateResult != null, "gate_message", modifier)

/** Full width: the dots, centred, above the keypad on the right panel (D3f H14). */
@Composable
private fun GateSide(state: SettingsUiState, actions: SettingsActions, modifier: Modifier) {
    Column(modifier.testTag("settings_side").clip(RoundedCornerShape(GwigaonRadius.card)).background(Gwigaon.palette.surface).padding(GwigaonSpace.lg)) {
        Spacer(Modifier.weight(1f))
        GateDots(state, Modifier)
        Spacer(Modifier.weight(1f))
        Gate(state, actions, Modifier, keyColor = Gwigaon.palette.window) // on the panel's surface
    }
}

/** Left: the step; right (full width only): [side]. */
@Composable
private fun TwoHalves(
    full: Boolean,
    modifier: Modifier,
    side: (@Composable (Modifier) -> Unit)?,
    left: @Composable ColumnScope.() -> Unit,
) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(GwigaonSpace.xl)) {
        Column(if (full) Modifier.width(GwigaonSize.sideColumn).fillMaxHeight() else Modifier.weight(1f).fillMaxHeight(), content = left)
        if (full && side != null) side(Modifier.weight(1f).fillMaxHeight())
    }
}

@Composable
private fun Open(state: SettingsUiState, actions: SettingsActions, full: Boolean, modifier: Modifier) {
    val step = state.step
    TwoHalves(full, modifier, side = { m -> Side(state, actions, m) }) {
        when {
            step == null || step == SettingsStep.INTRO -> Header(close = true, onBack = actions.back)
            step.number != null -> Header(close = state.direct, step = step, editing = state.mode == SettingsMode.EDIT, onBack = actions.back)
        }
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = GwigaonSpace.md)) {
            if (step == null) Summary(state, actions, edit = true) else StepBody(step, state, actions, full)
        }
        if (step != null) Foot(state, actions)
    }
}

/** Where am I, how much is left (numbered steps), or just 닫기 (summary, PIN gate). */
@Composable
private fun Header(onBack: () -> Unit, close: Boolean = false, step: SettingsStep? = null, editing: Boolean = false) {
    val p = Gwigaon.palette
    Row(
        Modifier.fillMaxWidth().height(GwigaonSize.back),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(GwigaonSpace.md),
    ) {
        BackButton(if (close) "닫기" else "뒤로", onBack, Modifier.testTag("settings_back"))
        val n = step?.number
        when {
            close || n == null -> Text("닫기", style = GwigaonText.detail, color = p.textTertiary)
            editing -> Text("${stepLabel(step)} 변경", style = GwigaonText.detail, color = p.textTertiary)
            else -> {
                Text("$n / ${SettingsStep.NUMBERED} · ${stepLabel(step)}", style = GwigaonText.detail, color = p.textTertiary, modifier = Modifier.testTag("step_count"))
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(GwigaonSpace.xs)) {
                    repeat(SettingsStep.NUMBERED) { i ->
                        Box(
                            Modifier.weight(1f).height(GwigaonSize.stepBar).clip(RoundedCornerShape(GwigaonSize.stepBar / 2))
                                .background(if (i < n) p.textSecondary else p.surface),
                        )
                    }
                }
            }
        }
    }
}

internal fun stepLabel(step: SettingsStep): String = when (step) {
    SettingsStep.HOME -> "주소"
    SettingsStep.CONTACTS -> SettingsTexts.CONTACTS_STEP
    SettingsStep.ARRIVAL -> "도착 안내"
    SettingsStep.CUES -> "대화 감지"
    SettingsStep.PIN -> "PIN"
    SettingsStep.INTRO, SettingsStep.DONE -> "설정"
}

/** The primary action, always in the same place. */
@Composable
private fun Foot(state: SettingsUiState, actions: SettingsActions) {
    val p = Gwigaon.palette
    val step = state.step ?: return
    val editing = state.mode == SettingsMode.EDIT
    val (label, sub) = when (step) {
        SettingsStep.INTRO -> "시작하기" to null
        SettingsStep.DONE -> "기사님께 넘기기" to null
        SettingsStep.CONTACTS -> (if (state.direct) "확인" else if (editing) "저장" else "다음") to null
        SettingsStep.ARRIVAL -> (if (editing) "저장" else "다음") to "건너뛰어도 돼요"
        else -> (if (editing) "저장" else "다음") to null
    }
    val line = p.line
    Column(
        Modifier
            .fillMaxWidth()
            .drawBehind { drawLine(line, Offset.Zero, Offset(size.width, 0f), GwigaonSize.divider.toPx()) }
            .padding(top = GwigaonSpace.md),
    ) {
        state.saveError?.let { Text(it, style = GwigaonText.labelStrong, color = p.sosText, modifier = Modifier.padding(bottom = GwigaonSpace.sm)) }
        PrimaryButton(label, actions.next, Modifier.testTag("settings_next"), enabled = state.canAdvance && !state.saving, sub = sub)
    }
}

/** 완료 and 편집: one row per part with 변경 (비상 연락처: QR 보기, or 확인 while its step was never shown). */
@Composable
internal fun Summary(state: SettingsUiState, actions: SettingsActions, edit: Boolean) {
    val s = state.saved
    val p = Gwigaon.palette
    if (edit) {
        ScreenTitle("설정", null, Modifier.padding(top = GwigaonSpace.lg))
    } else {
        ScreenTitle("설정을 마쳤어요", "다음 운행부터 그대로 써요. 바꾸고 싶으면 언제든 고칠 수 있어요.", Modifier.padding(top = GwigaonSpace.md))
    }
    Column(Modifier.padding(top = GwigaonSpace.lg)) {
        val change: (SettingsStep) -> @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = { step ->
            { RowAction("변경", { actions.edit(step) }, Modifier.testTag("change_${step.name.lowercase()}")) }
        }
        InfoRow(GwigaonIcons.Home, "주소", SettingsTexts.home(s) ?: "아직 없어요", end = change(SettingsStep.HOME))
        if (!s.contactsDone) {
            InfoRow(
                GwigaonIcons.People, SettingsTexts.CONTACTS, "QR을 확인해야 운행이 시작돼요", divided = true, detailColor = p.cautionText,
                end = { RowAction("확인", { actions.edit(SettingsStep.CONTACTS) }, Modifier.testTag("change_contacts")) },
            )
        } else {
            InfoRow(
                GwigaonIcons.People, SettingsTexts.CONTACTS, LinkTexts.connection(state.link.connection), divided = true,
                end = { RowAction("QR 보기", { actions.edit(SettingsStep.CONTACTS) }, Modifier.testTag("change_contacts")) },
            )
        }
        InfoRow(GwigaonIcons.Pin, "도착 안내", SettingsTexts.arrival(s.arrival), divided = true, end = change(SettingsStep.ARRIVAL))
        InfoRow(GwigaonIcons.Mic, "대화 감지", SettingsTexts.cues(s.cues), divided = true, end = change(SettingsStep.CUES))
        InfoRow(GwigaonIcons.Lock, "PIN으로 운행 종료", SettingsTexts.pin(s.pinOn), divided = true, end = change(SettingsStep.PIN))
    }
}

/** A trip is running (or the car moves): 설정 stays shut, and says the current one applies. */
@Composable
private fun Blocked(access: SettingsAccess, modifier: Modifier) {
    val p = Gwigaon.palette
    Column(modifier.widthIn(max = BLOCK_WIDTH).testTag("settings_blocked"), verticalArrangement = Arrangement.Center) {
        IconTile(GwigaonIcons.Lock, p.surfaceHigh, p.textPrimary)
        val (title, lede) = if (access == SettingsAccess.BLOCKED_TRIP) {
            "운행 중에는 설정을\n열 수 없어요" to "운행이 끝나면 다시 열 수 있어요."
        } else {
            "주차(P) 후에\n열 수 있어요" to "차가 움직이는 동안에는 설정을 바꿀 수 없어요."
        }
        ScreenTitle(title, lede, Modifier.padding(top = GwigaonSpace.lg))
    }
}

private val BLOCK_WIDTH = GwigaonSize.sideColumn + GwigaonSpace.xl * 4

internal val PREVIEW_SAVED = Settings(
    home = Destination("양재역", 37.4846, 127.0342),
    linkShown = true,
    arrival = ArrivalInstructions("지하 2층", "101동 출입구", "승객에게 직접"),
    cues = true,
)

@Preview(widthDp = 1682, heightDp = 1210)
@Composable
internal fun SettingsIntroPreview() = GwigaonTheme { SettingsScreen(SettingsUiState(), SettingsActions(), {}) }

@Preview(widthDp = 1682, heightDp = 1210)
@Composable
internal fun SettingsEditPreview() = GwigaonTheme {
    SettingsScreen(SettingsUiState(mode = SettingsMode.EDIT, step = null, saved = PREVIEW_SAVED, draft = SettingsDraft.from(PREVIEW_SAVED)), SettingsActions(), {})
}

@Preview(widthDp = 842, heightDp = 1210)
@Composable
internal fun SettingsHalfPreview() = GwigaonTheme {
    SettingsScreen(SettingsUiState(step = SettingsStep.CONTACTS, saved = PREVIEW_SAVED, draft = SettingsDraft.from(PREVIEW_SAVED)), SettingsActions(), {})
}
