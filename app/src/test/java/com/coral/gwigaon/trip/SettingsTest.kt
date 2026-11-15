package com.coral.gwigaon.trip

import com.coral.gwigaon.vehicle.Destination
import com.coral.gwigaon.vehicle.DrivingState
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

/** In-memory [SettingsStore] for unit tests. */
class FakeSettingsStore : SettingsStore {
    var current: Settings? = null
    var atLastTrip: Settings? = null
    var attempts = PinAttempts()

    override suspend fun current(): Settings? = current
    override suspend fun put(settings: Settings) {
        current = settings
    }

    override suspend fun atLastTrip(): Settings? = atLastTrip
    override suspend fun putAtLastTrip(settings: Settings) {
        atLastTrip = settings
    }

    override suspend fun pinAttempts(): PinAttempts = attempts
    override suspend fun setPinAttempts(attempts: PinAttempts) {
        this.attempts = attempts
    }
}

class SettingsRulesTest {
    private val home = Destination("양재역", 37.48460, 127.03420)
    private val hasher = PinHasher(SecureRandom(), iterations = 1)

    @Test
    fun completeNeedsHomeAndAContact() {
        assertFalse(Settings().isComplete)
        assertFalse(Settings(home = home).isComplete)
        assertFalse(Settings(contacts = listOf("언니")).isComplete)
        assertTrue(Settings(home = home, contacts = listOf("언니")).isComplete)
    }

    @Test
    fun pinIsOnOnlyWithAHash() {
        assertFalse(Settings().pinOn)
        assertTrue(Settings(pinHash = hasher.hash("2580")).pinOn)
    }

    @Test
    fun emptyDraftHasNoErrorsSoAPartialFirstRunCanBeSaved() {
        assertEquals(emptyMap<SettingsInput, String>(), SettingsDraft().errors(existing = Settings()))
    }

    @Test
    fun contactLabelsAreCheckedOneByOne() {
        val draft = SettingsDraft(contacts = listOf("언니", " ", "가".repeat(21), " 언니 "))
        assertEquals(
            mapOf(
                SettingsInput(SettingsField.CONTACTS, 1) to "보호자 이름을 입력해 주세요",
                SettingsInput(SettingsField.CONTACTS, 2) to "20자 이내로 입력해 주세요",
                SettingsInput(SettingsField.CONTACTS, 3) to "이미 등록된 보호자예요",
            ),
            draft.errors(Settings()),
        )
        assertTrue(SettingsDraft(contacts = listOf(" " + "가".repeat(20) + " ")).errors(Settings()).isEmpty())
    }

    @Test
    fun tooManyContactsIsAnError() {
        val draft = SettingsDraft(contacts = (1..Settings.MAX_CONTACTS + 1).map { "보호자$it" })
        assertEquals(
            mapOf(SettingsInput(SettingsField.CONTACTS, Settings.MAX_CONTACTS) to "보호자는 ${Settings.MAX_CONTACTS}명까지예요"),
            draft.errors(Settings()),
        )
    }

    @Test
    fun arrivalNotesAreAtMostThirty() {
        val long = "가".repeat(31)
        val errors = SettingsDraft(arrival = ArrivalInstructions(long, long, long)).errors(Settings())
        assertEquals(
            setOf(SettingsInput(SettingsField.ARRIVAL, 0), SettingsInput(SettingsField.ARRIVAL, 1), SettingsInput(SettingsField.ARRIVAL, 2)),
            errors.keys,
        )
        assertTrue(SettingsDraft(arrival = ArrivalInstructions("가".repeat(30))).errors(Settings()).isEmpty())
    }

    @Test
    fun aNewPinIsFourDigitsEnteredTwice() {
        val pinOn = SettingsDraft(pinOn = true)
        assertEquals(mapOf(SettingsDraft.PIN to "PIN은 숫자 4자리입니다"), pinOn.errors(Settings()))
        assertEquals(mapOf(SettingsDraft.PIN to "PIN은 숫자 4자리입니다"), pinOn.copy(pin = "258", pinConfirm = "258").errors(Settings()))
        assertEquals(mapOf(SettingsDraft.PIN_CONFIRM to "PIN이 일치하지 않습니다"), pinOn.copy(pin = "2580", pinConfirm = "2581").errors(Settings()))
        assertTrue(pinOn.copy(pin = "2580", pinConfirm = "2580").errors(Settings()).isEmpty())
    }

    @Test
    fun anExistingPinIsKeptWhenNoNewOneIsTyped() {
        val existing = Settings(pinHash = hasher.hash("2580"))
        val draft = SettingsDraft.from(existing)
        assertTrue(draft.pinOn)
        assertTrue(draft.errors(existing).isEmpty())
        assertEquals(existing.pinHash, draft.toSettings(existing, hasher)?.pinHash)
    }

