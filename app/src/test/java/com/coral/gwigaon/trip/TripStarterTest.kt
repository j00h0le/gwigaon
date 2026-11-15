package com.coral.gwigaon.trip

import com.coral.gwigaon.vehicle.Destination
import com.coral.gwigaon.vehicle.Gear
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

class TripStarterTest {
    private val home = Destination("양재역", 37.48460, 127.03420)
    private val clock = TestClock()
    private val hasher = PinHasher(SecureRandom(), iterations = 1)
    private val trips = FakeTripStore()
    private val sessions = TripSessionRepository(trips, hasher, clock) { "trip-${trips.trips.size + 1}" }
    private val settings = SettingsRepository(FakeSettingsStore(), hasher, clock, tripActive = { sessions.active.value != null })
    private val navigated = mutableListOf<Destination>()
    private val cuesFor = mutableListOf<String>()
    private val starter = TripStarter(sessions, settings, { navigated += it; Result.success(Unit) }, { cuesFor += it })

    private val complete = SettingsDraft(
        home = home,
        contacts = listOf("언니", "엄마"),
        arrival = ArrivalInstructions("지하 2층"),
        pinOn = true,
        pin = "2580",
        pinConfirm = "2580",
    )

    private fun events(id: String) = trips.events[id].orEmpty().map { it.event }

    private suspend fun endTrip() {
        sessions.endByPassenger("2580").getOrThrow()
    }

    @Test
    fun buttonStartGoesHomeWithTheSettings() = runTest {
        settings.save(complete).getOrThrow()
        val trip = starter.start(StartSource.BUTTON).getOrThrow().trip

        assertEquals(listOf<TripEvent>(TripEvent.TripStarted(StartSource.BUTTON)), events(trip.id))
        assertEquals(home, trip.destination)
        assertEquals("언니, 엄마", trip.contactLabel)
        assertEquals(ArrivalInstructions("지하 2층"), trip.arrival)
        assertEquals(settings.settings.value.pinHash, trip.pinHash)
        assertTrue(hasher.verify("2580", trip.pinHash))
        assertEquals(listOf(home), navigated)
        assertTrue(cuesFor.isEmpty())
    }

    @Test
    fun cuesTurnOnWhenSettingsSaySo() = runTest {
        settings.save(complete.copy(cues = true)).getOrThrow()
        val trip = starter.start(StartSource.BUTTON).getOrThrow().trip
        assertEquals(listOf(trip.id), cuesFor)
    }

    @Test
    fun incompleteSettingsStartNothing() = runTest {
        settings.save(complete.copy(contacts = emptyList())).getOrThrow()
        assertTrue(starter.start(StartSource.BUTTON).exceptionOrNull() is IncompleteSettingsException)
        settings.save(complete.copy(home = null)).getOrThrow()
        assertTrue(starter.start(StartSource.GEAR).exceptionOrNull() is IncompleteSettingsException)
        assertNull(sessions.active.value)
        assertTrue(trips.trips.isEmpty())
        assertTrue(navigated.isEmpty())
    }

    @Test
    fun settingsChangedIsLoggedRightAfterTheStartOnlyWhenSomethingChanged() = runTest {
        settings.save(complete).getOrThrow()
        val first = starter.start(StartSource.BUTTON).getOrThrow().trip
        assertEquals(1, events(first.id).size)
        endTrip()

        val second = starter.start(StartSource.BUTTON).getOrThrow().trip
        assertEquals(listOf<TripEvent>(TripEvent.TripStarted(StartSource.BUTTON)), events(second.id))
        endTrip()

        settings.save(SettingsDraft.from(settings.settings.value).copy(contacts = listOf("언니"), cues = true)).getOrThrow()
        val third = starter.start(StartSource.GEAR).getOrThrow().trip
        assertEquals(
            listOf(
                TripEvent.TripStarted(StartSource.GEAR),
                TripEvent.SettingsChanged(listOf(SettingsField.CONTACTS, SettingsField.CUES), "설정 변경: 비상 연락처, 대화 감지"),
            ),
            events(third.id),
        )
        endTrip()
        val fourth = starter.start(StartSource.BUTTON).getOrThrow().trip
        assertEquals(1, events(fourth.id).size)
    }

