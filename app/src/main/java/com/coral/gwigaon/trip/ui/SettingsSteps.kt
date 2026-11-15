package com.coral.gwigaon.trip.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import com.coral.gwigaon.trip.ArrivalPresets
import com.coral.gwigaon.trip.CheckInView
import com.coral.gwigaon.trip.CueKind
import com.coral.gwigaon.trip.DrivingPanelRule
import com.coral.gwigaon.trip.PanelNoteKind
import com.coral.gwigaon.trip.SavedPlaces
import com.coral.gwigaon.trip.SettingsDraft
import com.coral.gwigaon.trip.SettingsField
import com.coral.gwigaon.trip.SettingsInput
import com.coral.gwigaon.trip.SettingsStep
import com.coral.gwigaon.trip.SettingsTexts
import com.coral.gwigaon.cues.ui.CheckInCard
import com.coral.gwigaon.cues.ui.MicIndicator
import com.coral.gwigaon.ui.components.ArrivalNote
import com.coral.gwigaon.ui.components.ArrivalNotesCard
import com.coral.gwigaon.ui.components.Field
import com.coral.gwigaon.ui.components.GwigaonChip
import com.coral.gwigaon.ui.components.GwigaonIcons
import com.coral.gwigaon.ui.components.Keypad
import com.coral.gwigaon.ui.components.PinDots
import com.coral.gwigaon.ui.components.SettingSwitch
import com.coral.gwigaon.ui.theme.Gwigaon
import com.coral.gwigaon.ui.theme.GwigaonRadius
import com.coral.gwigaon.ui.theme.GwigaonSize
import com.coral.gwigaon.ui.theme.GwigaonSpace
import com.coral.gwigaon.ui.theme.GwigaonText
import com.coral.gwigaon.ui.theme.palette
import com.coral.gwigaon.vehicle.Destination

/** The left half of one 설정 step. */
@Composable
internal fun ColumnScope.StepBody(step: SettingsStep, state: SettingsUiState, actions: SettingsActions, full: Boolean) {
    val top = Modifier.padding(top = if (step.number == null) GwigaonSpace.md else GwigaonSpace.lg)
    when (step) {
        SettingsStep.INTRO -> Intro(top)
        SettingsStep.HOME -> HomeStep(state, actions, top)
        SettingsStep.CONTACTS -> ContactsStep(state, actions, full, top)
        SettingsStep.ARRIVAL -> ArrivalStep(state, actions, top)
        SettingsStep.CUES -> CuesStep(state.draft, actions, top)
        SettingsStep.PIN -> PinStep(state, actions, full, top)
        SettingsStep.DONE -> Summary(state, actions, edit = false)
    }
}

@Composable
private fun Intro(modifier: Modifier) {
    ScreenTitle("귀가온 설정을 시작합니다", "대리기사님을 부를 때마다 이 설정 그대로 집까지 지켜 드려요. 2분이면 끝나요.", modifier)
    Column(Modifier.padding(top = GwigaonSpace.lg)) {
        IntroRow(GwigaonIcons.Home, "주소", "자동으로 목적지로 안내가 시작됩니다", true, false)
        IntroRow(GwigaonIcons.People, SettingsTexts.CONTACTS_STEP, "운행을 실시간으로 모니터링해요", true, true)
        IntroRow(GwigaonIcons.Pin, "도착 안내", "주차 위치, 출입구, 차 키", false, true)
        IntroRow(GwigaonIcons.Mic, "대화 감지", "큰 소리나 폭언이 들리면 확인해요", false, true)
        IntroRow(GwigaonIcons.Lock, "PIN으로 운행 종료", "PIN을 입력해야 운행이 끝나요", false, true)
    }
}

@Composable
private fun IntroRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, detail: String, required: Boolean, divided: Boolean) =
    InfoRow(icon, title, detail, divided = divided, end = { Tag(if (required) "필수" else "선택", strong = required) })

@Composable
private fun HomeStep(state: SettingsUiState, actions: SettingsActions, modifier: Modifier) {
    val p = Gwigaon.palette
    ScreenTitle("집이 어디인가요?", "저장된 장소와 최근 목적지에서 골라 주세요.", modifier)
    Column(Modifier.padding(top = GwigaonSpace.lg), verticalArrangement = Arrangement.spacedBy(GwigaonSpace.xs)) {
        state.choices.forEach { d -> Place(d, selected = state.draft.home == d, onClick = { actions.setHome(d) }) }
    }
    Text(
        "목록에 없는 곳은 아직 고를 수 없어요",
        style = GwigaonText.labelStrong,
        color = p.textTertiary,
        modifier = Modifier.padding(top = GwigaonSpace.lg),
    )
}

