package com.coral.gwigaon.ui.debug

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import com.coral.gwigaon.arrival.ArrivalRule
import com.coral.gwigaon.arrival.ArrivalStage
import com.coral.gwigaon.arrival.ArrivalView
import com.coral.gwigaon.arrival.ui.ArrivalActions
import com.coral.gwigaon.arrival.ui.ArrivalScreen
import com.coral.gwigaon.report.AiState
import com.coral.gwigaon.report.ReportOutcome
import com.coral.gwigaon.report.ReportTone
import com.coral.gwigaon.report.ReportUiState
import com.coral.gwigaon.report.TimelineItem
import com.coral.gwigaon.report.ui.ReportScreen
import com.coral.gwigaon.trip.ArrivalInstructions
import com.coral.gwigaon.trip.DrivingPanelRule
import com.coral.gwigaon.trip.PinLock
import com.coral.gwigaon.trip.PinAttempts
import com.coral.gwigaon.trip.PinResult
import com.coral.gwigaon.trip.PinSetup
import com.coral.gwigaon.trip.Settings
import com.coral.gwigaon.trip.SettingsAccess
import com.coral.gwigaon.trip.SettingsDraft
import com.coral.gwigaon.trip.SettingsStep
import com.coral.gwigaon.trip.SavedPlaces
import com.coral.gwigaon.trip.ui.EndTripUiState
import com.coral.gwigaon.trip.ui.HomeScreen
import com.coral.gwigaon.trip.ui.PinEntryScreen
import com.coral.gwigaon.cues.ui.MicIndicator
import com.coral.gwigaon.trip.ui.HomeUiState
import com.coral.gwigaon.trip.ui.SettingsActions
import com.coral.gwigaon.trip.ui.SettingsMode
import com.coral.gwigaon.trip.ui.SettingsScreen
import kotlin.time.Instant
import com.coral.gwigaon.trip.ui.LinkUiState
import com.coral.gwigaon.sync.LinkConnection
import com.coral.gwigaon.trip.ui.SettingsUiState
import com.coral.gwigaon.trip.ui.StartScreen
import com.coral.gwigaon.ui.theme.GwigaonTheme
import com.coral.gwigaon.vehicle.Destination

/**
 * Debug only: every home / start / 설정 / arrival (D4–D7) / report (D8) prototype state with sample data, for day/night screenshots of the states a
 * real run can't easily reach (시작 중, 설정 while a trip runs).
 * `adb shell am start --user 10 -n com.coral.gwigaon/.ui.debug.ScreenGalleryActivity --es s <state>` with <state> one of
 * [STATES]' keys.
 */
class ScreenGalleryActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val key = intent.getStringExtra("s") ?: "home-ready"
        setContent { GwigaonTheme { (STATES[key] ?: STATES.getValue("home-ready"))() } }
    }

    /** A new state while the gallery is on top (e.g. in the half panel) redraws it in place. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        recreate()
    }
}

private val HOME = Destination("양재역", 37.4846, 127.0342)
private val READY = Settings(home = HOME, linkShown = true)

/** A sample account QR (a made-up token; the gallery never talks to the cloud). */
private val LINK = LinkUiState(
    url = "https://example.invalid/contact/#c=gallerySampleToken0123456",
    connection = LinkConnection.Connected(2, Instant.parse("2026-11-14T10:00:00Z")),
)
private val FULL = READY.copy(arrival = ArrivalInstructions("지하 2층", "101동 출입구", "승객에게 직접"), cues = true)
private val PIN_ON = FULL.copy(pinHash = "sample")

private fun settings(
    step: SettingsStep?,
    saved: Settings = FULL,
    draft: SettingsDraft = SettingsDraft.from(saved),
    mode: SettingsMode = SettingsMode.FIRST_RUN,
    pin: PinSetup = PinSetup(),
    access: SettingsAccess = SettingsAccess.OPEN,
    gate: PinResult? = null,
    link: LinkUiState = LINK,
    direct: Boolean = false,
): @Composable () -> Unit = {
    SettingsScreen(
        SettingsUiState(
            mode = mode, step = step, saved = saved, draft = pin.applyTo(draft), pinSetup = pin,
            choices = SavedPlaces.ALL, access = access, gateDigits = if (gate == null) "2" else "", gateResult = gate,
            link = link, direct = direct,
        ),
        SettingsActions(),
        onClose = {},
    )
}