    @Test
    fun pinOffDropsTheHashAndPinFieldsAreIgnored() {
        val existing = Settings(pinHash = hasher.hash("2580"))
        val off = SettingsDraft.from(existing).copy(pinOn = false, pin = "12", pinConfirm = "")
        assertTrue(off.errors(existing).isEmpty())
        assertNull(off.toSettings(existing, hasher)?.pinHash)
    }

    @Test
    fun toSettingsTrimsAndHashesTheNewPin() {
        val draft = SettingsDraft(
            home = home,
            contacts = listOf(" 언니 ", "엄마"),
            arrival = ArrivalInstructions(" 지하 2층 ", "", "키는 운전석에 "),
            cues = true,
            pinOn = true,
            pin = "2580",
            pinConfirm = "2580",
        )
        val saved = draft.toSettings(Settings(), hasher)
        assertNotNull(saved)
        saved!!
        assertEquals(home, saved.home)
        assertEquals(listOf("언니", "엄마"), saved.contacts)
        assertEquals(ArrivalInstructions("지하 2층", "", "키는 운전석에"), saved.arrival)
        assertTrue(saved.cues)
        assertTrue(hasher.verify("2580", checkNotNull(saved.pinHash)))
        assertFalse(saved.toString().contains("언니"))
        assertNull(draft.copy(pinConfirm = "0000").toSettings(Settings(), hasher))
    }

    @Test
    fun pinInputKeepsFourDigits() {
        assertEquals("1234", SettingsDraft.pinInput("12a3-45"))
    }

    @Test
    fun changedFieldsNameWhatChangedNotTheValues() {
        val before = Settings(home = home, contacts = listOf("언니"), arrival = ArrivalInstructions("지하 1층"))
        assertEquals(emptySet<SettingsField>(), before.changedSince(before))
        assertEquals(emptySet<SettingsField>(), before.changedSince(null))
        val after = before.copy(
            home = Destination("강남역", 37.49794, 127.02762),
            contacts = listOf("언니", "엄마"),
            cues = true,
            arrival = ArrivalInstructions("지하 2층"),
            pinHash = hasher.hash("2580"),
        )
        assertEquals(SettingsField.entries.toSet(), after.changedSince(before))
        assertEquals(listOf("home", "contacts", "cues", "arrival", "pin"), SettingsField.entries.map { it.wire })
    }

    @Test
    fun aNewPinCountsAsAChangeEvenIfTheDigitsAreTheSame() {
        val a = Settings(pinHash = hasher.hash("2580"))
        val b = Settings(pinHash = hasher.hash("2580"))
        assertNotEquals(a.pinHash, b.pinHash)
        assertEquals(setOf(SettingsField.PIN), b.changedSince(a))
    }
}

class SettingsGateTest {
    @Test
    fun settingsOpenOnlyParkedWithNoTrip() {
        assertEquals(SettingsAccess.OPEN, SettingsGate.access(DrivingState.PARKED, tripActive = false, pinOn = false, unlocked = false))
        assertEquals(SettingsAccess.BLOCKED_DRIVING, SettingsGate.access(DrivingState.DRIVING, tripActive = false, pinOn = false, unlocked = true))
        assertEquals(SettingsAccess.BLOCKED_TRIP, SettingsGate.access(DrivingState.PARKED, tripActive = true, pinOn = false, unlocked = true))
    }

    @Test
    fun withThePinOnTheyNeedThePinFirst() {
        assertEquals(SettingsAccess.NEEDS_PIN, SettingsGate.access(DrivingState.PARKED, tripActive = false, pinOn = true, unlocked = false))
        assertEquals(SettingsAccess.OPEN, SettingsGate.access(DrivingState.PARKED, tripActive = false, pinOn = true, unlocked = true))
        assertEquals(SettingsAccess.BLOCKED_DRIVING, SettingsGate.access(DrivingState.DRIVING, tripActive = false, pinOn = true, unlocked = false))
    }
}

class SettingsRepositoryTest {
    private val home = Destination("양재역", 37.48460, 127.03420)
    private val clock = TestClock()
    private val hasher = PinHasher(SecureRandom(), iterations = 1)
    private val store = FakeSettingsStore()
    private var tripActive = false
    private val repo = SettingsRepository(store, hasher, clock, tripActive = { tripActive })

    private val complete = SettingsDraft(home = home, contacts = listOf("언니"))

    @Test
    fun loadsWhatWasSaved() = runTest {
        store.current = Settings(home = home)
        repo.load()
        assertEquals(Settings(home = home), repo.settings.value)
    }

    @Test
    fun nothingSavedYetMeansEmptySettings() = runTest {
        repo.load()
        assertEquals(Settings(), repo.settings.value)
        assertFalse(repo.settings.value.isComplete)
    }

    @Test
    fun saveStoresAndPublishes() = runTest {
        val saved = repo.save(complete).getOrThrow()
        assertTrue(saved.isComplete)
        assertEquals(saved, store.current)
        assertEquals(saved, repo.settings.value)
    }

