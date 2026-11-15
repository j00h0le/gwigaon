package com.coral.gwigaon.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.coral.gwigaon.ui.theme.GwigaonTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** D3a shared components: one key state each (SOS ring fill/reset, timeline aging, controls). */
@RunWith(AndroidJUnit4::class)
class ComponentsTest {
    @get:Rule
    val compose = createComposeRule()

    private fun show(dark: Boolean = true, content: @androidx.compose.runtime.Composable () -> Unit) =
        compose.setContent { GwigaonTheme(darkTheme = dark, content = content) }

    private fun SemanticsNodeInteraction.progress(): Float =
        fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current

    private fun SemanticsNodeInteraction.fontSizeSp(): Float {
        val results = mutableListOf<TextLayoutResult>()
        fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action!!.invoke(results)
        return results.first().layoutInput.style.fontSize.value
    }

    @Test
    fun sosRingFillsWhileHeldAndResetsOnEarlyRelease() {
        var fired = 0
        show { SosHoldBar(onFire = { fired++ }) }
        compose.mainClock.autoAdvance = false
        val bar = compose.onNodeWithTag("sos_button")
        val ring = compose.onNodeWithTag("sos_ring", useUnmergedTree = true)

        bar.performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(750)
        assertTrue("half way: ${ring.progress()}", ring.progress() in 0.35f..0.65f)

        bar.performTouchInput { up() }
        compose.mainClock.advanceTimeBy(100)
        assertEquals(0f, ring.progress())
        assertEquals(0, fired)

        bar.performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(1_700)
        bar.performTouchInput { up() }
        compose.mainClock.advanceTimeBy(100)
        assertEquals(1, fired)
        assertEquals(0f, ring.progress())
    }

    @Test
    fun sosHintIsAtLeastThirtyTwoWhileDriving() {
        show { SosHoldBar(onFire = {}) }
        val hint = compose.onNodeWithTag("sos_hold_hint", useUnmergedTree = true).assertIsDisplayed()
        assertTrue(hint.fontSizeSp() >= 32f)
        compose.onNodeWithText("SOS 길게 누르기").assertIsDisplayed()
        compose.onNodeWithText("또는 좌우 깜빡이를 4번 번갈아 켜주세요").assertIsDisplayed()
    }

    @Test
    fun squareSosFiresOnlyAfterTheHold() {
        var fired = 0
        show { SosSquare(onFire = { fired++ }) }
        compose.mainClock.autoAdvance = false
        val sq = compose.onNodeWithTag("sos_square")
        sq.performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(500)
        sq.performTouchInput { up() }
        compose.mainClock.advanceTimeBy(100)
        assertEquals(0, fired)
        assertEquals(0f, compose.onNodeWithTag("sos_square_ring", useUnmergedTree = true).progress())
        sq.performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(1_700)
        sq.performTouchInput { up() }
        compose.mainClock.advanceTimeBy(100)
        assertEquals(1, fired)
    }

    @Test
    fun timelineEventAgesFromLiveToPast() {
        var age by mutableStateOf(EventAge.Live)
        show {
            TimelineEvent(
                icon = GwigaonIcons.Warn, title = "경로 이탈", detail = "경로에서 320 m",
                time = if (age == EventAge.Live) "2분째" else "01:09–01:11", age = age,
            )
        }
        val ev = compose.onNodeWithTag("timeline_event")
        assertEquals("진행 중", ev.fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription))
        compose.onNodeWithText("경로에서 320 m").assertIsDisplayed()
        compose.onNodeWithText("2분째").assertIsDisplayed()