private fun arrival(stage: ArrivalStage, pinOn: Boolean): @Composable () -> Unit = {
    val arrived = stage == ArrivalStage.ARRIVED
    ArrivalScreen(
        ArrivalView(
            stage = stage,
            pinOn = pinOn,
            title = if (arrived) ArrivalRule.ARRIVED_TITLE else "4분째 하차하지 않음",
            subline = "역삼동 · 집 (예시) · 01:31 도착",
            notes = if (arrived) DrivingPanelRule.notes(FULL.arrival) else emptyList(),
            howItEnds = when {
                !pinOn -> ArrivalRule.ENDS_WHEN_OUT
                arrived -> ArrivalRule.ENDS_WITH_PIN
                else -> ArrivalRule.ENDS_OUT_OR_PIN
            },
            hint = if (arrived) ArrivalRule.HINT else null,
            contacts = "",
            checkOpenedAt = "01:34",
        ),
        ArrivalActions(onReplay = {}),
    )
}

private fun pin(digits: String = "12", message: String? = null, lock: PinLock? = null, mic: Boolean = false): @Composable () -> Unit = {
    PinEntryScreen(
        EndTripUiState(open = true, pin = digits, message = message), lock, {}, {}, {}, {},
        micIndicator = if (mic) ({ MicIndicator(onTurnOff = {}) }) else null,
    )
}

private val SAFE_LOG = listOf(
    TimelineItem(1, "00:58", "운행 시작", null, ReportTone.NEUTRAL),
    TimelineItem(2, "01:09", "경로 이탈", "안내 경로에서 320m 벗어났어요", ReportTone.WARN),
    TimelineItem(4, "01:14", "장시간 정차", "5분 동안 정차했어요", ReportTone.WARN),
    TimelineItem(7, "01:31", "목적지 도착", null, ReportTone.OK),
    TimelineItem(8, "01:34", "승객 하차 확인 필요", "도착 후 3분째 내리지 않았어요", ReportTone.WARN),
    TimelineItem(10, "01:38", "안전 귀가", null, ReportTone.OK),
)
private val SAFE_AI = AiState.Ready(
    "00:58에 출발해 40분 만에 집에 도착했어요. 01:09에 경로를 잠깐 벗어났다가 2분 만에 돌아왔고, 01:14에는 목적지가 아닌 곳에서 5분 동안 정차했어요. 도착 후 3분 동안 승객이 내리지 않아 비상 연락처에게 알렸고, 01:38에 승객이 내려 안전하게 귀가했어요.",
)

private fun report(ai: AiState, early: Boolean = false): @Composable () -> Unit = {
    ReportScreen(
        if (early) {
            ReportUiState(
                "운행 기록 · 집 (예시)", "00:58 → 01:22 · 24분", ReportOutcome.ENDED_EARLY,
                listOf(
                    SAFE_LOG[0], SAFE_LOG[1],
                    TimelineItem(5, "01:22", "목적지 밖에서 운행 종료", "1.2km 남음", ReportTone.WARN),
                ),
                timelineLoaded = true,
                ai = if (ai is AiState.Ready) {
                    AiState.Ready("01:09에 경로를 잠깐 벗어났다가 돌아왔고, 01:22에 집까지 1.2km를 남기고 운행이 끝났어요. 왜 일찍 끝냈는지는 기록되지 않았어요.")
                } else ai,
            )
        } else {
            ReportUiState("운행 기록 · 집 (예시)", "00:58 → 01:38 · 40분", ReportOutcome.SAFE_ARRIVAL, SAFE_LOG, true, ai)
        },
        {}, {},
    )
}