    @Test
    fun saveRefusesAnInvalidDraftAndKeepsTheOldSettings() = runTest {
        repo.save(complete).getOrThrow()
        assertTrue(repo.save(complete.copy(contacts = listOf(""))).isFailure)
        assertEquals(listOf("언니"), store.current?.contacts)
    }

    @Test
    fun saveRefusesWhileATripRuns() = runTest {
        tripActive = true
        assertTrue(repo.save(complete).isFailure)
        assertNull(store.current)
    }

    @Test
    fun settingANewPinResetsTheLockout() = runTest {
        store.attempts = PinAttempts(failures = 3)
        repo.save(complete.copy(pinOn = true, pin = "2580", pinConfirm = "2580")).getOrThrow()
        assertEquals(PinAttempts(), store.attempts)
    }

    @Test
    fun unlockWithoutAPinIsOpen() = runTest {
        repo.save(complete).getOrThrow()
        assertTrue(repo.unlock("").getOrThrow() is PinResult.Accepted)
    }

    @Test
    fun unlockChecksThePinWithTheLockout() = runTest {
        repo.save(complete.copy(pinOn = true, pin = "2580", pinConfirm = "2580")).getOrThrow()
        assertTrue(repo.unlock("0000").getOrThrow() is PinResult.Wrong)
        assertEquals(1, store.attempts.failures)
        repeat(3) { repo.unlock("0000") }
        assertTrue(repo.unlock("0000").getOrThrow() is PinResult.LockedOut)
        assertTrue(repo.unlock("2580").getOrThrow() is PinResult.LockedOut)
        clock.now = clock.now + kotlin.time.Duration.parse("31s")
        assertTrue(repo.unlock("2580").getOrThrow() is PinResult.Accepted)
        assertEquals(PinAttempts(), store.attempts)
    }

    @Test
    fun aTripStartTakesTheChangesSinceTheLastTripAndMovesTheBaseline() = runTest {
        repo.save(complete).getOrThrow()
        // The first trip has nothing to compare with: the first settings are not "changes".
        assertEquals(emptySet<SettingsField>(), repo.markTripStarted().getOrThrow())
        assertEquals(emptySet<SettingsField>(), repo.markTripStarted().getOrThrow())
        repo.save(SettingsDraft.from(repo.settings.value).copy(cues = true)).getOrThrow()
        assertEquals(setOf(SettingsField.CUES), repo.markTripStarted().getOrThrow())
        assertEquals(emptySet<SettingsField>(), repo.markTripStarted().getOrThrow())
    }

    @Test
    fun changingSomethingAndBackIsNoChange() = runTest {
        repo.save(complete).getOrThrow()
        repo.markTripStarted()
        repo.save(complete.copy(cues = true)).getOrThrow()
        repo.save(complete.copy(cues = false)).getOrThrow()
        assertEquals(emptySet<SettingsField>(), repo.markTripStarted().getOrThrow())
    }

    @Test
    fun changesSinceLastTripPeeksWithoutMovingTheBaseline() = runTest {
        repo.save(complete).getOrThrow()
        repo.markTripStarted().getOrThrow()
        repo.save(complete.copy(cues = true)).getOrThrow()
        assertEquals(setOf(SettingsField.CUES), repo.changesSinceLastTrip().getOrThrow())
        assertEquals(setOf(SettingsField.CUES), repo.changesSinceLastTrip().getOrThrow())
        assertEquals(setOf(SettingsField.CUES), repo.markTripStarted().getOrThrow())
        assertEquals(emptySet<SettingsField>(), repo.changesSinceLastTrip().getOrThrow())
    }

    @Test
    fun theSpokenSentenceStaysOnlyWhileItsNotesDo() = runTest {
        val notes = ArrivalInstructions(parking = "지하 2층")
        repo.save(complete.copy(arrival = notes)).getOrThrow()
        assertEquals(true, repo.setSpoken(notes, "지하 2층에 주차해 주세요. 감사합니다.").getOrThrow())
        repo.markTripStarted().getOrThrow()

        // Saving something else keeps it, and the sentence alone is no change for SETTINGS_CHANGED.
        repo.save(SettingsDraft.from(repo.settings.value).copy(cues = true)).getOrThrow()
        assertEquals("지하 2층에 주차해 주세요. 감사합니다.", repo.settings.value.arrival.spoken)
        assertEquals(setOf(SettingsField.CUES), repo.changesSinceLastTrip().getOrThrow())

        // New notes drop it, and an answer for the old notes is refused.
        repo.save(SettingsDraft.from(repo.settings.value).copy(arrival = ArrivalInstructions(parking = "지상 1층"))).getOrThrow()
        assertEquals("", repo.settings.value.arrival.spoken)
        assertEquals(false, repo.setSpoken(notes, "지하 2층에 주차해 주세요. 감사합니다.").getOrThrow())
        assertEquals("", store.current?.arrival?.spoken)
    }
}
