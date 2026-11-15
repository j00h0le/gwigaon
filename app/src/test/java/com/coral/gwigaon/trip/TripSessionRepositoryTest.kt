package com.coral.gwigaon.trip

import com.coral.gwigaon.vehicle.Destination
import com.coral.gwigaon.vehicle.GeoFix
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** In-memory [TripStore] that survives "process restarts" (new repositories over the same instance). */
class FakeTripStore : TripStore {
    val trips = linkedMapOf<String, StoredTrip>()
    val events = mutableMapOf<String, MutableList<EventRecord>>()
    var failAppend = false
    var corruptEvents = false

    override suspend fun insert(trip: StoredTrip) {
        check(trip.id !in trips)
        trips[trip.id] = trip
    }

    override suspend fun active() = trips.values.lastOrNull { it.status != TripStatus.ENDED }

    override suspend fun latestEnded() = trips.values.filter { it.status == TripStatus.ENDED }.maxByOrNull { it.startedAt }

    override suspend fun events(tripId: String) =
        if (corruptEvents) Result.failure(IllegalArgumentException("bad row")) else Result.success(events[tripId].orEmpty().toList())

    override suspend fun append(tripId: String, at: Instant, lat: Double?, lng: Double?, event: TripEvent): EventRecord {
        check(!failAppend) { "disk full" }
        val list = events.getOrPut(tripId) { mutableListOf() }
        return EventRecord(list.size + 1, at, lat, lng, event).also(list::add)
    }

    override suspend fun setStatus(tripId: String, status: TripStatus, endedAt: Instant?) {
        trips[tripId] = trips.getValue(tripId).copy(status = status, endedAt = endedAt)
    }

    override suspend fun touch(tripId: String, at: Instant) {
        trips[tripId] = trips.getValue(tripId).copy(lastSeenAt = at)
    }

    override suspend fun setPinAttempts(tripId: String, attempts: PinAttempts) {
        trips[tripId] = trips.getValue(tripId).copy(pinAttempts = attempts)
    }

    override suspend fun recentDestinations(limit: Int) =
        trips.values.sortedByDescending { it.startedAt }.map { it.destination }.distinct().take(limit)
}

class TripSessionRepositoryTest {
    private val store = FakeTripStore()
    private val clock = TestClock()
    private val hasher = PinHasher(iterations = 1_000)
    private var ids = 0
    private fun repo() = TripSessionRepository(store, hasher, clock) { "trip-${++ids}" }
    private val setup = TripSetup(Destination("집", 37.5, 127.0), " 언니 ", "2580")

    private fun types(tripId: String = "trip-1") = store.events[tripId].orEmpty().map { it.event.type }

    @Test
    fun theActiveTripCarriesItsLogForThePanel() = runTest {
        val r = repo()
        r.start(setup).getOrThrow()
        r.log(TripEvent.RouteDeviation(320)).getOrThrow()
        assertEquals(listOf(EventType.TRIP_STARTED, EventType.ROUTE_DEVIATION), r.active.value!!.log.map { it.event.type })
        assertEquals(store.events.getValue("trip-1"), r.active.value!!.log)

        val after = TripSessionRepository(store, hasher, clock)
        after.resumeIfActive().getOrThrow()
        assertEquals(
            listOf(EventType.TRIP_STARTED, EventType.ROUTE_DEVIATION, EventType.MONITORING_INTERRUPTED, EventType.MONITORING_RESUMED),
            after.active.value!!.log.map { it.event.type },
        )
    }

    @Test
    fun startStoresTheTripAndLogsTripStarted() = runTest {
        val r = repo()
        val active = r.start(setup).getOrThrow()

        assertEquals(TripStatus.ACTIVE, active.state.status)
        assertEquals(listOf(EventType.TRIP_STARTED), types())
        val stored = store.trips.getValue("trip-1")
        assertEquals("언니", stored.contactLabel)
        assertTrue(hasher.verify("2580", stored.pinHash))
        assertEquals(clock.now, stored.startedAt)
        assertEquals(active, r.active.value)
    }

    @Test
    fun startRejectsBadInputAndASecondTrip() = runTest {
        val r = repo()
        assertTrue(r.start(setup.copy(pin = "12")).isFailure)
        assertTrue(r.start(setup.copy(destination = Destination(" ", 0.0, 0.0))).isFailure)
        assertTrue(store.trips.isEmpty())
        r.start(setup).getOrThrow()
        assertTrue(r.start(setup).isFailure)
        assertEquals(1, store.trips.size)
    }