private val STATES: Map<String, @Composable () -> Unit> = mapOf(
    "report" to report(SAFE_AI),
    "report-loading" to report(AiState.Loading),
    "report-failed" to report(AiState.Failed),
    "report-off" to report(AiState.Off),
    "report-early" to report(SAFE_AI, early = true),
    "arrival" to arrival(ArrivalStage.ARRIVED, pinOn = false),
    "arrival-pin" to arrival(ArrivalStage.ARRIVED, pinOn = true),
    "pin" to pin(),
    "pin-mic" to pin(mic = true),
    "pin-wrong" to pin("", "PIN이 달라요 · 2번 더 틀리면 잠깐 잠겨요"),
    "pin-locked" to pin("", lock = PinLock(5, kotlin.time.Duration.parse("27s"))),
    "wait" to arrival(ArrivalStage.WAITING, pinOn = false),
    "wait-pin" to arrival(ArrivalStage.WAITING, pinOn = true),
    "home-ready" to { HomeScreen(READY, {}, {}, onHistory = {}, onQr = {}) },
    "home-first" to { HomeScreen(Settings(), {}, {}) },
    "home-partial" to { HomeScreen(Settings(home = HOME), {}, {}) },
    "start-basic" to { StartScreen(HomeUiState(settings = READY), {}, {}, {}) },
    "start-all" to { StartScreen(HomeUiState(settings = PIN_ON), {}, {}, {}) },
    "start-busy" to { StartScreen(HomeUiState(settings = READY, starting = true), {}, {}, {}) },
    "start-unset" to { StartScreen(HomeUiState(settings = Settings(linkShown = true)), {}, {}, {}) },
    "settings-intro" to settings(SettingsStep.INTRO, saved = Settings()),
    "settings-home" to settings(SettingsStep.HOME, saved = Settings(), draft = SettingsDraft(home = HOME)),
    "settings-contacts" to settings(SettingsStep.CONTACTS, link = LINK.copy(connection = LinkConnection.NotConnected)),
    "settings-contacts-connected" to settings(SettingsStep.CONTACTS),
    "settings-contacts-preparing" to settings(SettingsStep.CONTACTS, link = LINK.copy(connection = LinkConnection.Preparing)),
    "settings-contacts-replace" to settings(SettingsStep.CONTACTS, link = LINK.copy(confirmingReplace = true)),
    "settings-contacts-nocloud" to settings(SettingsStep.CONTACTS, link = LinkUiState(connection = LinkConnection.NoCloud)),
    "settings-qr-direct" to settings(SettingsStep.CONTACTS, mode = SettingsMode.EDIT, direct = true),
    "settings-arrival" to settings(SettingsStep.ARRIVAL),
    "settings-cues" to settings(SettingsStep.CUES),
    "settings-pin-off" to settings(SettingsStep.PIN),
    "settings-pin-on" to settings(SettingsStep.PIN, draft = SettingsDraft.from(FULL).copy(pinOn = true), pin = PinSetup("1234", "")),
    "settings-pin-mismatch" to settings(SettingsStep.PIN, draft = SettingsDraft.from(FULL).copy(pinOn = true), pin = PinSetup(mismatch = true)),
    "settings-done" to settings(SettingsStep.DONE),
    "settings-edit" to settings(null, saved = PIN_ON, mode = SettingsMode.EDIT),
    "settings-edit-incomplete" to settings(null, saved = FULL.copy(linkShown = false), mode = SettingsMode.EDIT),
    "settings-gate" to settings(null, saved = PIN_ON, mode = SettingsMode.EDIT, access = SettingsAccess.NEEDS_PIN),
    "settings-gate-wrong" to settings(
        null, saved = PIN_ON, mode = SettingsMode.EDIT, access = SettingsAccess.NEEDS_PIN, gate = PinResult.Wrong(3, PinAttempts(2)),
    ),
    "settings-trip" to settings(null, access = SettingsAccess.BLOCKED_TRIP),
)
