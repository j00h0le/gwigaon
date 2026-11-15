package com.coral.gwigaon.report.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.coral.gwigaon.report.ReportRepository
import com.coral.gwigaon.report.ReportViewModel
import com.coral.gwigaon.report.SummaryCloud
import com.coral.gwigaon.sync.AuthApi
import com.coral.gwigaon.sync.CloudError
import com.coral.gwigaon.sync.CloudSession
import com.coral.gwigaon.sync.NotableEvent
import com.coral.gwigaon.sync.SessionManager
import com.coral.gwigaon.sync.SessionStore
import com.coral.gwigaon.sync.SummaryApi
import com.coral.gwigaon.sync.TripSummary
import com.coral.gwigaon.trip.EndReason
import com.coral.gwigaon.trip.EventRecord
import com.coral.gwigaon.trip.PinAttempts
import com.coral.gwigaon.trip.StoredTrip
import com.coral.gwigaon.trip.TripEvent
import com.coral.gwigaon.trip.TripStatus
import com.coral.gwigaon.ui.theme.GwigaonTheme
import com.coral.gwigaon.vehicle.Destination
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.ZoneId
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** The journey report with a real ViewModel and a scripted cloud (T19 acceptance; D3e layout, ADR 0017). */
@RunWith(AndroidJUnit4::class)
class ReportScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val t0 = Instant.parse("2026-11-11T12:03:00Z")
    private val trip = StoredTrip(
        "11111111-1111-4111-8111-111111111111", TripStatus.ENDED, Destination("양재역", 37.484, 127.034), "언니",
        "hash", PinAttempts(), t0, t0 + 38.minutes, t0 + 38.minutes,
    )
    private val events = listOf(
        EventRecord(1, t0, null, null, TripEvent.TripStarted()),
        EventRecord(2, t0 + 5.minutes, null, null, TripEvent.RouteDeviation(420, "안내 경로에서 420m 벗어났어요")),
        EventRecord(3, t0 + 38.minutes, null, null, TripEvent.TripEnded(EndReason.SAFE_ARRIVAL)),
        // Not on the short 운행 로그 (ADR 0017).
        EventRecord(4, t0 + 35.minutes, null, null, TripEvent.NearDestination()),
    )
    private val summary = TripSummary(
        "한 번 경로를 벗어났지만 안전하게 도착했어요.", listOf(1, 2, 3), listOf(NotableEvent(2, "경로를 420m 벗어났어요.")),
        listOf("정차 이유는 기록에 없어요."), "claude-sonnet-5-5",
    )

    /** Fails until [failing] is cleared; counts calls. */
    private class Api(var failing: Boolean, val summary: TripSummary) : SummaryApi {
        var calls = 0

        override suspend fun summarizeTrip(accessToken: String, tripId: String): TripSummary {
            calls++
            if (failing) throw CloudError.Http(502, "model_unavailable")
            return summary
        }
    }

    private val auth = object : AuthApi {
        override suspend fun signUpAnonymously() = CloudSession("a", "r", Clock.System.now() + 1.hours)
        override suspend fun refresh(refreshToken: String) = signUpAnonymously()
    }
    private val sessionStore = object : SessionStore {
        override suspend fun load(): CloudSession? = null
        override suspend fun save(session: CloudSession?) = Unit
    }

    @After
    fun tearDown() = scope.cancel()

    private fun show(api: Api?, width: Dp? = null, height: Dp? = null, onClose: () -> Unit = {}): ReportViewModel {
        val repo = ReportRepository(
            { Result.success(events) },
            api?.let { SummaryCloud(SessionManager(auth, sessionStore, Clock.System), it) },
        )
        val vm = ReportViewModel(trip, repo, ZoneId.of("Asia/Seoul"), onClose, scope)
        compose.setContent {
            GwigaonTheme {
                // required*: the test window may be the half panel, narrower than the width under test.
                Box(Modifier.then(if (width != null) Modifier.requiredWidth(width) else Modifier).then(if (height != null) Modifier.requiredHeight(height) else Modifier)) {
                    ReportRoute(vm)
                }
            }
        }
        return vm
    }

    private fun waitForTag(tag: String) =
        compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty() }

    @Test
    fun aSummaryFailureShowsTheTimelineAndARetry() {
        val api = Api(failing = true, summary)
        show(api)
        waitForTag("ai_failed")

        compose.onNodeWithTag("timeline_1").assertIsDisplayed()
        compose.onNodeWithTag("timeline_2").assertIsDisplayed()
        compose.onNode(hasText("안내 경로에서 420m 벗어났어요") and hasAnyAncestor(hasTestTag("report_facts"))).assertIsDisplayed()
        compose.onNodeWithTag("ai_retry").assertIsDisplayed()

        api.failing = false
        compose.onNodeWithTag("ai_retry").performClick()
        waitForTag("ai_summary")
        compose.onNodeWithTag("ai_retry").assertDoesNotExist()
        compose.onNodeWithTag("timeline_1").assertIsDisplayed()
        assertEquals(2, api.calls)
    }

    @Test
    fun logOnTheLeftAndReportOnTheRightAtFullWidth() {
        // Connect-S app area; required width, so a half-panel test window shows only part of it (bounds still hold).
        show(Api(failing = false, summary), 1682.dp)
        assertSeparatePanels(onScreen = false)
        val facts = compose.onNodeWithTag("report_facts").getUnclippedBoundsInRoot()
        val ai = compose.onNodeWithTag("report_ai").getUnclippedBoundsInRoot()
        assertTrue("log ${facts.right} must end before the report ${ai.left}", facts.right <= ai.left)
    }

    @Test
    fun logComesBeforeTheReportInTheNarrowPortraitWindow() {
        show(Api(failing = false, summary), 628.dp)
        assertSeparatePanels(onScreen = false)
        val facts = compose.onNodeWithTag("report_facts").getUnclippedBoundsInRoot()
        val ai = compose.onNodeWithTag("report_ai").getUnclippedBoundsInRoot()
        assertTrue("log above the report", facts.bottom <= ai.top)
        assertTrue("fits 628 dp", ai.right <= 628.dp && facts.right <= 628.dp)
    }

    private fun assertSeparatePanels(onScreen: Boolean = true) {
        waitForTag("ai_summary")
        fun SemanticsNodeInteraction.shown() = if (onScreen) assertIsDisplayed() else assertExists()

        compose.onNode(hasText("운행 로그") and hasAnyAncestor(hasTestTag("report_facts"))).shown()
        compose.onNode(hasText("귀가온 리포트") and hasAnyAncestor(hasTestTag("report_ai"))).shown()
        // Still marked as the AI's reading, quietly beside the heading, without the old tag or outline caveat.
        compose.onNode(hasText("AI가 쓴 요약") and hasAnyAncestor(hasTestTag("report_ai"))).shown()
        compose.onNodeWithText("귀가온 AI").assertDoesNotExist()
        compose.onNodeWithTag("report_status").assertTextContains("안전하게 귀가했어요")
        // The AI text lives only in the AI panel, never among the facts.
        compose.onNode(hasText(summary.summary) and hasAnyAncestor(hasTestTag("report_ai"))).shown()
        compose.onNode(hasText(summary.summary) and hasAnyAncestor(hasTestTag("report_facts"))).assertDoesNotExist()
        // Only the summary: a stored report's per-event lines, time chips and caveats are not drawn.
        compose.onNodeWithText("경로를 420m 벗어났어요.").assertDoesNotExist()
        compose.onNodeWithText("정차 이유는 기록에 없어요.").assertDoesNotExist()
        compose.onNode(hasText("21:08") and hasAnyAncestor(hasTestTag("report_ai"))).assertDoesNotExist()
    }

    @Test
    fun withoutACloudTheReportIsFactsOnlyAndCloses() {
        var closed = false
        show(null, 840.dp) { closed = true }
        waitForTag("ai_off")
        compose.onNodeWithTag("ai_retry").assertDoesNotExist()
        compose.onNodeWithTag("timeline_3").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("timeline_4").assertDoesNotExist()
        // No cloud, so nothing is shared with a 비상 연락처.
        compose.onNodeWithText("비상 연락처도 같은 기록을 볼 수 있어요").assertDoesNotExist()

        compose.onNodeWithTag("report_close").performClick()
        compose.waitUntil(1_000) { closed }
    }
}