    @Test
    fun logAppliesTheStateMachineAndAttachesTheLastFix() = runTest {
        val r = repo()
        r.start(setup)
        r.lastFix = GeoFix(37.51, 127.01, 0)
        clock.advance(10.seconds)

        val rec = r.log(TripEvent.NearDestination()).getOrThrow()
        assertEquals(2, rec.seq)
        assertEquals(37.51, rec.lat!!, 0.0)
        assertEquals(TripStatus.ARRIVING, r.active.value!!.state.status)
        assertEquals(TripStatus.ARRIVING, store.trips.getValue("trip-1").status)
        assertEquals(clock.now, store.trips.getValue("trip-1").lastSeenAt)

        // Illegal now (no arrival yet): rejected and not stored.
        assertTrue(r.log(TripEvent.SafeArrivalCheckOpened()).isFailure)
        assertEquals(2, store.events.getValue("trip-1").size)
    }

    @Test
    fun aFixOlderThanTwoMinutesIsNotAttached() = runTest {
        val r = repo()
        r.start(setup)
        r.lastFix = GeoFix(37.51, 127.01, 0)
        clock.advance(2.minutes)
        assertEquals(37.51, r.log(TripEvent.MonitoringInterrupted(0)).getOrThrow().lat!!, 0.0)
        clock.advance(1.seconds)
        val stale = r.log(TripEvent.MonitoringResumed()).getOrThrow()
        assertNull(stale.lat)
        assertNull(stale.lng)
    }

    @Test
    fun aNewTripIsNotStampedWithWhereThePreviousOneEnded() = runTest {
        // Checkpoint C F1: location is only collected during a trip, so the last fix is from the last trip's end.
        val r = repo()
        r.start(setup)
        r.lastFix = GeoFix(37.4846, 127.0342, 0) // arrived home
        r.endByPassenger("2580").getOrThrow()
        // Even 30 s later (well within the 2-minute freshness window) the next trip must not start "at home":
        // in the emulator run the car was moved and a trip started 44 s after the last one ended.
        clock.advance(30.seconds)
        r.start(setup).getOrThrow()
        assertNull(store.events.getValue("trip-2").single().lat)
        r.lastFix = GeoFix(37.4979, 127.0276, 0) // first fix of the new trip
        assertEquals(37.4979, r.log(TripEvent.NearDestination()).getOrThrow().lat!!, 0.0)
    }

    @Test
    fun logWithoutATripFails() = runTest {
        assertTrue(repo().log(TripEvent.Sos(SosSource.ON_SCREEN)).isFailure)
    }

    @Test
    fun endingTheTripClosesItAndFreesTheSlot() = runTest {
        val r = repo()
        r.start(setup)
        clock.advance(1.minutes)
        r.endByPassenger("2580").getOrThrow()

        assertNull(r.active.value)
        val stored = store.trips.getValue("trip-1")
        assertEquals(TripStatus.ENDED, stored.status)
        assertEquals(clock.now, stored.endedAt)
        assertTrue(r.start(setup).isSuccess)
    }

    @Test
    fun anEndedTripWaitsForItsReportUntilClosed() = runTest {
        val r = repo()
        r.start(setup)
        assertNull(r.lastEnded.value)
        r.endByPassenger("2580").getOrThrow()

        val ended = checkNotNull(r.lastEnded.value)
        assertEquals("trip-1", ended.id)
        assertEquals(TripStatus.ENDED, ended.status)
        assertEquals(clock.now, ended.endedAt)

        r.closeReport()
        assertNull(r.lastEnded.value)
    }

    @Test
    fun theNewestEndedTripCanBeReopenedFromHomeButNotDuringATrip() = runTest {
        val r = repo()
        r.loadLatestEnded()
        assertNull(r.latestEnded.value)
        r.openLatestReport()
        assertNull(r.lastEnded.value)

        r.start(setup)
        r.endByPassenger("2580").getOrThrow()
        assertEquals("trip-1", r.latestEnded.value?.id)
        r.closeReport()

        r.openLatestReport()
        assertEquals("trip-1", r.lastEnded.value?.id)
        r.closeReport()

        // A running trip keeps the report closed: 운행 기록 only shows parked with no trip.
        r.start(setup)
        r.openLatestReport()
        assertNull(r.lastEnded.value)
    }