@Composable
private fun Place(d: Destination, selected: Boolean, onClick: () -> Unit) {
    val p = Gwigaon.palette
    val shape = RoundedCornerShape(GwigaonRadius.card)
    val saved = SavedPlaces.ALL.contains(d)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .then(if (selected) Modifier.background(p.surface).border(GwigaonSize.outline, p.primary, shape) else Modifier)
            .clickable(role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = GwigaonSpace.md)
            .testTag("place_${d.name}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            InfoRow(if (saved) GwigaonIcons.Pin else GwigaonIcons.Clock, d.name, if (saved) "저장된 장소" else "최근 목적지")
        }
        Radio(selected)
    }
}

/** 비상 연락처 (ADR 0019): the account's one QR. Nothing to fill in; passing the step completes it. */
@Composable
private fun ContactsStep(state: SettingsUiState, actions: SettingsActions, full: Boolean, modifier: Modifier) {
    ScreenTitle(LinkTexts.TITLE, LinkTexts.LEDE, modifier)
    Column(Modifier.padding(top = GwigaonSpace.lg)) {
        InfoRow(GwigaonIcons.Mobile, LinkTexts.HOW, LinkTexts.HOW_DETAIL)
        if (!full) {
            LinkQr(state.link.url, GwigaonSize.qrCompact, Modifier.padding(top = GwigaonSpace.md))
            LinkStatus(state.link, actions, Modifier.padding(vertical = GwigaonSpace.md))
        }
        InfoRow(GwigaonIcons.Car, "운행이 시작되면 바로 보여요", "경로, 정차, SOS를 실시간으로", divided = true)
    }
}