        age = EventAge.Past
        compose.waitForIdle()
        assertEquals("지난 일", ev.fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription))
        compose.onNodeWithText("경로에서 320 m").assertDoesNotExist()
        compose.onNodeWithText("01:09–01:11").assertIsDisplayed()
        val pastSize = compose.onNodeWithText("경로 이탈", useUnmergedTree = true).fontSizeSp()
        assertTrue("past title is quieter: $pastSize", pastSize < 38f && pastSize >= 32f)
    }

    @Test
    fun timelineShowsOnlyWholeRowsInAShortSpace() {
        val rows = listOf(
            TimelineRow(GwigaonIcons.Stop, "정차", "비상 연락처에게 알렸어요", "4분째", live = true),
            TimelineRow(GwigaonIcons.Done, "경로 이탈 → 복귀", null, "01:09–01:11", live = false),
            TimelineRow(GwigaonIcons.Start, "출발", null, "00:58", live = false),
        )
        show { Box(Modifier.height(240.dp)) { Timeline(rows) } }
        compose.onNodeWithText("정차", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("출발", useUnmergedTree = true).assertDoesNotExist()
        val shown = compose.onNodeWithTag("timeline").getUnclippedBoundsInRoot()
        assertTrue("no row cut at 240 dp", shown.bottom - shown.top <= 240.dp)
    }

    @Test
    fun timelineShowsEveryRowWhenThereIsRoom() {
        val rows = listOf(
            TimelineRow(GwigaonIcons.Done, "경로 이탈 → 복귀", null, "01:09–01:11", live = false),
            TimelineRow(GwigaonIcons.Start, "출발", null, "00:58", live = false),
        )
        show { Timeline(rows) }
        compose.onNodeWithText("경로 이탈 → 복귀", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("출발", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun progressBarShowsFillAndMarks() {
        show {
            TripProgressBar(
                fraction = 0.62f,
                marks = listOf(ProgressMark(0.54f, 0.57f, live = false), ProgressMark(0.61f, 0.62f, live = true)),
            )
        }
        assertEquals(0.62f, compose.onNodeWithTag("trip_progress").progress(), 0.001f)
        compose.onAllNodesWithTag("progress_mark_live").fetchSemanticsNodes().let { assertEquals(1, it.size) }
        compose.onAllNodesWithTag("progress_mark_past").fetchSemanticsNodes().let { assertEquals(1, it.size) }
    }

    @Test
    fun statusBlockAndArrivalCardShowTheirFacts() {
        show(dark = false) {
            androidx.compose.foundation.layout.Column {
                StatusBlock(status = "목적지 근처예요", destination = "역삼동 · 집")
                ArrivalTime(eta = "01:24", detail = "도착 · 400 m")
                ArrivalNotesCard(
                    notes = listOf(ArrivalNote(GwigaonIcons.Pin, "지하 2층 B구역", "주차")),
                    onReplay = {},
                )
            }
        }
        compose.onNodeWithText("목적지 근처예요").assertIsDisplayed()
        compose.onNodeWithText("01:24").assertIsDisplayed()
        compose.onNodeWithText("승객이 남긴 도착 안내").assertIsDisplayed()
        compose.onNodeWithText("지하 2층 B구역").assertIsDisplayed()
        compose.onNodeWithTag("voice_pill").assertIsDisplayed()
    }

    @Test
    fun buttonsClickAndDisable() {
        var clicks = 0
        var enabled by mutableStateOf(true)
        show {
            androidx.compose.foundation.layout.Column {
                PrimaryButton(
                    "운행 시작", onClick = { clicks++ }, enabled = enabled, sub = "기어를 P에서 빼도 시작돼요",
                    modifier = Modifier.testTag("primary_button"),
                )
                SecondaryButton("나중에", onClick = { clicks += 10 })
            }
        }
        compose.onNodeWithText("운행 시작").performClick()
        compose.onNodeWithText("나중에").performClick()
        assertEquals(11, clicks)
        enabled = false
        compose.onNodeWithTag("primary_button").assertIsNotEnabled()
    }

    @Test
    fun chipSelectsAndSwitchToggles() {
        var selected by mutableStateOf(false)
        var on by mutableStateOf(false)
        show {
            androidx.compose.foundation.layout.Column {
                GwigaonChip("엄마", selected = selected, onClick = { selected = !selected })
                SettingSwitch("대화 감지", detail = "폭언·다툼을 알려요", checked = on, onCheckedChange = { on = it })
            }
        }
        compose.onNodeWithText("엄마").assertIsNotSelected().performClick().assertIsSelected()
        compose.onNode(isToggleable()).assertIsOff().performClick().assertIsOn()
    }

    @Test
    fun keypadTypesDeletesAndLocks() {
        val typed = StringBuilder()
        var locked by mutableStateOf(false)
        show {
            androidx.compose.foundation.layout.Column {
                PinDots(filled = typed.length)
                Keypad(onDigit = { typed.append(it) }, onDelete = { typed.setLength(typed.length - 1) }, enabled = !locked)
            }
        }
        compose.onNodeWithTag("key_1").performClick()
        compose.onNodeWithTag("key_0").performClick()
        compose.onNodeWithTag("key_9").performClick()
        compose.onNodeWithTag("key_delete").performClick()
        assertEquals("10", typed.toString())
        locked = true
        compose.onNodeWithTag("key_5").assertIsNotEnabled()
    }
}