    @Test
    fun afterARestartTheNewestEndedTripComesFromTheStore() = runTest {
        val first = repo()
        first.start(setup)
        first.endByPassenger("2580").getOrThrow()

        val restarted = repo()
        assertNull(restarted.latestEnded.value)
        restarted.loadLatestEnded()
        assertEquals("trip-1", restarted.latestEnded.value?.id)
        assertEquals(TripStatus.ENDED, restarted.latestEnded.value?.status)
    }

    @Test
    fun storageFailureLeavesTheStateUnchanged() = runTest {
        val r = repo()
        r.start(setup)
        store.failAppend = true
        assertTrue(r.log(TripEvent.NearDestination()).isFailure)
        assertEquals(TripStatus.ACTIVE, r.active.value!!.state.status)
    }

    @Test
    fun concurrentLogsKeepOneOrder() = runTest {
        val r = repo()
        r.start(setup)
        (1..30).map { async { r.log(TripEvent.Sos(SosSource.ON_SCREEN)) } }.awaitAll()
        assertEquals((1..31).toList(), store.events.getValue("trip-1").map { it.seq })
    }

    @Test
    fun restartLogsTheGapFromLastSeenAndResumes() = runTest {
        val before = repo()
        before.start(setup)
        clock.advance(30.seconds)
        before.heartbeat()
        val lastSeen = clock.now

        // Process dies; 2 minutes later a new process starts.
        clock.advance(2.minutes)
        val after = repo()
        val resumed = after.resumeIfActive().getOrThrow()

        assertNotNull(resumed)
        val log = store.events.getValue("trip-1")
        assertEquals(listOf(EventType.TRIP_STARTED, EventType.MONITORING_INTERRUPTED, EventType.MONITORING_RESUMED), types())
        val gap = log[1]
        assertEquals(TripEvent.MonitoringInterrupted(120, "앱 재시작으로 2분간 모니터링 중단"), gap.event)
        assertEquals(lastSeen, gap.occurredAt)
        assertEquals(clock.now, log[2].occurredAt)
        assertEquals(TripStatus.ACTIVE, after.active.value!!.state.status)
        assertEquals(false, after.active.value!!.state.interrupted)
    }

    @Test
    fun resumeHappensOncePerProcess() = runTest {
        repo().start(setup)
        clock.advance(1.minutes)
        val after = repo()
        after.resumeIfActive()
        after.resumeIfActive()
        after.start(setup) // also checks resume first; must not log the gap again
        assertEquals(3, store.events.getValue("trip-1").size)
    }

    @Test
    fun resumeAfterDyingMidGapOnlyLogsResumed() = runTest {
        val r = repo()
        r.start(setup)
        r.log(TripEvent.MonitoringInterrupted(10)).getOrThrow()
        clock.advance(1.minutes)
        repo().resumeIfActive().getOrThrow()
        assertEquals(listOf(EventType.TRIP_STARTED, EventType.MONITORING_INTERRUPTED, EventType.MONITORING_RESUMED), types())
    }

    @Test
    fun resumeKeepsTheRestoredStateFlags() = runTest {
        val r = repo()
        r.start(setup)
        r.log(TripEvent.Sos(SosSource.TURN_SIGNAL))
        clock.advance(5.seconds)
        val after = repo()
        after.resumeIfActive()
        assertTrue(after.active.value!!.state.sosActive)
    }

    @Test
    fun noTripMeansNothingToResume() = runTest {
        assertNull(repo().resumeIfActive().getOrThrow())
    }

    @Test
    fun tripThatEndedOrNeverStartedIsClosedNotResumed() = runTest {
        // Ended in the log, but the row update was lost.
        val ended = StoredTrip("x", TripStatus.ARRIVING, setup.destination, "", hasher.hash("1111"), PinAttempts(), clock.now, null, clock.now)
        store.insert(ended)
        store.append("x", clock.now, null, null, TripEvent.TripStarted())
        store.append("x", clock.now, null, null, TripEvent.TripEnded(EndReason.ENDED_BY_PASSENGER))
        assertNull(repo().resumeIfActive().getOrThrow())
        assertEquals(TripStatus.ENDED, store.trips.getValue("x").status)

        // Row stored, TRIP_STARTED never logged.
        store.insert(ended.copy(id = "y", status = TripStatus.ACTIVE))
        assertNull(repo().resumeIfActive().getOrThrow())
        assertEquals(TripStatus.ENDED, store.trips.getValue("y").status)
        assertTrue(store.events["y"].isNullOrEmpty())
    }

