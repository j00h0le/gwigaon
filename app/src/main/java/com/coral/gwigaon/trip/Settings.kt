package com.coral.gwigaon.trip

import com.coral.gwigaon.vehicle.Destination
import com.coral.gwigaon.vehicle.DrivingState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock

/** The parts of 설정 a trip can report as changed (`SETTINGS_CHANGED {fields}`); [wire] is the payload value. */
enum class SettingsField(val wire: String) {
    HOME("home"),
    CONTACTS("contacts"),
    CUES("cues"),
    ARRIVAL("arrival"),
    PIN("pin"),
    ;

    companion object {
        fun fromWire(wire: String): SettingsField? = entries.firstOrNull { it.wire == wire }
    }
}

/**
 * The car owner's one-time 설정 (SPEC trip-core, ADR 0015): every trip goes to [home], every [contacts] 보호자
 * follows it, [arrival] is told to the driver near home, [cues] turns conflict cues on, and a [pinHash] (null = off)
 * means the trip only ends with the PIN. Never holds the PIN itself.
 */
data class Settings(
    val home: Destination? = null,
    val contacts: List<String> = emptyList(),
    val arrival: ArrivalInstructions = ArrivalInstructions(),
    val cues: Boolean = false,
    /** [PinHasher] stored form, or null when PIN으로 운행 종료 is off. */
    val pinHash: String? = null,
    /** The 비상 연락처 step (the account's QR, ADR 0019) has been shown once. */
    val linkShown: Boolean = false,
) {
    val pinOn: Boolean get() = pinHash != null

    /** The 비상 연락처 part is done: the QR step was seen (or, before ADR 0019, a 보호자 was named). */
    val contactsDone: Boolean get() = linkShown || contacts.any { it.isNotBlank() }

    /** A trip can start: home is set and the 비상 연락처 step is done (ADR 0019). */
    val isComplete: Boolean get() = home != null && contactsDone

    /** What differs from [baseline] (the settings at the last trip start); nothing when there was no earlier trip. */
    fun changedSince(baseline: Settings?): Set<SettingsField> {
        if (baseline == null) return emptySet()
        return buildSet {
            if (home != baseline.home) add(SettingsField.HOME)
            if (contacts != baseline.contacts) add(SettingsField.CONTACTS)
            if (cues != baseline.cues) add(SettingsField.CUES)
            if (!arrival.sameNotes(baseline.arrival)) add(SettingsField.ARRIVAL)
            if (pinHash != baseline.pinHash) add(SettingsField.PIN)
        }
    }

    /** No home, names, notes or hash in logs. */
    override fun toString(): String =
        "Settings(home=${home != null}, contacts=${contacts.size}, arrival=$arrival, cues=$cues, pinOn=$pinOn)"

    companion object {
        const val MAX_CONTACTS = 5
        const val MAX_LABEL = 20

        /** Short enough to read at a glance on the arrival card (≤ 3 short lines, SPEC arrival). */
        const val MAX_NOTE = 30
    }
}

/** One input on the 설정 screens: a field plus which entry (보호자 index; arrival 0 parking, 1 entrance, 2 key). */
data class SettingsInput(val field: SettingsField, val index: Int = 0)

/**
 * 설정 while it is being edited. Partial drafts are fine (first run can stop halfway, "설정 이어서 하기"); only
 * what is filled in is checked. With the PIN on and nothing typed, an existing PIN is kept.
 */
data class SettingsDraft(
    val home: Destination? = null,
    val contacts: List<String> = emptyList(),
    val arrival: ArrivalInstructions = ArrivalInstructions(),
    val cues: Boolean = false,
    val pinOn: Boolean = false,
    val pin: String = "",
    val pinConfirm: String = "",
    val linkShown: Boolean = false,
) {
    /** Korean inline errors per input; empty when [toSettings] will succeed. */
    fun errors(existing: Settings): Map<SettingsInput, String> = buildMap {
        val seen = mutableSetOf<String>()
        contacts.forEachIndexed { i, raw ->
            val label = raw.trim()
            val key = SettingsInput(SettingsField.CONTACTS, i)
            when {
                i >= Settings.MAX_CONTACTS -> put(key, "보호자는 ${Settings.MAX_CONTACTS}명까지예요")
                label.isEmpty() -> put(key, "보호자 이름을 입력해 주세요")
                label.length > Settings.MAX_LABEL -> put(key, "${Settings.MAX_LABEL}자 이내로 입력해 주세요")
                !seen.add(label) -> put(key, "이미 등록된 보호자예요")
            }
        }
        listOf(arrival.parking, arrival.entrance, arrival.keyNote).forEachIndexed { i, note ->
            if (note.trim().length > Settings.MAX_NOTE) {
                put(SettingsInput(SettingsField.ARRIVAL, i), "${Settings.MAX_NOTE}자 이내로 입력해 주세요")
            }
        }
        if (pinOn && (pin.isNotEmpty() || pinConfirm.isNotEmpty() || !existing.pinOn)) {
            when {
                !PinHasher.isValidPin(pin) -> put(PIN, "PIN은 숫자 ${PinHasher.PIN_LENGTH}자리입니다")
                pinConfirm != pin -> put(PIN_CONFIRM, "PIN이 일치하지 않습니다")
            }
        }
    }

    /** The settings to save (trimmed, new PIN hashed), or null while [errors] isn't empty. */
    fun toSettings(existing: Settings, hasher: PinHasher): Settings? {
        if (errors(existing).isNotEmpty()) return null
        val pinHash = when {
            !pinOn -> null
            pin.isNotEmpty() -> hasher.hash(pin)
            else -> existing.pinHash
        }
        val notes = ArrivalInstructions(arrival.parking.trim(), arrival.entrance.trim(), arrival.keyNote.trim())
        return Settings(
            home = home,
            contacts = contacts.map { it.trim() },
            // Claude's sentence stays only while the notes it was written for do (ADR 0021).
            arrival = if (notes.sameNotes(existing.arrival)) notes.copy(spoken = existing.arrival.spoken) else notes,
            cues = cues,
            pinHash = pinHash,
            linkShown = linkShown,
        )
    }

    /** No names, notes or PIN digits in logs. */
    override fun toString(): String =
        "SettingsDraft(home=${home != null}, contacts=${contacts.size}, cues=$cues, pinOn=$pinOn, pinTyped=${pin.isNotEmpty()})"

    companion object {
        /** The PIN fields share [SettingsField.PIN]: 0 is the PIN, 1 the confirmation. */
        val PIN = SettingsInput(SettingsField.PIN, 0)
        val PIN_CONFIRM = SettingsInput(SettingsField.PIN, 1)

        /** Starts editing saved settings; the PIN fields start empty (the stored PIN is only a hash). */
        fun from(settings: Settings) = SettingsDraft(
            home = settings.home,
            contacts = settings.contacts,
            arrival = settings.arrival,
            cues = settings.cues,
            pinOn = settings.pinOn,
            linkShown = settings.linkShown,
        )

        /** PIN fields keep digits only, at most 4. */
        fun pinInput(raw: String): String = raw.filter { it in '0'..'9' }.take(PinHasher.PIN_LENGTH)
    }
}

