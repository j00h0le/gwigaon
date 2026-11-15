package com.coral.gwigaon.arrival

import com.coral.gwigaon.route.offset
import com.coral.gwigaon.trip.ActiveTrip
import com.coral.gwigaon.trip.ArrivalInstructions
import com.coral.gwigaon.trip.FakeTripStore
import com.coral.gwigaon.trip.MonitoringConfig
import com.coral.gwigaon.trip.PinHasher
import com.coral.gwigaon.trip.SafeArrivalCloser
import com.coral.gwigaon.trip.TestClock
import com.coral.gwigaon.trip.TripEvent
import com.coral.gwigaon.trip.TripSessionRepository
import com.coral.gwigaon.trip.TripSetup
import com.coral.gwigaon.trip.TripState
import com.coral.gwigaon.trip.TripStatus
import com.coral.gwigaon.vehicle.Destination
import com.coral.gwigaon.vehicle.GeoFix
import com.coral.gwigaon.vehicle.NaviEvent
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class ArrivalAnnouncerTest {
    private val store = FakeTripStore()
    private val clock = TestClock()
    private var ids = 0
    private val sessions = TripSessionRepository(store, PinHasher(iterations = 1_000), clock) { "trip-${++ids}" }
    private val spoken = mutableListOf<String>()
    private val logged = mutableListOf<Pair<String, Throwable?>>()
    private var speaker = Speaker { spoken += it }
    private val announcer by lazy {
        ArrivalAnnouncer(sessions, MonitoringConfig.DEFAULT, { speaker.speak(it) }) { m, e -> logged += m to e }
    }
    private val instructions = ArrivalInstructions("지하 2층", "101동 정문", "키는 경비실에")
    private val setup = TripSetup(Destination("양재역", 37.48460, 127.03420), "언니", "2580", instructions)

    private fun nearEvents(trip: String = "trip-1") =
        store.events[trip].orEmpty().map { it.event }.filterIsInstance<TripEvent.NearDestination>()

    private suspend fun fixAt(northM: Double, accuracy: Float? = 5f) {
        val p = offset(northM, 0.0)
        announcer.onFix(GeoFix(p.lat, p.lng, 0, accuracyM = accuracy))
    }

    @Test
    fun nearDestinationFiresOncePerTripAndSpeaksOnce() = runTest {
        sessions.start(setup).getOrThrow()
        fixAt(900.0)
        fixAt(600.0)
        assertTrue(nearEvents().isEmpty())

        fixAt(480.0)
        fixAt(300.0)
        fixAt(150.0)
        fixAt(520.0) // back out and in again: still once
        fixAt(400.0)

        assertEquals(listOf(TripEvent.NearDestination("목적지 500m 이내")), nearEvents())
        assertEquals(TripStatus.ARRIVING, sessions.active.value!!.state.status)
        assertEquals(listOf("목적지 근처입니다. 출입문은 101동 정문, 주차는 지하 2층, 차 키는 경비실에 부탁드립니다. 감사합니다."), spoken)
        assertTrue(logged.single().first.startsWith("NEAR_DESTINATION seq=2 at "))
    }

    @Test
    fun justInsideTheThresholdCounts() = runTest {
        sessions.start(setup).getOrThrow()
        fixAt(499.0)
        assertEquals(1, nearEvents().size)
    }

    @Test
    fun inaccurateFixesAreIgnoredButFixesWithoutAccuracyCount() = runTest {
        sessions.start(setup).getOrThrow()
        fixAt(200.0, accuracy = 150f)
        assertTrue(nearEvents().isEmpty())
        fixAt(200.0, accuracy = null)
        assertEquals(1, nearEvents().size)
    }

    @Test
    fun nothingWithoutAnActiveTrip() = runTest {
        fixAt(10.0)
        assertTrue(spoken.isEmpty())
        assertTrue(store.events.isEmpty())
    }

    @Test
    fun theNextTripIsAnnouncedAgain() = runTest {
        sessions.start(setup).getOrThrow()
        fixAt(200.0)
        sessions.endByPassenger("2580").getOrThrow()
        sessions.start(setup.copy(arrival = ArrivalInstructions())).getOrThrow()
        fixAt(200.0)

        assertEquals(1, nearEvents("trip-1").size)
        assertEquals(1, nearEvents("trip-2").size)
        assertEquals("목적지 근처입니다.", spoken.last())
    }

    @Test
    fun aFailingSpeakerStillLeavesTheEventAndTheCard() = runTest {
        speaker = Speaker { error("engine gone") }
        sessions.start(setup).getOrThrow()
        fixAt(200.0)

        assertEquals(1, nearEvents().size)
        assertEquals(3, ArrivalTexts.card(sessions.active.value).size)
        assertEquals("arrival instructions not spoken", logged.last().first)
        assertTrue(logged.last().second is IllegalStateException)
    }

    @Test
    fun aFailedLogIsRetriedOnTheNextFixAndSpeaksOnlyThen() = runTest {
        sessions.start(setup).getOrThrow()
        store.failAppend = true
        fixAt(200.0)
        assertTrue(spoken.isEmpty())
        assertEquals("NEAR_DESTINATION not stored", logged.last().first)

        store.failAppend = false
        fixAt(90.0)
        assertEquals(1, nearEvents().size)
        assertEquals(1, spoken.size)
    }

    @Test
    fun watchFollowsTheLocationFlow() = runTest {
        sessions.start(setup).getOrThrow()
        val location = MutableSharedFlow<GeoFix>()
        val navi = MutableSharedFlow<NaviEvent>()
        announcer.watch(location, navi, backgroundScope)
        runCurrent()
        val p = offset(200.0, 0.0)
        location.emit(GeoFix(p.lat, p.lng, 0, accuracyM = 5f))
        runCurrent()
        assertEquals(1, nearEvents().size)
        navi.emit(NaviEvent.Arrived)
        runCurrent()
        assertTrue(sessions.active.value!!.state.arrived)
    }

    private fun types(trip: String = "trip-1") = store.events[trip].orEmpty().map { it.event.type.wire }

    @Test
    fun arrivedOnceWithin100mAfterNearDestination() = runTest {
        sessions.start(setup).getOrThrow()
        fixAt(300.0)
        fixAt(120.0)
        assertEquals(listOf("TRIP_STARTED", "NEAR_DESTINATION"), types())
        fixAt(90.0)
        fixAt(20.0)
        fixAt(300.0)
        fixAt(10.0)
        assertEquals(listOf("TRIP_STARTED", "NEAR_DESTINATION", "ARRIVED"), types())
        assertEquals(TripEvent.Arrived("목적지 도착"), store.events.getValue("trip-1").last().event)
        assertTrue(logged.last().first.startsWith("ARRIVED seq=3 by "))
    }

    @Test
    fun aFirstFixAlreadyAtTheDoorAnnouncesThenArrives() = runTest {
        sessions.start(setup).getOrThrow()
        fixAt(30.0)
        assertEquals(listOf("TRIP_STARTED", "NEAR_DESTINATION", "ARRIVED"), types())
        assertEquals(1, spoken.size)
    }

    @Test
    fun navigationArrivalWithinTheDestinationAreaArrives() = runTest {
        sessions.start(setup).getOrThrow()
        fixAt(450.0)
        announcer.onNaviArrived()
        announcer.onNaviArrived()
        assertEquals(listOf("TRIP_STARTED", "NEAR_DESTINATION", "ARRIVED"), types())
        assertEquals(1, spoken.size)
        assertTrue(logged.any { it.first.endsWith("by navigation") })
    }

    @Test
    fun navigationArrivalFarFromTheDestinationIsIgnored() = runTest {
        sessions.start(setup).getOrThrow()
        announcer.onNaviArrived() // no fix yet
        fixAt(3_000.0) // e.g. the driver routed the navigation to a gas station
        announcer.onNaviArrived()
        assertEquals(listOf("TRIP_STARTED"), types())
        assertTrue(spoken.isEmpty())
        assertEquals(
            listOf("navigation arrival ignored: no fix yet", "navigation arrival ignored: 3000 m from the destination"),
            logged.map { it.first },
        )
    }

    @Test
    fun aFixFromThePreviousTripDoesNotCountForNavigationArrival() = runTest {
        sessions.start(setup).getOrThrow()
        fixAt(20.0)
        sessions.endByPassenger("2580").getOrThrow()
        sessions.start(setup.copy(destination = Destination("강남역", 37.4979, 127.0276))).getOrThrow()
        announcer.onNaviArrived()
        assertEquals(listOf("TRIP_STARTED"), types("trip-2"))
    }

    @Test
    fun aFailedArrivedLogIsReportedAndRetried() = runTest {
        sessions.start(setup).getOrThrow()
        fixAt(300.0)
        store.failAppend = true
        fixAt(50.0)
        assertEquals("ARRIVED not stored", logged.last().first)
        store.failAppend = false
        fixAt(40.0)
        assertTrue(sessions.active.value!!.state.arrived)
    }
}

