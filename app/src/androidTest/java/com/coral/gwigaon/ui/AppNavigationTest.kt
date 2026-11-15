package com.coral.gwigaon.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.coral.gwigaon.cues.ClipStore
import com.coral.gwigaon.cues.CueMonitor
import com.coral.gwigaon.cues.CueSwitch
import com.coral.gwigaon.cues.MicSource
import com.coral.gwigaon.report.ReportRepository
import com.coral.gwigaon.report.ReportViewModel
import com.coral.gwigaon.report.ui.ReportRoute
import com.coral.gwigaon.sos.SosController
import com.coral.gwigaon.trip.ArrivalInstructions
import com.coral.gwigaon.trip.CheckInAnswer
import com.coral.gwigaon.trip.CueKind
import com.coral.gwigaon.trip.EndReason
import com.coral.gwigaon.trip.MonitoringConfig
import com.coral.gwigaon.trip.SafeArrivalCloser
import com.coral.gwigaon.trip.TripStatus
import com.coral.gwigaon.trip.SosSource
import com.coral.gwigaon.trip.PinHasher
import com.coral.gwigaon.trip.TripEvent
import com.coral.gwigaon.trip.TripSessionRepository
import com.coral.gwigaon.trip.TripSetup
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import com.coral.gwigaon.trip.db.GwigaonDatabase
import com.coral.gwigaon.trip.db.RoomTripStore
import com.coral.gwigaon.trip.SettingsRepository
import com.coral.gwigaon.trip.StartSource
import com.coral.gwigaon.trip.TripStarter
import com.coral.gwigaon.trip.db.RoomSettingsStore
import com.coral.gwigaon.trip.ui.HomeViewModel
import com.coral.gwigaon.trip.ui.NoTripScreens
import com.coral.gwigaon.trip.ui.SettingsViewModel
import com.coral.gwigaon.trip.ui.TripViewModel
import com.coral.gwigaon.ui.theme.GwigaonTheme
import com.coral.gwigaon.vehicle.Destination
import com.coral.gwigaon.vehicle.FakeVehicleSignals
import com.coral.gwigaon.vehicle.Gear
import com.coral.gwigaon.vehicle.Signal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.ZoneId
import kotlin.time.Clock

/** Parked-only gating over the real repository (Room in memory), ViewModels and the fake car (SPEC criterion 4). */
@RunWith(AndroidJUnit4::class)
class AppNavigationTest {
    @get:Rule
    val compose = createComposeRule()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var db: GwigaonDatabase
    private lateinit var car: FakeVehicleSignals
    private lateinit var sessions: TripSessionRepository
    private lateinit var sos: SosController
    private val spoken = java.util.Collections.synchronizedList(mutableListOf<String>())

    @Before
    fun setUp() {
        db = GwigaonDatabase.inMemory(ApplicationProvider.getApplicationContext())
        car = FakeVehicleSignals(scope, initialGear = Gear.PARK)
        sessions = TripSessionRepository(RoomTripStore(db), PinHasher(iterations = 1_000), Clock.System)
        sos = SosController(sessions, Clock.System, MonitoringConfig.DEFAULT)
    }

    @After
    fun tearDown() {
        db.close()
        scope.cancel()
    }

    private fun show(width: Dp? = null, withReport: Boolean = false, cues: CueMonitor? = null) {
        val settings = SettingsRepository(RoomSettingsStore(db), PinHasher(iterations = 1_000), Clock.System) { sessions.active.value != null }
        runBlocking { settings.load().getOrThrow() }
        val starter = TripStarter(sessions, settings, car::setDestination, {})
        val homeVm = HomeViewModel(settings.settings, sessions.active, { starter.start(StartSource.BUTTON) })
        val settingsVm = SettingsViewModel(settings, car.drivingState, sessions.active, sessions::recentDestinations)
        val tripVm = TripViewModel(sessions, car.drivingState, Clock.System, sos, cues = cues, speaker = { spoken += it })
        val reports = ReportRepository(sessions::events, cloud = null)
        compose.setContent {
            GwigaonTheme {
                val active by sessions.active.collectAsStateWithLifecycle()
                val driving by car.drivingState.collectAsStateWithLifecycle()
                val unavailable by car.unavailable.collectAsStateWithLifecycle()
                val ended by sessions.lastEnded.collectAsStateWithLifecycle()
                val listening = cues?.listening?.collectAsStateWithLifecycle()?.value == true
                val report: (@Composable () -> Unit)? = ended?.takeIf { withReport }?.let { trip ->
                    {
                        val vm = remember(trip.id) { ReportViewModel(trip, reports, ZoneId.of("Asia/Seoul"), sessions::closeReport, scope) }
                        ReportRoute(vm)
                    }
                }
                Box(if (width != null) Modifier.width(width) else Modifier) {
                    AppNavigation(active, driving, unavailable, tripVm, report = report, listening = listening) {
                        NoTripScreens(homeVm, settingsVm, link = null, onLocationGranted = {})
                    }
                }
            }
        }
    }