    @Test
    fun unreadableLogFailsResumeInsteadOfGuessing() = runTest {
        repo().start(setup)
        store.corruptEvents = true
        assertTrue(repo().resumeIfActive().isFailure)
    }

    @Test
    fun unreadableLogFailsResumeOnceAndClosesTheTrip() = runTest {
        repo().start(setup)
        store.corruptEvents = true
        val r = repo()
        assertTrue(r.resumeIfActive().exceptionOrNull() is UnreadableTripLog)
        assertNull(r.active.value)
        assertEquals(TripStatus.ENDED, store.trips.getValue("trip-1").status)

        // The next passenger can still start a trip.
        store.corruptEvents = false
        assertTrue(r.resumeIfActive().isSuccess)
        assertEquals("trip-2", r.start(setup).getOrThrow().trip.id)
    }

    @Test
    fun failedStartFreesTheSlot() = runTest {
        val r = repo()
        store.failAppend = true
        assertTrue(r.start(setup).isFailure)
        assertNull(r.active.value)
        assertEquals(TripStatus.ENDED, store.trips.getValue("trip-1").status)

        store.failAppend = false
        assertTrue(r.start(setup).isSuccess)
    }

    @Test
    fun heartbeatMovesLastSeenOnlyWithATrip() = runTest {
        val r = repo()
        r.heartbeat()
        r.start(setup)
        clock.advance(10.seconds)
        r.heartbeat()
        assertEquals(clock.now, store.trips.getValue("trip-1").lastSeenAt)
        assertEquals(clock.now, r.active.value!!.trip.lastSeenAt)
    }

    @Test
    fun pinAttemptsArePersistedAcrossRestarts() = runTest {
        val r = repo()
        r.start(setup)
        repeat(4) { r.checkPin("0000").getOrThrow() }
        val after = repo()
        after.resumeIfActive()
        val locked = after.checkPin("0000").getOrThrow()
        assertTrue(locked is PinResult.LockedOut)
        assertEquals(locked.attempts, store.trips.getValue("trip-1").pinAttempts)

        clock.advance(30.seconds)
        assertTrue(after.checkPin("2580").getOrThrow() is PinResult.Accepted)
        assertEquals(PinAttempts(), after.active.value!!.trip.pinAttempts)
    }

    @Test
    fun endingByPassengerNeedsThePin() = runTest {
        val r = repo()
        r.start(setup)

        val wrong = r.endByPassenger("0000").getOrThrow()
        assertEquals(PinResult.Wrong(4, PinAttempts(failures = 1)), wrong)
        assertNotNull(r.active.value)
        assertEquals(listOf(EventType.TRIP_STARTED), types())

        assertTrue(r.endByPassenger("2580").getOrThrow() is PinResult.Accepted)
        assertNull(r.active.value)
        val ended = store.events.getValue("trip-1").last().event as TripEvent.TripEnded
        assertEquals(EndReason.ENDED_BY_PASSENGER, ended.endReason)
        assertEquals(TripTexts.ENDED_BY_PASSENGER, ended.reason)
        assertEquals(TripStatus.ENDED, store.trips.getValue("trip-1").status)
    }

    @Test
    fun endingDuringTheLockoutKeepsTheTripEvenWithTheRightPin() = runTest {
        val r = repo()
        r.start(setup)
        repeat(5) { r.endByPassenger("0000").getOrThrow() }
        assertTrue(r.endByPassenger("2580").getOrThrow() is PinResult.LockedOut)
        assertNotNull(r.active.value)
    }

    @Test
    fun aNewLockoutIsLoggedForTheContactButTriesDuringItAreNot() = runTest {
        val r = repo()
        r.start(setup)
        repeat(5) { r.endByPassenger("0000").getOrThrow() }
        repeat(3) { r.checkPin("0000").getOrThrow() } // still locked: not even checked, not logged again

        val first = store.events.getValue("trip-1").map { it.event }.filterIsInstance<TripEvent.PinLockout>()
        assertEquals(listOf(TripEvent.PinLockout(5, 30, "PIN 5회 오입력 · 30초 잠금")), first)

        clock.advance(30.seconds)
        repeat(5) { r.checkPin("0000").getOrThrow() }
        val all = store.events.getValue("trip-1").map { it.event }.filterIsInstance<TripEvent.PinLockout>()
        assertEquals(TripEvent.PinLockout(10, 60, "PIN 10회 오입력 · 1분 잠금"), all.last())
        assertEquals(2, all.size)
        assertNotNull(r.active.value) // reported only: the trip goes on
    }