class ArrivalTextsTest {
    private val trip = { status: TripStatus, arrival: ArrivalInstructions ->
        ActiveTrip(
            com.coral.gwigaon.trip.StoredTrip(
                id = "t", status = status, destination = Destination("집", 37.5, 127.0), contactLabel = "언니",
                pinHash = "h", pinAttempts = com.coral.gwigaon.trip.PinAttempts(),
                startedAt = TestClock().now, endedAt = null, lastSeenAt = TestClock().now, arrival = arrival,
            ),
            TripState(status),
        )
    }

    @Test
    fun cardLinesSkipBlanksTrimAndLabelOnlyWhereNeeded() {
        assertEquals(
            listOf("주차 · 지하 2층", "키 · 우편함에"),
            ArrivalTexts.cardLines(ArrivalInstructions(" 지하 2층 ", "  ", "우편함에")),
        )
        assertEquals(listOf("출입 · 정문", "키는 운전석에"), ArrivalTexts.cardLines(ArrivalInstructions("", "정문", "키는 운전석에")))
        assertTrue(ArrivalTexts.cardLines(ArrivalInstructions()).isEmpty())
    }

    @Test
    fun cardShowsOnlyWhileArrivingWithInstructions() {
        val given = ArrivalInstructions("지하 1층")
        assertEquals(listOf("주차 · 지하 1층"), ArrivalTexts.card(trip(TripStatus.ARRIVING, given)))
        assertTrue(ArrivalTexts.card(trip(TripStatus.ACTIVE, given)).isEmpty())
        assertTrue(ArrivalTexts.card(trip(TripStatus.SAFE_ARRIVAL_CHECK, given)).isEmpty())
        assertTrue(ArrivalTexts.card(trip(TripStatus.ARRIVING, ArrivalInstructions())).isEmpty())
        assertTrue(ArrivalTexts.card(null).isEmpty())
    }

    @Test
    fun spokenSentenceAndReason() {
        assertEquals("목적지 근처입니다. 출입문은 정문 부탁드립니다. 감사합니다.", ArrivalTexts.spoken(ArrivalInstructions(entrance = "정문")))
        assertEquals("목적지 500m 이내", ArrivalTexts.nearDestination(500.0))
    }

    @Test
    fun safeArrivalTexts() {
        assertEquals("도착 후 30초째 승객이 하차하지 않음", ArrivalTexts.checkOpened(30.seconds))
        assertEquals("도착 후 3분째 승객이 하차하지 않음", ArrivalTexts.checkOpened(3.minutes + 4.seconds))
        assertEquals("승객 하차 확인", ArrivalTexts.confirmed(SafeArrivalCloser.SEAT_EMPTY))
        assertEquals("승객이 PIN으로 상태 확인", ArrivalTexts.confirmed(SafeArrivalCloser.PASSENGER_PIN))
        assertEquals("기사가 승객 반응 확인", ArrivalTexts.confirmed(SafeArrivalCloser.DRIVER_CONFIRMED))
    }
}
