package com.coral.gwigaon.trip.ui

import androidx.compose.ui.test.assertHasClickAction
import kotlin.time.Instant
import com.coral.gwigaon.trip.PinAttempts
import com.coral.gwigaon.trip.TripStatus
import com.coral.gwigaon.trip.StoredTrip
import kotlinx.coroutines.flow.StateFlow
import android.Manifest
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.coral.gwigaon.sos.SosController
import com.coral.gwigaon.sync.OwnerLink
import com.coral.gwigaon.sync.OwnerLinkRepository
import com.coral.gwigaon.sync.OwnerLinkStore
import com.coral.gwigaon.trip.MonitoringConfig
import com.coral.gwigaon.trip.PinHasher
import com.coral.gwigaon.trip.SettingsDraft
import com.coral.gwigaon.trip.SettingsRepository
import com.coral.gwigaon.trip.StartSource
import com.coral.gwigaon.trip.TripEvent
import com.coral.gwigaon.trip.TripSessionRepository
import com.coral.gwigaon.trip.TripStarter
import com.coral.gwigaon.trip.db.GwigaonDatabase
import com.coral.gwigaon.trip.db.RoomSettingsStore
import com.coral.gwigaon.trip.db.RoomTripStore
import com.coral.gwigaon.ui.AppNavigation
import com.coral.gwigaon.ui.theme.GwigaonTheme
import com.coral.gwigaon.vehicle.Destination
import com.coral.gwigaon.vehicle.FakeVehicleSignals
import com.coral.gwigaon.vehicle.Gear
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.time.Clock

/**
 * Home, the driver's start screen and 설정 (D3c) over the real view models, Room (in memory), the trip starter and the
 * fake car, inside [AppNavigation] so driving gating applies. Replaces the old setup screen's tests: the same rules
 * (nothing starts while incomplete, PIN typed twice, the trip and navigation get the destination, driving hides the
 * form and keeps the draft, portrait windows fit) now hold for 설정 and the start screen.
 */
@RunWith(AndroidJUnit4::class)
class HomeScreensTest {
    @get:Rule
    val compose = createComposeRule()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var db: GwigaonDatabase
    private lateinit var car: FakeVehicleSignals
    private lateinit var sessions: TripSessionRepository
    private lateinit var settings: SettingsRepository
    private val gearStart = MutableStateFlow(true)
    private val yangjae = Destination("양재역", 37.48460, 127.03420)

    /** The account link without a cloud: a QR to show, tokens counted up so a replace is visible. */
    private var tokens = 0
    private val links = OwnerLinkRepository(
        object : OwnerLinkStore {
            private var kept: OwnerLink? = null
            override suspend fun get() = kept
            override suspend fun put(link: OwnerLink) { kept = link }
        },
        cloud = null,
        clock = Clock.System,
        newToken = { "testLinkToken_${tokens++}_0123456789abcdef" },
    )