/** The right half on the 비상 연락처 step and the 설정 summary: the QR, big, with its state. */
@Composable
private fun LinkSide(state: SettingsUiState, actions: SettingsActions, modifier: Modifier) =
    SidePanel("비상 연락처의 휴대폰", GwigaonIcons.Mobile, null, modifier, foot = "QR을 다시 만들면 이전 QR로는 바로 볼 수 없어요.") {
        LinkQr(state.link.url, GwigaonSize.qr, Modifier.padding(top = GwigaonSpace.lg))
        LinkStatus(state.link, actions, Modifier.padding(top = GwigaonSpace.lg))
    }

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ArrivalStep(state: SettingsUiState, actions: SettingsActions, modifier: Modifier) {
    val a = state.draft.arrival
    val errors = state.draft.errors(state.saved)
    ScreenTitle("기사님께 남길 도착 안내", "골라도 되고, 직접 써도 돼요. 모두 건너뛰어도 괜찮아요.", modifier)
    NoteGroup("주차 위치", ArrivalPresets.PARKING, a.parking, "예: 지하 2층 B구역", errors[SettingsInput(SettingsField.ARRIVAL, 0)], "parking") { v ->
        actions.setArrival { it.copy(parking = v) }
    }
    NoteGroup("출입구", ArrivalPresets.ENTRANCE, a.entrance, "예: 101동 출입구", errors[SettingsInput(SettingsField.ARRIVAL, 1)], "entrance") { v ->
        actions.setArrival { it.copy(entrance = v) }
    }
    NoteGroup("차 키", ArrivalPresets.KEY, a.keyNote, "직접 입력", errors[SettingsInput(SettingsField.ARRIVAL, 2)], "key") { v ->
        actions.setArrival { it.copy(keyNote = v) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NoteGroup(
    title: String,
    presets: List<String>,
    value: String,
    placeholder: String,
    error: String?,
    tag: String,
    onChange: (String) -> Unit,
) {
    Text(title, style = GwigaonText.rowTitle, color = Gwigaon.palette.textPrimary, modifier = Modifier.padding(top = GwigaonSpace.lg))
    FlowRow(
        Modifier.padding(top = GwigaonSpace.sm),
        horizontalArrangement = Arrangement.spacedBy(GwigaonSpace.sm),
        verticalArrangement = Arrangement.spacedBy(GwigaonSpace.sm),
    ) {
        presets.forEach { preset ->
            GwigaonChip(preset, selected = value == preset, onClick = { onChange(if (value == preset) "" else preset) }, modifier = Modifier.testTag("chip_$preset"))
        }
    }
    Field(value, onChange, Modifier.padding(top = GwigaonSpace.sm).fillMaxWidth().testTag("field_$tag"), placeholder = placeholder, error = error)
}

@Composable
private fun CuesStep(draft: SettingsDraft, actions: SettingsActions, modifier: Modifier) {
    ScreenTitle("차 안 대화를 감지할까요?", "AI 음성 인식을 통해 위험한 상황을 감지해요.", modifier)
    SettingSwitch("대화 감지", "모든 운행에 적용돼요", draft.cues, actions.setCues, Modifier.padding(top = GwigaonSpace.lg).testTag("listen_switch"))
    Column(Modifier.padding(top = GwigaonSpace.md)) {
        InfoRow(GwigaonIcons.Mic, "음성은 저장되지 않아요", "AI가 듣고, 위급 상황만 감지해요")
        InfoRow(GwigaonIcons.Car, "기사님도 안내되어요", "운행 중 화면에 \"AI 모니터링 중\"이 늘 보여요", divided = true)
    }
}

@Composable
private fun PinStep(state: SettingsUiState, actions: SettingsActions, full: Boolean, modifier: Modifier) {
    val on = state.draft.pinOn
    val setup = state.pinSetup
    val title = when {
        !on -> "운행을 PIN으로\n종료할까요?"
        setup.mismatch || setup.confirming -> "한 번 더 입력해 주세요"
        else -> "PIN 4자리를\n정해 주세요"
    }
    ScreenTitle(
        title,
        if (on) null else "켜면 내가 기사님께 PIN을 알려줘야만 운행 기록이 끝나요. 잠들어도 비상 연락처가 끝까지 지켜볼 수 있어요.",
        modifier,
    )
    SettingSwitch(
        "PIN으로 운행 종료", if (on) "켜짐" else "꺼짐 · 내가 내리면 운행이 끝나요", on, actions.setPinOn,
        Modifier.padding(top = GwigaonSpace.lg).testTag("pin_switch"),
    )
    if (!on) {
        Column(Modifier.padding(top = GwigaonSpace.md)) {
            InfoRow(GwigaonIcons.Flag, "꺼 두면", "집 앞에서 내가 내리면 자동으로 끝나요")
            InfoRow(GwigaonIcons.Lock, "켜 두면", "설정을 열 때도 PIN이 필요해요", divided = true)
        }
        return
    }
    if (!full) {
        SetupDots(state, Modifier.padding(top = GwigaonSpace.lg))
        PinKeypad(actions, Modifier.padding(top = GwigaonSpace.md), GwigaonSize.keyCompact, Gwigaon.palette.surface)
    }
}

/** The new PIN's dots and what to do next, centred (left half when narrow, above the keypad at full width). */
@Composable
private fun SetupDots(state: SettingsUiState, modifier: Modifier) {
    val setup = state.pinSetup
    val keepsOld = state.draft.pinOn && state.saved.pinOn && setup.first.isEmpty()
    val (message, error) = when {
        setup.mismatch -> "처음 입력한 PIN과 달라요. 처음부터 다시 정해 주세요." to true
        setup.complete -> "PIN을 정했어요" to false
        setup.confirming -> "한 번 더 입력해서 확인해요" to false
        keepsOld -> "지금 PIN을 그대로 써요 · 바꾸려면 새 PIN을 눌러 주세요" to false
        else -> "숫자 4자리를 눌러 주세요" to false
    }
    PinDotsWithMessage(if (setup.complete) 4 else setup.filled, message, error, "pin_message", modifier)
}

/** Dots and one line under them, both centred (D3f H14). */
@Composable
internal fun PinDotsWithMessage(filled: Int, message: String, error: Boolean, tag: String, modifier: Modifier = Modifier) {
    val p = Gwigaon.palette
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        PinDots(filled)
        Text(
            message,
            style = GwigaonText.label,
            color = if (error) p.sosText else p.textTertiary,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = GwigaonSpace.md).testTag(tag),
        )
    }
}

@Composable
private fun PinKeypad(
    actions: SettingsActions,
    modifier: Modifier,
    keyHeight: Dp = GwigaonSize.key,
    keyColor: Color = Gwigaon.palette.window, // on the side panel's lit surface
) = Keypad(actions.pinDigit, actions.pinDelete, modifier.testTag("pin_keypad"), keyColor = keyColor, keyHeight = keyHeight)

/** The right half: what the driver or 보호자 will see, or the keypad. */
@Composable
internal fun Side(state: SettingsUiState, actions: SettingsActions, modifier: Modifier) {
    val p = Gwigaon.palette
    val step = state.step
    val m = modifier.testTag("settings_side")
    when (step) {
        SettingsStep.INTRO -> SidePanel("누가 무엇을 보나요", GwigaonIcons.People, "세 사람이 같은 운행을 봐요", m, foot = "가입은 없어요. 비상 연락처는 QR 링크로만 봐요.") {
            Column(Modifier.padding(top = GwigaonSpace.sm)) {
                InfoRow(GwigaonIcons.Car, "기사님", "목적지, 도착 안내")
                InfoRow(GwigaonIcons.Mobile, "비상 연락처", "경로, 정차, SOS를 실시간으로", divided = true)
                InfoRow(GwigaonIcons.Person, "나", "운행 기록과 AI 요약", divided = true)
            }
        }
        SettingsStep.HOME -> SidePanel(
            "기사님 화면", GwigaonIcons.Car, "운행 시작 전에 이렇게 보여요", m,
            text = "기사님은 목적지를 바꿀 수 없어요.",
            foot = "운행 시작 시 설정된 주소가 자동으로 내비게이션에 떠요.",
        ) {
            MiniPanel {
                Text("목적지", style = GwigaonText.labelStrong, color = p.textTertiary)
                Text(state.draft.home?.name ?: "아직 고르지 않았어요", style = GwigaonText.rowTitle, color = p.textPrimary, modifier = Modifier.padding(top = GwigaonSpace.xs))
            }
        }
        SettingsStep.CONTACTS -> LinkSide(state, actions, m)
        SettingsStep.ARRIVAL -> SidePanel(
            "기사님 화면", GwigaonIcons.Car, "목적지 500 m 전에 보여 주고 읽어 드려요", m,
            foot = "공동현관 비밀번호는 넣지 마세요. 기사님 화면에 그대로 보여요. 읽어 드릴 문장은 AI(Claude)가 안내 내용만으로 다듬어요.",
        ) {
            val notes = DrivingPanelRule.notes(state.draft.arrival)
            if (notes.isEmpty()) {
                MiniPanel { Text("고른 안내가 여기에 보여요", style = GwigaonText.detail, color = p.textTertiary) }
            } else {
                MiniPanel {
                    ArrivalNotesCard(
                        notes.map { n ->
                            val icon = when (n.kind) {
                                PanelNoteKind.PARKING -> GwigaonIcons.Pin
                                PanelNoteKind.ENTRANCE -> GwigaonIcons.Door
                                PanelNoteKind.KEY -> GwigaonIcons.Key
                            }
                            ArrivalNote(icon, n.fact, n.label)
                        },
                    )
                }
            }
        }
        SettingsStep.CUES -> SidePanel(
            "기사님 화면", GwigaonIcons.Car, "감지되면 이렇게 물어봐요", m,
            text = "소리 내지 않고 화면으로만 물어요.\n비상 연락처에게는 기록으로만 남아요.",
            foot = "시연용 기능이에요. 실제 서비스에서는 승객과 기사님 모두의 사전 동의를 받아요.",
        ) {
            MiniPanel {
                MicIndicator(onTurnOff = {})
                Spacer(Modifier.padding(top = GwigaonSpace.md))
                CheckInCard(CheckInView(CueKind.PROFANITY, level = 1, secondsLeft = 30), onOk = {}, sosBelow = false)
            }
        }
        SettingsStep.PIN -> if (state.draft.pinOn) {
            Column(m.clip(RoundedCornerShape(GwigaonRadius.card)).background(p.surface).padding(GwigaonSpace.lg), verticalArrangement = Arrangement.Bottom) {
                Spacer(Modifier.weight(1f))
                SetupDots(state, Modifier)
                Spacer(Modifier.weight(1f))
                PinKeypad(actions, Modifier)
            }
        } else {
            SidePanel("기사님 화면", GwigaonIcons.Car, "도착하면 이렇게 안내해요", m, foot = "PIN을 켜면 기사님께 \"승객에게 PIN을 물어보세요\"로 바뀌어요.") {
                MiniPanel { InfoRow(GwigaonIcons.Flag, "승객이 내리면 끝나요", "안 내리면 승객 상태를 확인해 달라고 알려 드려요") }
            }
        }
        SettingsStep.DONE -> SidePanel(
            "다음", GwigaonIcons.Car, "화면을 기사님께 넘겨 주세요", m,
            text = "기사님이 운행 시작을 누르면 비상 연락처가 바로 함께 봐요.",
            foot = "기어를 P에서 빼도 운행이 시작돼요.",
        ) {
            Column(Modifier.padding(top = GwigaonSpace.sm)) {
                InfoRow(GwigaonIcons.Mobile, SettingsTexts.CONTACTS, LinkTexts.connection(state.link.connection))
            }
        }
        null -> if (state.saved.isComplete) {
            LinkSide(state, actions, m)
        } else {
            SidePanel("기사님 화면", GwigaonIcons.Car, "지금 운행하면 이렇게 보여요", m) {
                MiniPanel {
                    Text("이번 운행은 귀가온 없이 가 주세요", style = GwigaonText.rowTitle, color = p.textPrimary)
                    Text("차 주인이 아직 설정을 마치지 않았어요.", style = GwigaonText.label, color = p.textTertiary, modifier = Modifier.padding(top = GwigaonSpace.xs))
                }
            }
        }
    }
}