    @Test
    fun aLockoutThatCannotBeStoredStillLocksThePin() = runTest {
        val r = repo()
        r.start(setup)
        repeat(4) { r.checkPin("0000").getOrThrow() }
        store.failAppend = true
        assertTrue(r.checkPin("0000").getOrThrow() is PinResult.LockedOut)
    }

    @Test
    fun eachTripGetsItsOwnShareToken() = runTest {
        val r = repo()
        r.start(setup)
        r.endByPassenger("2580")
        r.start(setup)

        val tokens = store.trips.values.map { it.shareToken }
        assertTrue(tokens.all { com.coral.gwigaon.sync.ShareToken.isValid(it) })
        assertEquals(2, tokens.toSet().size)
        assertTrue("token must not reach logs", tokens.none { it in store.trips.getValue("trip-1").toString() })
    }

    @Test
    fun endingFailsWhileTheSafeArrivalCheckIsOpen() = runTest {
        val r = repo()
        r.start(setup)
        r.log(TripEvent.Arrived()).getOrThrow()
        r.log(TripEvent.SafeArrivalCheckOpened()).getOrThrow()
        assertTrue(r.endByPassenger("2580").isFailure)
        assertNotNull(r.active.value)
    }

    @Test
    fun passengerEndWithoutThePinIsRefusedByLog() = runTest {
        val r = repo()
        r.start(setup)
        assertTrue(r.log(TripEvent.TripEnded(EndReason.ENDED_BY_PASSENGER)).isFailure)
        assertNotNull(r.active.value)
        assertEquals(listOf(EventType.TRIP_STARTED), types())
    }

    @Test
    fun endingWithoutATripFails() = runTest {
        assertTrue(repo().endByPassenger("2580").isFailure)
    }

    @Test
    fun loggingATripNeverShowsThePinHashOrContact() = runTest {
        val active = repo().start(setup).getOrThrow()
        val text = active.toString()
        assertTrue(text.contains("trip-1"))
        assertTrue(!text.contains(active.trip.pinHash) && !text.contains("pbkdf2") && !text.contains("언니"))
    }

    @Test
    fun arrivalInstructionsAreStoredTrimmed() = runTest {
        val r = repo()
        r.start(setup.copy(arrival = ArrivalInstructions(" B2 ", "", " 키는 운전석에 "))).getOrThrow()
        val arrival = store.trips.getValue("trip-1").arrival
        assertEquals(ArrivalInstructions("B2", "", "키는 운전석에"), arrival)
        assertTrue(!arrival.isEmpty)
        assertTrue(ArrivalInstructions(" ", "", "").isEmpty)
    }

    @Test
    fun theSpokenSentenceFromSettingsGoesWithTheTrip() = runTest {
        // ADR 0021: Claude's sentence saved in 설정 is what the trip speaks near home (Checkpoint F review C1).
        val r = repo()
        val sentence = "B2에 주차해 주시고 키는 운전석에 두어 주세요. 감사합니다."
        r.start(setup.copy(arrival = ArrivalInstructions(" B2 ", "", "키는 운전석에", spoken = sentence))).getOrThrow()
        assertEquals(sentence, store.trips.getValue("trip-1").arrival.spoken)
        assertEquals(sentence, r.active.value?.trip?.arrival?.spoken)
    }

    @Test
    fun recentsComeFromPastTrips() = runTest {
        val r = repo()
        r.start(setup).getOrThrow()
        r.endByPassenger("2580").getOrThrow()
        clock.advance(1.minutes)
        val office = Destination("회사", 37.4, 127.1)
        r.start(setup.copy(destination = office)).getOrThrow()
        assertEquals(listOf(office, setup.destination), r.recentDestinations())
    }

    @Test
    fun pinCheckNeedsATrip() = runTest {
        assertTrue(repo().checkPin("1234").isFailure)
    }
}

class TripTextsTest {
    @Test
    fun durationsReadNaturally() {
        assertEquals("0초", TripTexts.duration((-5).seconds))
        assertEquals("45초", TripTexts.duration(45.seconds))
        assertEquals("2분", TripTexts.duration(2.minutes))
        assertEquals("2분 30초", TripTexts.duration(150.seconds))
        assertEquals("1시간 5분", TripTexts.duration(65.minutes + 12.seconds))
        assertEquals("2시간", TripTexts.duration(120.minutes))
        assertEquals("앱 재시작으로 45초간 모니터링 중단", TripTexts.interrupted(45.seconds))
    }