/** Whether 설정 can be opened right now. */
enum class SettingsAccess { OPEN, NEEDS_PIN, BLOCKED_DRIVING, BLOCKED_TRIP }

/** SPEC trip-core: 설정 opens only parked with no trip running; with the PIN on, only after the PIN. */
object SettingsGate {
    fun access(driving: DrivingState, tripActive: Boolean, pinOn: Boolean, unlocked: Boolean): SettingsAccess = when {
        driving == DrivingState.DRIVING -> SettingsAccess.BLOCKED_DRIVING
        tripActive -> SettingsAccess.BLOCKED_TRIP
        pinOn && !unlocked -> SettingsAccess.NEEDS_PIN
        else -> SettingsAccess.OPEN
    }
}

/** Persistence for [SettingsRepository]; Room in the app, a fake in unit tests. */
interface SettingsStore {
    /** The saved settings, or null if 설정 was never saved. */
    suspend fun current(): Settings?

    suspend fun put(settings: Settings)

    /** The settings as they were when the last trip started (the `SETTINGS_CHANGED` baseline), or null. */
    suspend fun atLastTrip(): Settings?

    suspend fun putAtLastTrip(settings: Settings)

    /** Wrong-PIN bookkeeping for opening 설정, kept so a restart doesn't reset the lockout. */
    suspend fun pinAttempts(): PinAttempts

    suspend fun setPinAttempts(attempts: PinAttempts)
}

/**
 * Owns the saved 설정 (ADR 0015). Saving is refused while a trip runs ([tripActive]), whatever the screen does; the
 * PIN that opens 설정 uses the same lockout as ending a trip ([PinGate], ADR 0006).
 */
class SettingsRepository(
    private val store: SettingsStore,
    private val hasher: PinHasher,
    clock: Clock,
    private val tripActive: () -> Boolean,
) {
    private val mutex = Mutex()
    private val pinGate = PinGate(hasher, clock)
    private val _settings = MutableStateFlow(Settings())

    /** The saved settings; empty until [load] (or a first save). */
    val settings: StateFlow<Settings> = _settings.asStateFlow()

    suspend fun load(): Result<Settings> = mutex.withLock {
        runCatching { (store.current() ?: Settings()).also { _settings.value = it } }
    }

    suspend fun save(draft: SettingsDraft): Result<Settings> = mutex.withLock {
        runCatching {
            check(!tripActive()) { "설정 can't change while a trip runs" }
            val existing = _settings.value
            val next = requireNotNull(draft.toSettings(existing, hasher)) { "settings draft has errors" }
            store.put(next)
            if (next.pinHash != existing.pinHash) store.setPinAttempts(PinAttempts())
            _settings.value = next
            next
        }
    }

    /**
     * Stores Claude's spoken sentence (ADR 0021) if the saved notes are still the ones it was written for; false
     * when they changed meanwhile (the template stays until the next answer).
     */
    suspend fun setSpoken(forNotes: ArrivalInstructions, sentence: String): Result<Boolean> = mutex.withLock {
        runCatching {
            val current = _settings.value
            if (!current.arrival.sameNotes(forNotes) || current.arrival.isEmpty) return@runCatching false
            val next = current.copy(arrival = current.arrival.copy(spoken = sentence))
            store.put(next)
            _settings.value = next
            true
        }
    }

    /** Checks the PIN that opens 설정; with the PIN off it is always accepted. */
    suspend fun unlock(pin: String): Result<PinResult> = mutex.withLock {
        runCatching {
            val hash = _settings.value.pinHash ?: return@runCatching PinResult.Accepted(PinAttempts())
            pinGate.check(pin, hash, store.pinAttempts()).also { store.setPinAttempts(it.attempts) }
        }
    }

    /** What changed since the last trip start (nothing before the first trip). */
    suspend fun changesSinceLastTrip(): Result<Set<SettingsField>> = mutex.withLock {
        runCatching { _settings.value.changedSince(store.atLastTrip()) }
    }

    /**
     * A trip started: returns what changed since the last trip start and makes the current settings the baseline
     * for the next `SETTINGS_CHANGED`.
     */
    suspend fun markTripStarted(): Result<Set<SettingsField>> = mutex.withLock {
        runCatching {
            val current = _settings.value
            current.changedSince(store.atLastTrip()).also { store.putAtLastTrip(current) }
        }
    }
}
