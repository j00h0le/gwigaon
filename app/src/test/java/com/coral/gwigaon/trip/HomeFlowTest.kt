package com.coral.gwigaon.trip

import com.coral.gwigaon.vehicle.Destination
import kotlin.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeFlowTest {
    private val home = Destination("양재역", 37.4846, 127.0342)
    private val ready = Settings(home = home, linkShown = true)

    // Home (home.html: 처음 사용 / 설정 덜 됨 / 설정 완료)

    @Test
    fun homeKindFollowsWhatIsMissing() {
        assertEquals(HomeKind.FIRST, HomeRule.kind(Settings()))
        assertEquals(HomeKind.FIRST, HomeRule.kind(Settings(cues = true, contacts = listOf(" "))))
        assertEquals(HomeKind.PARTIAL, HomeRule.kind(Settings(home = home)))
        assertEquals(HomeKind.PARTIAL, HomeRule.kind(Settings(linkShown = true)))
        assertEquals(HomeKind.READY, HomeRule.kind(ready))
    }

    @Test
    fun missingListsHomeThenContacts() {
        assertEquals(listOf(SettingsField.HOME, SettingsField.CONTACTS), HomeRule.missing(Settings()))
        assertEquals(listOf(SettingsField.CONTACTS), HomeRule.missing(Settings(home = home)))
        assertEquals(listOf(SettingsField.HOME), HomeRule.missing(Settings(linkShown = true)))
        assertTrue(HomeRule.missing(ready).isEmpty())
    }

    // Start screen briefing (start.html: PIN 꺼짐 / PIN · 대화 감지)

    @Test
    fun briefingWithPinOffEndsByArrival() {
        assertEquals(listOf(BriefRow.RECORD, BriefRow.END_AUTO, BriefRow.SOS), StartBriefing.rows(ready))
    }

    @Test
    fun briefingWithPinAndCuesNamesBothAndKeepsSosLast() {
        assertEquals(
            listOf(BriefRow.RECORD, BriefRow.CUES, BriefRow.END_PIN, BriefRow.SOS),
            StartBriefing.rows(ready.copy(cues = true, pinHash = "h")),
        )
    }

    // 설정 steps

    @Test
    fun stepsRunInOrderAndStopAtDone() {
        val order = generateSequence(SettingsStep.INTRO) { s -> SettingsFlow.next(s).takeIf { it != s } }.toList()
        assertEquals(SettingsStep.entries.toList(), order)
        assertEquals(SettingsStep.DONE, SettingsFlow.next(SettingsStep.DONE))
        assertEquals(5, SettingsStep.entries.count { it.number != null })
        assertEquals(SettingsStep.NUMBERED, SettingsStep.PIN.number)
    }

    @Test
    fun backGoesOneStepAndLeavesFromIntroOrDone() {
        assertNull(SettingsFlow.back(SettingsStep.INTRO))
        assertNull(SettingsFlow.back(SettingsStep.DONE))
        assertEquals(SettingsStep.INTRO, SettingsFlow.back(SettingsStep.HOME))
        assertEquals(SettingsStep.CUES, SettingsFlow.back(SettingsStep.PIN))
    }

    @Test
    fun firstRunOpensAtTheIntroOrWhereItStopped() {
        assertEquals(SettingsStep.INTRO, SettingsFlow.firstRunEntry(Settings()))
        assertEquals(SettingsStep.CONTACTS, SettingsFlow.firstRunEntry(Settings(home = home)))
        assertEquals(SettingsStep.HOME, SettingsFlow.firstRunEntry(Settings(contacts = listOf("엄마"))))
        assertEquals(SettingsStep.DONE, SettingsFlow.firstRunEntry(ready))
    }

    @Test
    fun homeStepNeedsAPlace() {
        assertFalse(SettingsFlow.canAdvance(SettingsStep.HOME, SettingsDraft(), Settings()))
        assertTrue(SettingsFlow.canAdvance(SettingsStep.HOME, SettingsDraft(home = home), Settings()))
    }

    @Test
    fun contactsStepOnlyShowsTheQrSoItAlwaysMovesOn() {
        assertTrue(SettingsFlow.canAdvance(SettingsStep.CONTACTS, SettingsDraft(), Settings()))
        assertTrue(SettingsFlow.canAdvance(SettingsStep.CONTACTS, SettingsDraft(), ready, editing = true))
    }

    @Test
    fun arrivalStepRefusesOverlongNotesOnly() {
        assertTrue(SettingsFlow.canAdvance(SettingsStep.ARRIVAL, SettingsDraft(), Settings()))
        val long = SettingsDraft(arrival = ArrivalInstructions(parking = "가".repeat(Settings.MAX_NOTE + 1)))
        assertFalse(SettingsFlow.canAdvance(SettingsStep.ARRIVAL, long, Settings()))
    }

    @Test
    fun introCuesAndDoneAlwaysAdvance() {
        listOf(SettingsStep.INTRO, SettingsStep.CUES, SettingsStep.DONE).forEach {
            assertTrue(it.name, SettingsFlow.canAdvance(it, SettingsDraft(), Settings()))
        }
    }

    @Test
    fun pinStepNeedsAConfirmedPinUnlessOffOrKeepingTheOldOne() {
        assertTrue(SettingsFlow.canAdvance(SettingsStep.PIN, SettingsDraft(pinOn = false), Settings()))
        assertFalse(SettingsFlow.canAdvance(SettingsStep.PIN, SettingsDraft(pinOn = true), Settings()))
        val typed = PinSetup("2580", "2580").applyTo(SettingsDraft(pinOn = true))
        assertTrue(SettingsFlow.canAdvance(SettingsStep.PIN, typed, Settings()))
        assertTrue(SettingsFlow.canAdvance(SettingsStep.PIN, SettingsDraft(pinOn = true), Settings(pinHash = "h")))
        val half = PinSetup("2580", "25").applyTo(SettingsDraft(pinOn = true))
        assertFalse(SettingsFlow.canAdvance(SettingsStep.PIN, half, Settings(pinHash = "h")))
    }

    // PIN on the keypad (settings.html PIN 입력 / PIN 불일치)

    @Test
    fun pinSetupTakesFourThenTheSameFourAgain() {
        var s = PinSetup()
        "2580".forEach { s = s.digit(it) }
        assertTrue(s.confirming)
        assertFalse(s.complete)
        assertEquals(0, s.filled)
        "258".forEach { s = s.digit(it) }
        assertEquals(3, s.filled)
        s = s.digit('0')
        assertTrue(s.complete)
        assertEquals(4, s.filled)
        assertEquals(s, s.digit('1')) // nothing after it is complete
    }

    @Test
    fun aDifferentSecondEntryStartsOver() {
        var s = PinSetup("2580", "258")
        s = s.digit('1')
        assertEquals(PinSetup(mismatch = true), s)
        s = s.digit('1')
        assertFalse(s.mismatch)
        assertEquals("1", s.first)
    }

    @Test
    fun pinSetupIgnoresNonDigitsAndDeletesBackIntoTheFirstEntry() {
        assertEquals(PinSetup(), PinSetup().digit('a'))
        assertEquals(PinSetup("2580", "2"), PinSetup("2580", "25").delete())
        assertEquals(PinSetup("258"), PinSetup("2580").delete())
        assertEquals(PinSetup(), PinSetup().delete())
    }

    @Test
    fun pinSetupFillsTheDraftSoItsRulesApply() {
        val draft = PinSetup("2580", "2580").applyTo(SettingsDraft(pinOn = true))
        assertEquals("2580", draft.pin)
        assertEquals("2580", draft.pinConfirm)
        assertTrue(draft.errors(Settings()).isEmpty())
    }

    // 비상 연락처 (ADR 0019)

    @Test
    fun seeingTheQrStepCompletesContactsWithoutNames() {
        val seen = Settings(home = home, linkShown = true)
        assertTrue(seen.isComplete)
        assertEquals(HomeKind.READY, HomeRule.kind(seen))
        assertEquals(listOf(SettingsField.CONTACTS), HomeRule.missing(Settings(home = home)))
        assertTrue(SettingsFlow.canAdvance(SettingsStep.CONTACTS, SettingsDraft(), Settings()))
        // 설정 saved before ADR 0019 with names stays complete.
        assertTrue(Settings(home = home, contacts = listOf("엄마")).isComplete)
    }

    @Test
    fun linkShownSurvivesTheDraftRoundTrip() {
        val saved = Settings(home = home, linkShown = true)
        assertTrue(SettingsDraft.from(saved).toSettings(saved, PinHasher())!!.linkShown)
    }

    // Summary and door texts (settings.html 완료 / 편집, home.html)

    @Test
    fun summaryTexts() {
        assertNull(SettingsTexts.home(Settings()))
        assertEquals("양재역", SettingsTexts.home(ready))
        assertEquals("지하 2층 · 키는 직접", SettingsTexts.arrival(ArrivalInstructions(" 지하 2층 ", "", "키는 직접")))
        assertEquals("없음 · 건너뛰어도 돼요", SettingsTexts.arrival(ArrivalInstructions()))
        assertEquals("켜짐 · 기사님 화면에도 표시돼요", SettingsTexts.cues(true))
        assertEquals("꺼짐", SettingsTexts.cues(false))
        assertEquals("켜짐 · 설정을 열 때도 PIN이 필요해요", SettingsTexts.pin(true))
        assertEquals("꺼짐 · 내가 내리면 운행이 끝나요", SettingsTexts.pin(false))
        assertEquals("양재역 · PIN 꺼짐", SettingsTexts.doorSummary(ready))
        assertEquals("양재역 · PIN 켜짐", SettingsTexts.doorSummary(ready.copy(pinHash = "h")))
    }

    @Test
    fun gateTexts() {
        assertEquals("5번 틀리면 30초 동안 잠겨요", SettingsTexts.gate(null, 0))
        assertEquals("PIN이 맞지 않아요 · 3번 남았어요", SettingsTexts.gate(PinResult.Wrong(3, PinAttempts(2)), 0))
        val locked = PinResult.LockedOut(Instant.fromEpochSeconds(30), PinAttempts(5))
        assertEquals("12초 뒤에 다시 입력해 주세요", SettingsTexts.gate(locked, 12))
        assertEquals("1초 뒤에 다시 입력해 주세요", SettingsTexts.gate(locked, 0))
    }

    @Test
    fun homeCopyFollowsOwnersWording() {
        // D3f H3–H7: one-line titles, the gear hint names D.
        assertEquals("환영합니다", HomeTexts.FIRST_TITLE)
        assertTrue('\n' !in HomeTexts.PARTIAL_TITLE)
        assertEquals("기어를 D로 바꾸면 자동으로 시작해요", HomeTexts.GEAR_HINT)
        assertEquals(3, HomeTexts.SPLIT_HINT.lines().size)
        assertEquals("모니터링 QR", SettingsTexts.CONTACTS)
        assertEquals("모니터링 QR 공유", SettingsTexts.CONTACTS_STEP)
    }
}