    @Test
    fun gapSecondsIsClamped() {
        assertEquals(0, TripTexts.gapSeconds((-3).seconds))
        assertEquals(90, TripTexts.gapSeconds(90.seconds))
    }

    @Test
    fun notificationShowsTheMostUrgentCondition() {
        val active = TripState(TripStatus.ACTIVE)
        assertEquals("귀가온 운행 중 · 집", TripTexts.notificationTitle("집"))
        assertEquals("안심 귀가 모니터링 중", TripTexts.notificationStatus(active))
        assertEquals("목적지 외 장소에서 정차 중", TripTexts.notificationStatus(active.copy(longStopped = true)))
        assertEquals("목적지와 다른 방향으로 이동 중", TripTexts.notificationStatus(active.copy(longStopped = true, deviating = true)))
        assertEquals("모니터링 재연결 중", TripTexts.notificationStatus(active.copy(deviating = true, interrupted = true)))
        assertEquals("SOS 발생", TripTexts.notificationStatus(active.copy(interrupted = true, sosActive = true)))
        assertEquals("목적지 근처", TripTexts.notificationStatus(TripState(TripStatus.ARRIVING)))
        assertEquals("목적지 도착", TripTexts.notificationStatus(TripState(TripStatus.ARRIVING, arrived = true)))
        assertEquals("승객 상태를 확인해 주세요", TripTexts.notificationStatus(TripState(TripStatus.SAFE_ARRIVAL_CHECK, arrived = true)))
        val driverConfirmed = TripState(TripStatus.ARRIVING, arrived = true, safeArrivalConfirmed = true)
        assertEquals(
            "승객 하차를 기다리는 중",
            TripTexts.notificationStatus(driverConfirmed.copy(safeArrivalClosedBy = SafeArrivalCloser.DRIVER_CONFIRMED)),
        )
        assertEquals("목적지 도착", TripTexts.notificationStatus(driverConfirmed.copy(safeArrivalClosedBy = SafeArrivalCloser.SEAT_EMPTY)))
    }

    @Test
    fun statusToneFollowsTheMostUrgentCondition() {
        assertEquals(StatusTone.NORMAL, TripTexts.statusTone(TripState(TripStatus.ACTIVE)))
        assertEquals(StatusTone.NORMAL, TripTexts.statusTone(TripState(TripStatus.ARRIVING, arrived = true)))
        assertEquals(StatusTone.CAUTION, TripTexts.statusTone(TripState(TripStatus.ACTIVE, deviating = true)))
        assertEquals(StatusTone.CAUTION, TripTexts.statusTone(TripState(TripStatus.ACTIVE, longStopped = true)))
        assertEquals(StatusTone.CAUTION, TripTexts.statusTone(TripState(TripStatus.ACTIVE, interrupted = true)))
        assertEquals(StatusTone.CAUTION, TripTexts.statusTone(TripState(TripStatus.SAFE_ARRIVAL_CHECK, arrived = true)))
        assertEquals(StatusTone.ALERT, TripTexts.statusTone(TripState(TripStatus.ACTIVE, sosActive = true)))
        assertEquals(StatusTone.ALERT, TripTexts.statusTone(TripState(TripStatus.ACTIVE, deviating = true, sosActive = true)))
    }

    @Test
    fun pinMessages() {
        val now = Instant.parse("2026-11-11T09:00:00Z")
        assertNull(TripTexts.pinMessage(PinResult.Accepted(PinAttempts()), now))
        assertEquals("PIN이 맞지 않습니다 (남은 시도 2회)", TripTexts.pinMessage(PinResult.Wrong(2, PinAttempts(3)), now))
        val locked = { until: Instant -> TripTexts.pinMessage(PinResult.LockedOut(until, PinAttempts(lockedUntil = until)), now) }
        assertEquals("PIN을 여러 번 틀렸습니다. 30초 후에 다시 시도해 주세요", locked(now + 30.seconds))
        assertEquals("PIN을 여러 번 틀렸습니다. 30초 후에 다시 시도해 주세요", locked(now + 29_400.milliseconds))
        // Rounded up: 0.2 s left still reads as 1초, never 0초.
        assertEquals("PIN을 여러 번 틀렸습니다. 1초 후에 다시 시도해 주세요", locked(now + 200.milliseconds))
        assertEquals("PIN을 여러 번 틀렸습니다. 1초 후에 다시 시도해 주세요", locked(now - 1.seconds))
    }
}