    @Test
    fun aSettingsPinOffTripHasNoPin() = runTest {
        settings.save(complete.copy(pinOn = false)).getOrThrow()
        val trip = starter.start(StartSource.BUTTON).getOrThrow().trip
        assertEquals("", trip.pinHash)
    }

    @Test
    fun gearOutOfParkStartsOnceWhenReady() = runTest {
        settings.save(complete).getOrThrow()
        val gear = MutableStateFlow<Gear?>(null)
        var ready = true
        val job = starter.watchGear(gear, { ready && sessions.active.value == null }, this)
        runCurrent()
        gear.value = Gear.PARK; runCurrent()
        gear.value = Gear.DRIVE; runCurrent()
        val trip = checkNotNull(sessions.active.value).trip
        assertEquals(listOf<TripEvent>(TripEvent.TripStarted(StartSource.GEAR)), events(trip.id))

        // A stop at a light and off again during the trip starts nothing new.
        gear.value = Gear.PARK; runCurrent()
        gear.value = Gear.DRIVE; runCurrent()
        assertEquals(1, trips.trips.size)
        assertEquals(listOf(home), navigated)

        endTrip()
        ready = false
        gear.value = Gear.PARK; runCurrent()
        gear.value = Gear.REVERSE; runCurrent()
        assertEquals(1, trips.trips.size)
        job.cancel()
    }

    @Test
    fun aGearAlreadyOutOfParkStartsNothing() = runTest {
        settings.save(complete).getOrThrow()
        val gear = MutableStateFlow<Gear?>(Gear.DRIVE)
        val job = starter.watchGear(gear, { true }, this)
        runCurrent()
        gear.value = Gear.NEUTRAL; runCurrent()
        assertNull(sessions.active.value)
        job.cancel()
    }

    @Test
    fun gearStartRule() {
        assertTrue(GearStart.shouldStart(Gear.PARK, Gear.DRIVE, ready = true))
        assertTrue(GearStart.shouldStart(Gear.PARK, Gear.REVERSE, ready = true))
        assertFalse(GearStart.shouldStart(Gear.PARK, Gear.DRIVE, ready = false))
        assertFalse(GearStart.shouldStart(null, Gear.DRIVE, ready = true))
        assertFalse(GearStart.shouldStart(Gear.PARK, Gear.PARK, ready = true))
        assertFalse(GearStart.shouldStart(Gear.PARK, null, ready = true))
        assertFalse(GearStart.shouldStart(Gear.DRIVE, Gear.NEUTRAL, ready = true))
    }

    @Test
    fun navigationFailureDoesNotUndoTheTrip() = runTest {
        settings.save(complete).getOrThrow()
        val logged = mutableListOf<String>()
        val s = TripStarter(sessions, settings, { Result.failure(IllegalStateException("no navi")) }, {}, { m, _ -> logged += m })
        assertTrue(s.start(StartSource.BUTTON).isSuccess)
        assertEquals(listOf("navigation not set"), logged)
    }

}

class TripStarterSaveTest {
    @Test
    fun saveAndStartSavesThenStartsByButton() = kotlinx.coroutines.test.runTest {
        val clock = TestClock()
        val hasher = PinHasher(SecureRandom(), iterations = 1)
        val trips = FakeTripStore()
        val sessions = TripSessionRepository(trips, hasher, clock) { "t" }
        val settings = SettingsRepository(FakeSettingsStore(), hasher, clock, tripActive = { sessions.active.value != null })
        val starter = TripStarter(sessions, settings, { Result.success(Unit) }, {})
        assertTrue(starter.saveAndStart(SettingsDraft(contacts = listOf(""))).isFailure)
        assertTrue(trips.trips.isEmpty())
        val draft = SettingsDraft(Destination("집", 37.5, 127.0), listOf("언니"))
        val trip = starter.saveAndStart(draft).getOrThrow().trip
        assertEquals(listOf<TripEvent>(TripEvent.TripStarted(StartSource.BUTTON)), trips.events[trip.id].orEmpty().map { it.event })
        assertTrue(settings.settings.value.isComplete)
    }
}