    @Before
    fun setUp() {
        // 운행 시작 asks for these first; granted here so no system dialog covers the test.
        val ui = InstrumentationRegistry.getInstrumentation().uiAutomation
        val pkg = InstrumentationRegistry.getInstrumentation().targetContext.packageName
        listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.RECORD_AUDIO)
            .forEach { ui.grantRuntimePermission(pkg, it) }
        db = GwigaonDatabase.inMemory(ApplicationProvider.getApplicationContext())
        car = FakeVehicleSignals(scope, initialGear = Gear.PARK)
        sessions = TripSessionRepository(RoomTripStore(db), PinHasher(iterations = 1_000), Clock.System)
        settings = SettingsRepository(RoomSettingsStore(db), PinHasher(iterations = 1_000), Clock.System) { sessions.active.value != null }
        runBlocking { settings.load().getOrThrow() }
    }

    @After
    fun tearDown() {
        db.close()
        scope.cancel()
    }

    /** Shows the app's no-trip screens; [width] narrows it to a portrait or half window. */
    private fun show(width: Dp? = null, latestEnded: StateFlow<StoredTrip?> = MutableStateFlow(null), openReport: () -> Unit = {}) {
        val starter = TripStarter(sessions, settings, car::setDestination, {})
        val home = HomeViewModel(settings.settings, sessions.active, { starter.start(StartSource.BUTTON) }, gearStart, latestEnded, openReport)
        val settingsVm = SettingsViewModel(settings, car.drivingState, sessions.active, sessions::recentDestinations)
        val tripVm = TripViewModel(sessions, car.drivingState, Clock.System, SosController(sessions, Clock.System, MonitoringConfig.DEFAULT))
        val linkVm = LinkViewModel(links, "https://example.invalid/contact/")
        compose.setContent {
            GwigaonTheme {
                val active by sessions.active.collectAsStateWithLifecycle()
                val driving by car.drivingState.collectAsStateWithLifecycle()
                val unavailable by car.unavailable.collectAsStateWithLifecycle()
                Box(if (width != null) Modifier.width(width) else Modifier) {
                    AppNavigation(active, driving, unavailable, tripVm) { NoTripScreens(home, settingsVm, linkVm, onLocationGranted = {}) }
                }
            }
        }
    }

    private fun saveReady(pin: String? = null) = runBlocking {
        val draft = SettingsDraft(home = yangjae, linkShown = true)
        settings.save(if (pin == null) draft else draft.copy(pinOn = true, pin = pin, pinConfirm = pin)).getOrThrow()
    }

    private fun tag(t: String) = compose.onNodeWithTag(t)
    private fun next() = tag("settings_next").performClick()
    private fun waitFor(t: String) = compose.waitUntil(5_000) { compose.onAllNodesWithTag(t).fetchSemanticsNodes().isNotEmpty() }
    private fun field(t: String): SemanticsNodeInteraction = compose.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag(t)))
    private fun keys(digits: String, pad: String = "pin_keypad") = digits.forEach {
        val key = compose.onNode(hasTestTag("key_$it") and hasAnyAncestor(hasTestTag(pad)))
        runCatching { key.performScrollTo() } // the narrow keypad sits in the scrolling step; the side one doesn't scroll
        key.performClick()
    }

    // Home: the start destination when no trip runs

    @Test
    fun firstUseOffersSettingsAndTheStartDoorExplainsInsteadOfStarting() {
        show()
        compose.onNodeWithText("환영합니다").assertIsDisplayed()
        tag("home_settings").assertTextContains("처음 설정하기")
        tag("start").assertTextContains("설정이 아직 안됐어요").performClick()
        tag("not_set_up").assertIsDisplayed()
        tag("start_trip").assertDoesNotExist()
        assertNull(sessions.active.value)

        tag("open_settings").performClick()
        compose.onNodeWithText("귀가온 설정을 시작합니다").assertIsDisplayed()
    }

    @Test
    fun historyShowsOnlyOnceATripHasEndedAndOpensItsReport() {
        saveReady()
        val latest = MutableStateFlow<StoredTrip?>(null)
        var opened = 0
        show(width = 840.dp, latestEnded = latest) { opened++ }
        tag("home_history").assertDoesNotExist()

        latest.value = StoredTrip(
            "t1", TripStatus.ENDED, yangjae, "", "", PinAttempts(),
            Instant.parse("2026-11-14T12:00:00Z"), Instant.parse("2026-11-14T12:40:00Z"), Instant.parse("2026-11-14T12:40:00Z"),
        )
        waitFor("home_history")
        tag("home_history").assertTextContains("운행 기록").assertHasClickAction().performClick()
        compose.runOnIdle { assertEquals(1, opened) }
    }

    @Test
    fun halfDoneSettingsAreContinuedWhereTheyStopped() {
        runBlocking { settings.save(SettingsDraft(home = yangjae)).getOrThrow() }
        show()
        compose.onNodeWithText("모니터링 QR를 아직 공유하지 않았어요.").assertIsDisplayed()
        tag("home_settings").assertTextContains("설정 이어서 하기").performClick()
        tag("step_count").assertTextContains("2 / 5 · 모니터링 QR 공유")
    }

    @Test
    fun readyHomeSummarisesAndOpensTheStartScreen() {
        saveReady()
        show(width = 840.dp) // the half panel; full width swaps the gear hint for the split guide
        tag("home_settings").assertTextContains("양재역 · PIN 꺼짐")
        tag("start").assertTextContains("목적지: 양재역").performClick()
        tag("destination").assertTextContains("양재역", substring = true)
        tag("brief_end_auto").assertIsDisplayed()
        tag("brief_sos").assertExists()
        tag("start_trip").assertTextContains("기어를 D로 바꾸면 자동으로 시작해요")

        tag("start_back").performClick()
        tag("home_screen").assertIsDisplayed()
    }

    @Test
    fun theBriefingFollowsSettings() {
        runBlocking {
            settings.save(SettingsDraft(home = yangjae, linkShown = true, cues = true, pinOn = true, pin = "2580", pinConfirm = "2580")).getOrThrow()
        }
        show()
        tag("start").performClick()
        tag("brief_cues").assertExists()
        tag("brief_end_pin").assertExists()
        tag("brief_end_auto").assertDoesNotExist()
    }

    // 설정 first run → home → 운행 시작 → trip (D3c acceptance)

    @Test
    fun firstRunThenStartRunsATripToHomeAndSetsTheNavigation() {
        show()
        tag("home_settings").performClick()
        next() // 시작하기
        tag("settings_next").assertIsNotEnabled() // no place yet
        waitFor("place_양재역")
        tag("place_양재역").performScrollTo().performClick()
        next()
        waitFor("link_qr") // 비상 연락처: the account QR, nothing to fill in
        tag("settings_next").assertIsEnabled()
        next()
        compose.waitUntil(5_000) { settings.settings.value.linkShown }
        waitFor("chip_지하 2층")
        tag("chip_지하 2층").performScrollTo().performClick()
        tag("chip_승객에게 직접").performScrollTo().performClick()
        next()
        waitFor("listen_switch")
        next() // 대화 감지 stays off
        waitFor("pin_switch")
        next() // PIN stays off
        waitFor("change_home")
        compose.onNodeWithText("설정을 마쳤어요").assertIsDisplayed()
        next() // 기사님께 넘기기

        tag("start").assertTextContains("목적지: 양재역").performClick()
        tag("start_trip").performClick()
        compose.waitUntil(5_000) { sessions.active.value != null }
        val trip = sessions.active.value!!.trip
        assertEquals("양재역", trip.destination.name)
        assertEquals("지하 2층", trip.arrival.parking)
        assertEquals("승객에게 직접", trip.arrival.keyNote)
        compose.waitUntil(5_000) { car.destinationsSet.isNotEmpty() }
        assertEquals("양재역", car.destinationsSet.single().name)
        val started = runBlocking { RoomTripStore(db).events(trip.id).getOrThrow().map { it.event } }
        assertTrue(started.first() is TripEvent.TripStarted)
        tag("trip_screen").assertExists()
    }

    @Test
    fun qrStepShowsTheAccountQrAndReplacesItOnlyAfterTheConfirm() {
        show()
        tag("home_settings").performClick()
        next()
        waitFor("place_양재역")
        tag("place_양재역").performScrollTo().performClick()
        next()
        waitFor("link_qr")
        val first = runBlocking { links.ensure().token }
        tag("link_replace").performClick()
        tag("link_replace_cancel").performClick()
        tag("link_replace").assertExists()
        assertEquals(first, links.link.value?.token)
        tag("link_replace").performClick()
        tag("link_replace_confirm").performClick()
        compose.waitUntil(5_000) { links.link.value?.token != first }
        tag("link_qr").assertExists()
        tag("settings_next").assertIsEnabled()
    }

    @Test
    fun pinMismatchStartsOverAndAMatchingPinCanBeSaved() {
        runBlocking { settings.save(SettingsDraft(home = yangjae, linkShown = true)).getOrThrow() }
        show(width = 840.dp) // keypad under the step
        tag("home_settings").performClick()
        tag("change_pin").performScrollTo().performClick()
        tag("pin_switch").performClick()
        tag("settings_next").assertIsNotEnabled()
        keys("2580")
        tag("pin_message").assertTextContains("한 번 더 입력해서 확인해요")
        keys("2581")
        tag("pin_message").assertTextContains("처음 입력한 PIN과 달라요. 처음부터 다시 정해 주세요.")
        tag("settings_next").assertIsNotEnabled()
        keys("2580")
        keys("2580")
        tag("pin_message").assertTextContains("PIN을 정했어요")
        tag("settings_next").assertIsEnabled().performClick()
        compose.waitUntil(5_000) { settings.settings.value.pinOn }
        tag("change_pin").assertExists() // back on the summary
    }

    // PIN gate and blocked states

    @Test
    fun withThePinOnSettingsOpenOnlyAfterThePin() {
        saveReady(pin = "2580")
        show(width = 840.dp)
        tag("home_settings").performClick()
        tag("gate_message").assertTextContains("5번 틀리면 30초 동안 잠겨요")
        tag("change_home").assertDoesNotExist()
        keys("1111", pad = "gate_keypad")
        compose.waitUntil(5_000) { runCatching { tag("gate_message").assertTextContains("4번 남았어요", substring = true) }.isSuccess }
        keys("2580", pad = "gate_keypad")
        waitFor("change_home")
    }

    @Test
    fun fullWidthPutsThePinDotsOnTheRightPanel() {
        // D3f H14: gate and new-PIN dots sit above the keypad on the right panel at full width.
        saveReady(pin = "2580")
        show()
        tag("home_settings").performClick()
        compose.onNode(hasTestTag("gate_message") and hasAnyAncestor(hasTestTag("settings_side"))).assertIsDisplayed()
        keys("2580", pad = "gate_keypad")
        waitFor("change_pin")
        tag("change_pin").performClick()
        compose.onNode(hasTestTag("pin_message") and hasAnyAncestor(hasTestTag("settings_side"))).assertIsDisplayed()
    }

    @Test
    fun fullWidthStartGoesAloneWithoutSubtextAndExplainsTheSplit() {
        saveReady()
        show()
        tag("start").performClick()
        tag("start_trip").assertTextContains("바로 출발할게요")
        compose.onNodeWithText("내비게이션이 이 화면을 덮어요", substring = true).assertDoesNotExist()
        tag("split_hint").assertIsDisplayed()
        tag("split_diagram").assertIsDisplayed()
    }

    @Test
    fun homeQrOpensOnlyTheQrStepAndClosesBackHome() {
        saveReady()
        show()
        tag("home_qr").assertTextContains("QR 확인하기").performClick()
        waitFor("link_qr")
        tag("settings_next").assertTextContains("확인").performClick()
        waitFor("home_screen")
        tag("settings_back").assertDoesNotExist()
    }

    @Test
    fun withThePinOnTheHomeQrNeedsThePinToo() {
        saveReady(pin = "2580")
        show(width = 840.dp)
        tag("home_qr").performClick()
        tag("gate_message").assertExists()
        tag("link_qr").assertDoesNotExist()
        keys("2580", pad = "gate_keypad")
        waitFor("link_qr")
    }

    @Test
    fun gearStartIsOffOnlyWhileSettingsAreOpen() {
        saveReady()
        show()
        assertTrue(gearStart.value)
        tag("home_settings").performClick()
        compose.waitForIdle()
        assertFalse(gearStart.value)
        tag("settings_back").performClick()
        compose.waitForIdle()
        assertTrue(gearStart.value)
        tag("start").performClick()
        compose.waitForIdle()
        assertTrue(gearStart.value)
    }

    @Test
    fun drivingHidesSettingsAndKeepsTheDraft() {
        show()
        tag("home_settings").performClick()
        next()
        waitFor("place_양재역")
        tag("place_양재역").performScrollTo().performClick()
        car.setGear(Gear.DRIVE)
        compose.waitUntil(1_000) { compose.onAllNodesWithTag("driving_screen").fetchSemanticsNodes().isNotEmpty() }
        tag("settings_screen").assertDoesNotExist()

        car.setGear(Gear.PARK)
        waitFor("settings_screen")
        tag("step_count").assertTextContains("1 / 5 · 주소")
        tag("settings_next").assertIsEnabled() // 양재역 still picked
    }

    // Portrait app windows: Connect-S 840 dp, Connect-L 628 dp (research/findings/pleos-figma-kit.md).
    @Test
    fun connectSPortraitFits() = assertFits(840.dp)

    @Test
    fun connectLPortraitFits() = assertFits(628.dp)

    private fun assertFits(width: Dp) {
        saveReady()
        show(width)
        val settingsDoor = tag("home_settings").performScrollTo().getUnclippedBoundsInRoot()
        val startDoor = tag("start").performScrollTo().getUnclippedBoundsInRoot()
        assertTrue("doors stack at $width", startDoor.top >= settingsDoor.bottom)
        assertTrue("start door fits $width", startDoor.right <= width)
        tag("start").performClick()
        val go = tag("start_trip").getUnclippedBoundsInRoot()
        assertTrue("start button fits $width", go.right <= width)
        tag("start_trip").assertIsDisplayed()
    }
}