    private fun startTrip() = runBlocking {
        sessions.start(TripSetup(Destination("양재역", 37.484, 127.034), "언니", "2580")).getOrThrow()
    }

    /** D6: the passenger taps the PIN on the keypad; the 4th digit checks it. */
    private fun typePin(digits: String) {
        compose.onNodeWithTag("pin_entry_screen").assertIsDisplayed()
        digits.forEach { compose.onNodeWithTag("key_$it").performClick() }
    }

    /** Gear D must switch screens within 1 s (T10 acceptance). */
    private fun assertSwitchesTo(tag: String) {
        compose.waitUntil(1_000) { compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun setupLeavesForTheDrivingScreenInD() {
        show()
        compose.onNodeWithTag("start").assertExists()

        car.setGear(Gear.DRIVE)
        assertSwitchesTo("driving_screen")
        compose.onNodeWithTag("start").assertDoesNotExist()
        compose.onNodeWithText("주차(P) 후에 운행을 시작할 수 있어요").assertIsDisplayed()

        car.setGear(Gear.PARK)
        assertSwitchesTo("start")
    }

    @Test
    fun unknownGearCountsAsDriving() {
        show()
        car.setGear(null)
        assertSwitchesTo("driving_screen")
        compose.onNodeWithTag("start").assertDoesNotExist()
        compose.onNodeWithTag("cannot_tell").assertDoesNotExist()
    }

    @Test
    fun gearFailureAfterParkLeavesSetupAndSaysWhy() {
        show()
        car.setUnavailable(setOf(Signal.GEAR, Signal.LOCATION)) // last gear was P
        assertSwitchesTo("driving_screen")
        compose.onNodeWithTag("start").assertDoesNotExist()
        compose.onNodeWithTag("cannot_tell").assertIsDisplayed()
    }

    @Test
    fun parkedTripViewAndOpenEndDialogLeaveInD() {
        startTrip()
        show()
        compose.onNodeWithTag("trip_screen").assertExists()
        compose.onNodeWithTag("end_trip").performClick()
        typePin("25")

        car.setGear(Gear.DRIVE)
        assertSwitchesTo("driving_screen")
        compose.onNodeWithTag("pin_entry_screen").assertDoesNotExist()
        compose.onNodeWithTag("end_trip").assertDoesNotExist()
        compose.onNodeWithText("안전하게 가고 있어요").assertIsDisplayed()
        // No arrival time yet (ADR 0018): the numeral holds its place.
        compose.onNodeWithText("--:--", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("도착 시간 확인 중", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("양재역").assertIsDisplayed()

        // Parking again doesn't bring back the PIN screen or the half-typed PIN.
        car.setGear(Gear.PARK)
        assertSwitchesTo("trip_screen")
        compose.onNodeWithTag("pin_entry_screen").assertDoesNotExist()
        compose.onNodeWithTag("end_trip").performClick()
        compose.onNodeWithTag("pin_dots").assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf("4 자리 중 0 자리 입력")))
    }

    @Test
    fun endingTheTripNeedsThePin() {
        val trip = startTrip()
        show()
        compose.onNodeWithTag("end_trip").performClick()
        typePin("0000")
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasTestTag("pin_entry_screen")).fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodesWithText("PIN이 달라요 · 4번 더 틀리면 잠깐 잠겨요").fetchSemanticsNodes().isNotEmpty()
        }
        assertNotNull(sessions.active.value)
        // A wrong PIN clears the dots.
        compose.onNodeWithTag("pin_dots").assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf("4 자리 중 0 자리 입력")))

        typePin("2580")
        compose.waitUntil(5_000) { sessions.active.value == null }
        assertSwitchesTo("start")
        val last = runBlocking { RoomTripStore(db).events(trip.trip.id).getOrThrow().last().event }
        assertEquals(EndReason.ENDED_BY_PASSENGER, (last as TripEvent.TripEnded).endReason)
    }

    @Test
    fun theReportFollowsTheEndedTripAndIsUnreachableWhileDriving() {
        startTrip()
        show(withReport = true)
        compose.onNodeWithTag("end_trip").performClick()
        typePin("2580")
        assertSwitchesTo("report_screen")
        compose.onNodeWithTag("timeline_1").assertIsDisplayed()

        car.setGear(Gear.DRIVE)
        assertSwitchesTo("driving_screen")
        compose.onNodeWithTag("report_screen").assertDoesNotExist()
        compose.onNodeWithTag("report_close").assertDoesNotExist()

        car.setGear(Gear.PARK)
        assertSwitchesTo("report_screen")
        compose.onNodeWithTag("report_close").performClick()
        assertSwitchesTo("start")
        compose.onNodeWithTag("report_screen").assertDoesNotExist()
    }

    @Test
    fun cancelKeepsTheTrip() {
        startTrip()
        show()
        compose.onNodeWithTag("end_trip").performClick()
        compose.onNodeWithTag("pin_entry_close").performClick()
        compose.onNodeWithTag("pin_entry_screen").assertDoesNotExist()
        compose.onNodeWithTag("trip_screen").assertExists()
        assertNotNull(sessions.active.value)
    }

    @Test
    fun cautionShowsOnTheDrivingScreen() {
        startTrip()
        runBlocking { sessions.log(TripEvent.RouteDeviation(distanceM = 420, reason = "경로 이탈")).getOrThrow() }
        show()
        car.setGear(Gear.DRIVE)
        assertSwitchesTo("driving_screen")
        // D3b: the status says it in the driver's words, and the live row on the timeline says how far.
        compose.onNodeWithTag("status_line", useUnmergedTree = true).assertTextContains("경로를 벗어났어요")
        compose.onNodeWithText("경로 이탈", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("경로에서 420m", useUnmergedTree = true).assertIsDisplayed()
    }

    // Portrait app windows: Connect-S 840 dp, Connect-L 628 dp (research/findings/pleos-figma-kit.md).
    @Test
    fun drivingScreenFitsConnectSPortrait() = assertFits(840.dp)

    @Test
    fun drivingScreenFitsConnectLPortrait() = assertFits(628.dp)

    private fun assertFits(width: Dp) {
        startTrip()
        show(width)
        car.setGear(Gear.DRIVE)
        assertSwitchesTo("driving_screen")
        for (tag in listOf("status_line", "destination")) {
            val bounds = compose.onNodeWithTag(tag, useUnmergedTree = true).assertIsDisplayed().getUnclippedBoundsInRoot()
            assertTrue("$tag fits $width", bounds.right <= width)
        }
        // Pinned to the top in the half panel (ADR 0003), not floating mid-screen.
        val status = compose.onNodeWithTag("status_line", useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertTrue("status at the top at $width", status.top < 160.dp)
    }

    // --- Arrival card (T13) ---

    private fun startTripNearDestination(arrival: ArrivalInstructions) = runBlocking {
        sessions.start(TripSetup(Destination("양재역", 37.484, 127.034), "언니", "2580", arrival)).getOrThrow()
        sessions.log(TripEvent.NearDestination("목적지 500m 이내")).getOrThrow()
    }

    @Test
    fun arrivalCardShowsWhileDrivingNearTheDestinationAboveSos() {
        startTripNearDestination(ArrivalInstructions("지하 2층", "101동 정문", "키는 경비실에"))
        show(628.dp)
        car.setGear(Gear.DRIVE)
        assertSwitchesTo("driving_screen")

        compose.onNodeWithTag("status_line", useUnmergedTree = true).assertTextContains("목적지 근처예요")
        // D3b: the glance zone never moves, so the destination stays; the notes take the timeline's place.
        compose.onNodeWithTag("destination", useUnmergedTree = true).assertTextContains("양재역", substring = true)
        compose.onNodeWithTag("timeline").assertDoesNotExist()
        compose.onAllNodesWithTag("arrival_line", useUnmergedTree = true).assertCountEquals(3)
        compose.onNodeWithText("지하 2층", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("101동 정문", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("키는 경비실에", useUnmergedTree = true).assertIsDisplayed()
        val card = compose.onNodeWithTag("arrival_card").assertIsDisplayed().getUnclippedBoundsInRoot()
        val sosButton = compose.onNodeWithTag("sos_button").assertIsDisplayed().getUnclippedBoundsInRoot()
        assertTrue("card above SOS", card.bottom <= sosButton.top)
        assertTrue("card fits 628 dp", card.right <= 628.dp)
    }

    @Test
    fun noArrivalCardWithoutInstructionsOrBeforeTheDestination() {
        startTripNearDestination(ArrivalInstructions())
        show()
        compose.onNodeWithTag("arrival_card").assertDoesNotExist()
        compose.onNodeWithText("양재역").assertIsDisplayed()
    }

    @Test
    fun noArrivalCardWhileStillFarAway() {
        runBlocking {
            sessions.start(TripSetup(Destination("양재역", 37.484, 127.034), "언니", "2580", ArrivalInstructions("지하 1층")))
                .getOrThrow()
        }
        show()
        compose.onNodeWithTag("arrival_card").assertDoesNotExist()
    }

    // --- Safe-arrival check (T14) ---

    private fun openSafeArrivalCheck() = runBlocking {
        val trip = sessions.start(TripSetup(Destination("양재역", 37.484, 127.034), "언니", "2580")).getOrThrow()
        sessions.log(TripEvent.NearDestination()).getOrThrow()
        sessions.log(TripEvent.Arrived()).getOrThrow()
        sessions.log(TripEvent.SafeArrivalCheckOpened("도착 후 30초째 승객이 하차하지 않음")).getOrThrow()
        trip
    }

    @Test
    fun theWaitScreenCountsUpAsksNobodyAndLeavesInD() {
        openSafeArrivalCheck()
        show(628.dp)
        compose.onNodeWithTag("arrival_wait_screen").assertExists()
        compose.onNodeWithTag("arrival_title").assertTextContains("째 하차하지 않음", substring = true)
        compose.onNodeWithText("비상 연락처에게 알렸어요").assertIsDisplayed()
        compose.onNodeWithText("언니").assertIsDisplayed()
        compose.onNodeWithTag("arrival_how").assertTextContains("승객이 내리거나 PIN을 입력하면 운행이 끝나요.")
        // ADR 0020: no driver check, no 승객 반응 있음; a PIN trip can end with the PIN.
        compose.onNodeWithTag("passenger_responsive").assertDoesNotExist()
        compose.onNodeWithText("이름을 부르고 어깨를 두드려 주세요").assertDoesNotExist()
        val end = compose.onNodeWithTag("arrival_end").assertTextContains("PIN으로 종료하기").getUnclippedBoundsInRoot()
        val sosBar = compose.onNodeWithTag("sos_button").assertIsDisplayed().getUnclippedBoundsInRoot()
        assertTrue("SOS last", end.bottom <= sosBar.top)
        assertTrue("SOS fits 628 dp", sosBar.right <= 628.dp)
        compose.onNodeWithTag("end_trip").assertDoesNotExist()

        car.setGear(Gear.DRIVE)
        assertSwitchesTo("driving_screen")
        compose.onNodeWithTag("arrival_end").assertDoesNotExist()
    }

    @Test
    fun passengerPinClosesTheCheck() {
        val trip = openSafeArrivalCheck()
        show()
        compose.onNodeWithTag("arrival_end").performClick()
        compose.onNodeWithText("PIN을 입력해 주세요").assertIsDisplayed()
        compose.onNodeWithTag("sos_button").assertDoesNotExist() // the passenger's screen; turn-signal SOS still works
        typePin("2580")
        compose.waitUntil(5_000) { sessions.active.value?.state?.safeArrivalConfirmed == true }
        val confirmed = events(trip.trip.id).last() as TripEvent.SafeArrivalConfirmed
        assertEquals(SafeArrivalCloser.PASSENGER_PIN, confirmed.closedBy)
        compose.onNodeWithTag("pin_entry_screen").assertDoesNotExist()
    }

    @Test
    fun aKeypadOpenedOnD4ClosesTheCheckThatOpensWhileTyping() {
        // F1 (Checkpoint F review R4): what the PIN does is decided when it is checked, not when the keypad opened.
        val trip = arrive(pin = "2580")
        show()
        compose.onNodeWithTag("arrival_end").performClick()
        typePin("258")
        runBlocking { sessions.log(TripEvent.SafeArrivalCheckOpened("도착 후 30초째 승객이 하차하지 않음")).getOrThrow() }
        compose.waitUntil(5_000) { sessions.active.value?.state?.status == TripStatus.SAFE_ARRIVAL_CHECK }
        compose.onNodeWithTag("key_0").performClick()
        // The PIN closes the check (SafeArrivalMonitor then ends the trip as safe_arrival; not running here).
        compose.waitUntil(5_000) { sessions.active.value?.state?.safeArrivalConfirmed == true }
        val log = events(trip.id)
        assertEquals(SafeArrivalCloser.PASSENGER_PIN, log.filterIsInstance<TripEvent.SafeArrivalConfirmed>().single().closedBy)
        assertTrue(log.none { it is TripEvent.TripEnded }) // not refused as an END_TRIP while the check was open
        compose.onNodeWithTag("pin_entry_screen").assertDoesNotExist()
    }

    @Test
    fun aTripThatEndsUnderTheKeypadLeavesNoKeypadForTheNextTrip() {
        // Checkpoint F review R1: the seat ends the trip while the passenger's keypad is open.
        arrive(pin = "2580")
        show()
        compose.onNodeWithTag("arrival_end").performClick()
        typePin("25")
        runBlocking {
            sessions.log(TripEvent.SafeArrivalCheckOpened()).getOrThrow()
            sessions.log(TripEvent.SafeArrivalConfirmed(SafeArrivalCloser.SEAT_EMPTY)).getOrThrow()
            sessions.log(TripEvent.TripEnded(EndReason.SAFE_ARRIVAL)).getOrThrow()
        }
        compose.waitUntil(5_000) { sessions.active.value == null }
        compose.waitForIdle()
        startTrip() // button start, still parked: no gear change to clear anything
        compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag("trip_screen")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("pin_entry_screen").assertDoesNotExist()
    }

    // --- Arrival D4–D7 (D3d) ---

    private fun arrive(pin: String, arrival: ArrivalInstructions = ArrivalInstructions("지하 2층", "101동 정문", "승객에게 직접")) = runBlocking {
        sessions.start(TripSetup(Destination("양재역", 37.484, 127.034), "엄마, 동생", pin, arrival)).getOrThrow().trip
            .also {
                sessions.log(TripEvent.NearDestination()).getOrThrow()
                sessions.log(TripEvent.Arrived()).getOrThrow()
            }
    }

    @Test
    fun arrivedWithoutAPinShowsD4AndOnePressEndsAtArrival() {
        val trip = arrive(pin = "")
        show(628.dp)
        compose.onNodeWithTag("arrival_screen").assertIsDisplayed()
        compose.onNodeWithText("도착했어요", useUnmergedTree = true).assertIsDisplayed()
        compose.onAllNodesWithTag("arrival_line", useUnmergedTree = true).assertCountEquals(3)
        compose.onNodeWithText("다시 듣기", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("arrival_how").assertTextContains("승객이 내리면 운행이 끝나요", substring = true)
        compose.onNodeWithTag("arrival_hint", useUnmergedTree = true).assertTextEquals("하차 전에 승객 상태를 한 번 확인해 주세요")
        val card = compose.onNodeWithTag("arrival_card").getUnclippedBoundsInRoot()
        val how = compose.onNodeWithTag("arrival_how").getUnclippedBoundsInRoot()
        assertTrue("how it ends sits under the card", how.top >= card.bottom)
        val square = compose.onNodeWithTag("sos_square").assertIsDisplayed().getUnclippedBoundsInRoot()
        val end = compose.onNodeWithTag("arrival_end").assertTextContains("운행 종료").getUnclippedBoundsInRoot()
        assertTrue("square SOS left of the end action", square.right <= end.left)
        assertTrue("end action fits 628 dp", end.right <= 628.dp)

        compose.onNodeWithTag("arrival_end").performClick()
        compose.waitUntil(5_000) { sessions.active.value == null }
        assertEquals(EndReason.ENDED_AT_ARRIVAL, (events(trip.id).last() as TripEvent.TripEnded).endReason)
    }

    @Test
    fun replayingTheNotesSpeaksThemAgain() {
        arrive(pin = "")
        show()
        compose.onNodeWithTag("voice_pill").performClick()
        compose.waitUntil(2_000) { spoken.isNotEmpty() }
        assertEquals("승객이 남긴 안내입니다. 출입문은 101동 정문, 주차는 지하 2층, 차 키는 승객에게 직접 부탁드립니다. 감사합니다.", spoken.last())
    }

    @Test
    fun arrivedWithAPinAsksThePassengerAndLeavesInD() {
        val trip = arrive(pin = "2580", arrival = ArrivalInstructions())
        show()
        compose.onNodeWithTag("arrival_card").assertDoesNotExist() // no notes, no card
        compose.onNodeWithTag("arrival_how").assertTextContains("PIN을 입력해야 운행이 끝나요", substring = true)
        compose.onNodeWithTag("arrival_end").assertTextContains("PIN으로 종료하기").performClick()
        typePin("25")

        car.setGear(Gear.DRIVE)
        assertSwitchesTo("driving_screen")
        compose.onNodeWithTag("pin_entry_screen").assertDoesNotExist()
        compose.onNodeWithTag("arrival_screen").assertDoesNotExist()
        car.setGear(Gear.PARK)
        assertSwitchesTo("arrival_screen")

        compose.onNodeWithTag("arrival_end").performClick()
        typePin("2580")
        compose.waitUntil(5_000) { sessions.active.value == null }
        assertEquals(EndReason.ENDED_BY_PASSENGER, (events(trip.id).last() as TripEvent.TripEnded).endReason)
    }

    @Test
    fun fiveWrongPinsLockTheKeypadWithACountdownThatSurvivesReopening() {
        val trip = arrive(pin = "2580")
        show()
        compose.onNodeWithTag("arrival_end").performClick()
        repeat(5) { i ->
            typePin("0000")
            compose.waitUntil(5_000) { sessions.active.value?.trip?.pinAttempts?.failures == i + 1 }
        }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("pin_lock_countdown").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("잠시 후 다시 입력해 주세요").assertIsDisplayed()
        compose.onNodeWithText("5번 틀려서 잠겼어요 · 비상 연락처 기록에 남았어요").assertIsDisplayed()
        compose.onNodeWithTag("key_2").assertIsNotEnabled()
        assertTrue(events(trip.id).any { it is TripEvent.PinLockout })

        compose.onNodeWithTag("pin_entry_close").performClick()
        compose.onNodeWithTag("arrival_end").performClick()
        compose.onNodeWithTag("pin_lock_countdown").assertIsDisplayed()
    }

    @Test
    fun withoutAPinTheWaitHasNoEndButtonOnlyTheSeatEndsIt() {
        arrive(pin = "")
        runBlocking { sessions.log(TripEvent.SafeArrivalCheckOpened()).getOrThrow() }
        show()
        compose.onNodeWithTag("arrival_wait_screen").assertIsDisplayed()
        compose.onNodeWithTag("arrival_how").assertTextContains("승객이 내리면 운행이 끝나요.")
        compose.onNodeWithTag("arrival_end").assertDoesNotExist() // no PIN: the seat ends it (ADR 0007, 0020)
        compose.onNodeWithTag("passenger_responsive").assertDoesNotExist()
        compose.onNodeWithTag("sos_button").assertIsDisplayed()
    }

    // --- SOS (T11) ---

    @Test
    fun withoutAPinAMidTripEndNeedsTheHoldAndSaysWhy() {
        val trip = runBlocking { sessions.start(TripSetup(Destination("양재역", 37.484, 127.034), "언니")).getOrThrow().trip }
        show()
        compose.onNodeWithTag("end_trip").performClick()
        compose.onNodeWithTag("end_trip_pin").assertDoesNotExist()
        compose.onNodeWithTag("end_trip_title").assertTextContains("목적지 밖이에요") // D3f D11; no fix here, so no distance line
        // A tap is not a hold.
        compose.onNodeWithTag("end_trip_hold").performClick()
        compose.onNodeWithTag("end_trip_dialog").assertExists()
        compose.onNodeWithTag("end_trip_hold").performTouchInput { down(center); advanceEventTime(2_100); up() }
        compose.waitUntil(5_000) { sessions.active.value == null }
        assertEquals(EndReason.ENDED_EARLY, (events(trip.id).last() as TripEvent.TripEnded).endReason)
    }

    @Test
    fun withoutAPinArrivingEndsWithOnePress() {
        val trip = runBlocking {
            sessions.start(TripSetup(Destination("양재역", 37.484, 127.034), "언니")).getOrThrow().trip
                .also { sessions.log(TripEvent.NearDestination()).getOrThrow() }
        }
        show()
        compose.onNodeWithTag("end_trip").performClick()
        compose.onNodeWithTag("end_trip_confirm").performClick()
        compose.waitUntil(5_000) { sessions.active.value == null }
        assertEquals(EndReason.ENDED_AT_ARRIVAL, (events(trip.id).last() as TripEvent.TripEnded).endReason)
    }

    private fun events(tripId: String) = runBlocking { RoomTripStore(db).events(tripId).getOrThrow().map { it.event } }

    /** Presses the SOS button for [ms] of test time, then lifts. */
    private fun holdSos(ms: Long) {
        compose.onNodeWithTag("sos_button").performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(ms)
        // A completed hold fires at once, and the SOS screen may already have replaced the bar: nothing to lift.
        if (compose.onAllNodes(hasTestTag("sos_button")).fetchSemanticsNodes().isNotEmpty()) {
            compose.onNodeWithTag("sos_button").performTouchInput { up() }
        }
        compose.mainClock.advanceTimeBy(100)
    }

    @Test
    fun sosButtonIsOnTheParkedTripScreen() {
        startTrip()
        show()
        compose.onNodeWithTag("trip_screen").assertExists()
        compose.onNodeWithTag("sos_button").assertIsDisplayed()
    }

    @Test
    fun sosButtonIsOnTheDrivingScreen() {
        startTrip()
        show()
        car.setGear(Gear.DRIVE)
        assertSwitchesTo("driving_screen")
        compose.onNodeWithTag("sos_button").assertIsDisplayed()
    }

    @Test
    fun noSosButtonWithoutATrip() {
        show()
        car.setGear(Gear.DRIVE)
        assertSwitchesTo("driving_screen")
        compose.onNodeWithTag("sos_button").assertDoesNotExist()
    }

    @Test
    fun shortPressDoesNotFire() {
        val trip = startTrip()
        show()
        compose.mainClock.autoAdvance = false
        holdSos(1_000)
        compose.mainClock.autoAdvance = true
        compose.onNodeWithTag("sos_screen").assertDoesNotExist()
        assertTrue(events(trip.trip.id).none { it is TripEvent.Sos })
    }

    @Test
    fun holdingWhileDrivingFiresSosAndShowsTheSosScreen() {
        val trip = startTrip()
        show()
        car.setGear(Gear.DRIVE)
        assertSwitchesTo("driving_screen")
        compose.mainClock.autoAdvance = false
        holdSos(1_600)
        compose.mainClock.autoAdvance = true
        compose.waitUntil(5_000) { sessions.active.value?.state?.sosActive == true }
        assertSwitchesTo("sos_screen")
        compose.onNodeWithText("SOS를 보냈어요", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("sos_guidance").assertIsDisplayed()
        compose.onNodeWithTag("sos_cancel").assertIsDisplayed()
        val sosEvents = events(trip.trip.id).filterIsInstance<TripEvent.Sos>()
        assertEquals(listOf(SosSource.ON_SCREEN), sosEvents.map { it.source })
    }

    @Test
    fun sosScreenCoversTheParkedTripAndCancelLogsSosCancelled() {
        val trip = startTrip()
        runBlocking { sos.fire(SosSource.TURN_SIGNAL).getOrThrow() }
        show()
        compose.onNodeWithTag("sos_screen").assertIsDisplayed()
        compose.onNodeWithTag("end_trip").assertDoesNotExist()

        compose.onNodeWithTag("sos_cancel").performClick()
        compose.waitUntil(5_000) { sessions.active.value?.state?.sosActive == false }
        assertSwitchesTo("trip_screen")
        val tail = events(trip.trip.id).takeLast(2)
        assertTrue(tail[0] is TripEvent.Sos && tail[1] is TripEvent.SosCancelled)
    }

    @Test
    fun sosScreenFitsConnectLPortrait() {
        startTrip()
        runBlocking { sos.fire(SosSource.ON_SCREEN).getOrThrow() }
        show(628.dp)
        for (tag in listOf("sos_guidance", "sos_cancel")) {
            val bounds = compose.onNodeWithTag(tag, useUnmergedTree = true).assertIsDisplayed().getUnclippedBoundsInRoot()
            assertTrue("$tag fits 628 dp", bounds.right <= 628.dp)
        }
    }

    /** A mic that opens and stays silent: the tests drive the check-in through the trip log, not through audio. */
    private val silentMic = object : MicSource {
        override fun permitted() = true
        override fun chunks(): Flow<ShortArray> = flow { awaitCancellation() }
    }

    private fun cueMonitor(): CueMonitor {
        val dir = File(ApplicationProvider.getApplicationContext<android.content.Context>().cacheDir, "cue-test")
        return CueMonitor(sessions, sos, silentMic, CueSwitch(null), ClipStore(dir, 16_000), Clock.System)
            .also { it.watch(scope) }
    }

    private fun prompt(level: Int = 1) = runBlocking {
        sessions.log(TripEvent.CheckInPrompted(level, CueKind.LOUD, "큰 소리가 10초 이상 이어짐")).getOrThrow()
    }

    @Test
    fun checkInCardShowsWhileDrivingAboveSosAndOkLogsTheAnswer() {
        val trip = startTrip()
        val cues = cueMonitor()
        prompt()
        show(628.dp, cues = cues)
        car.setGear(Gear.DRIVE)
        assertSwitchesTo("driving_screen")

        compose.onNodeWithText("괜찮으신가요?").assertIsDisplayed()
        val card = compose.onNodeWithTag("check_in_card").assertIsDisplayed().getUnclippedBoundsInRoot()
        val sosButton = compose.onNodeWithTag("sos_button").assertIsDisplayed().getUnclippedBoundsInRoot()
        assertTrue("card above SOS", card.bottom <= sosButton.top)
        assertTrue("card fits 628 dp", card.right <= 628.dp)
        compose.onNodeWithTag("check_in_countdown", useUnmergedTree = true).assertTextContains("초 후 기록", substring = true)
        compose.onNodeWithText("큰 목소리", useUnmergedTree = true).assertIsDisplayed()

        compose.onNodeWithTag("check_in_ok").performClick()
        compose.waitUntil(5_000) { sessions.active.value?.state?.checkInPending == false }
        compose.onNodeWithTag("check_in_card").assertDoesNotExist()
        assertEquals(TripEvent.CheckInAnswered(CheckInAnswer.OK, "기사가 괜찮다고 응답"), events(trip.trip.id).last())
    }

    @Test
    fun checkInSosIsANormalSos() {
        val trip = startTrip()
        val cues = cueMonitor()
        prompt()
        show(cues = cues)
        // D3b: the card has no SOS of its own; the bar is the SOS answer while the check-in is open.
        compose.mainClock.autoAdvance = false
        holdSos(1_600)
        compose.mainClock.autoAdvance = true
        compose.waitUntil(5_000) { sessions.active.value?.state?.sosActive == true }
        assertSwitchesTo("sos_screen")
        val tail = events(trip.trip.id).takeLast(2)
        assertEquals(TripEvent.CheckInAnswered(CheckInAnswer.SOS, "상태 확인에서 SOS 선택"), tail[0])
        assertTrue(tail[1] is TripEvent.Sos)
    }

    @Test
    fun checkInCardShowsOnTheSafeArrivalCheck() {
        startTripNearDestination(ArrivalInstructions())
        runBlocking {
            sessions.log(TripEvent.Arrived()).getOrThrow()
            sessions.log(TripEvent.SafeArrivalCheckOpened()).getOrThrow()
        }
        prompt()
        show(cues = cueMonitor())
        compose.onNodeWithTag("arrival_wait_screen").assertIsDisplayed()
        compose.onNodeWithTag("check_in_card").assertIsDisplayed()
    }

    @Test
    fun micIndicatorShowsOnTheTripScreensWhileListeningAndOneTapTurnsItOff() {
        val trip = startTrip()
        val cues = cueMonitor()
        show(628.dp, cues = cues)
        compose.onNodeWithTag("mic_indicator").assertDoesNotExist() // off by default

        cues.enable(trip.trip.id)
        assertSwitchesTo("mic_indicator")
        compose.onNodeWithTag("trip_screen").assertExists()
        car.setGear(Gear.DRIVE)
        assertSwitchesTo("driving_screen")
        compose.onNodeWithTag("mic_indicator").assertIsDisplayed()

        compose.onNodeWithTag("mic_indicator").performClick()
        compose.waitUntil(5_000) { !cues.listening.value }
        compose.onNodeWithTag("mic_indicator").assertDoesNotExist()

        // The passenger's PIN keypad keeps the pill: the mic still listens there (Checkpoint F review R2).
        cues.enable(trip.trip.id)
        assertSwitchesTo("mic_indicator")
        car.setGear(Gear.PARK)
        assertSwitchesTo("trip_screen")
        compose.onNodeWithTag("end_trip").performClick()
        compose.onNodeWithTag("pin_entry_screen").assertIsDisplayed()
        compose.onNodeWithTag("mic_indicator").assertIsDisplayed()
        compose.onNodeWithTag("pin_entry_close").performClick()

        // D3f D12: the SOS screen carries no pill, even while cues still listen.
        cues.enable(trip.trip.id)
        assertSwitchesTo("mic_indicator")
        runBlocking { sos.fire(SosSource.ON_SCREEN).getOrThrow() }
        assertSwitchesTo("sos_screen")
        compose.onNodeWithTag("mic_indicator").assertDoesNotExist()
        assertTrue(cues.listening.value)
    }

    @Test
    fun theSetupSwitchIsOffByDefault() {
        show()
        // 설정 first run up to step 4 (대화 감지).
        compose.onNodeWithTag("home_settings").performClick()
        compose.onNodeWithTag("settings_next").performClick() // 시작하기
        compose.onNodeWithTag("place_양재역").performScrollTo().performClick()
        compose.onNodeWithTag("settings_next").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("link_connection").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("settings_next").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("chip_지하 1층").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("settings_next").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("listen_switch").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("listen_switch").performScrollTo().assertIsOff()
        compose.onNodeWithTag("listen_switch").performClick().assertIsOn()
    }
}
