package com.coral.gwigaon.trip

import com.coral.gwigaon.sync.ShareToken
import com.coral.gwigaon.vehicle.Destination
import com.coral.gwigaon.vehicle.GeoFix
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** Trip ends that belong to a trip without a PIN; logged only through [TripSessionRepository.endWithoutPin]. */
private val NO_PIN_ENDS = setOf(EndReason.ENDED_AT_ARRIVAL, EndReason.ENDED_EARLY)

/** The live trip: what's stored, and where the state machine says it is. */
/** The running trip, its state, and its event log so far in seq order (for the driver panel's timeline). */
data class ActiveTrip(val trip: StoredTrip, val state: TripState, val log: List<EventRecord> = emptyList())

data class TripSetup(
    val destination: Destination,
    val contactLabel: String,
    /** A PIN typed for this trip (hashed on start); empty when [pinHash] is given or the trip has no PIN. */
    val pin: String = "",
    val arrival: ArrivalInstructions = ArrivalInstructions(),
    /** An already hashed PIN from 설정 (ADR 0015); wins over [pin]. */
    val pinHash: String? = null,
) {
    /** Never prints the PIN, the destination, the contact label or the arrival notes, so logging a setup can't leak them. */
    override fun toString(): String =
        "TripSetup(pinValid=${PinHasher.isValidPin(pin)}, pinHash=${pinHash != null}, arrivalGiven=${!arrival.isEmpty})"

    companion object {
        /** The trip 설정 describes: home, every 보호자, the arrival notes and the PIN hash (or none). */
        fun from(settings: Settings): TripSetup = TripSetup(
            destination = checkNotNull(settings.home) { "settings have no home" },
            contactLabel = settings.contacts.joinToString(", "),
            arrival = settings.arrival,
            pinHash = settings.pinHash,
        )
    }
}

/**
 * Owns the one active trip (SPEC trip-core). Every event goes through [TripStateMachine] before it is stored,
 * under one lock, so rules, the SOS button and the service can log concurrently without breaking the order.
 */
class TripSessionRepository(
    private val store: TripStore,
    private val hasher: PinHasher,
    private val clock: Clock,
    private val fixMaxAge: Duration = MonitoringConfig.DEFAULT.fixMaxAge,
    private val newShareToken: () -> String = ShareToken::generate,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    private val mutex = Mutex()
    private val pinGate = PinGate(hasher, clock)
    private val _active = MutableStateFlow<ActiveTrip?>(null)
    private var resumeChecked = false

    /** null when no trip is running (none started, or the last one ended). */
    val active: StateFlow<ActiveTrip?> = _active.asStateFlow()

    private val _lastEnded = MutableStateFlow<StoredTrip?>(null)

    /**
     * The trip that ended in this process and whose journey report hasn't been closed yet (SPEC journey-report),
     * or null. Kept in memory only: after a restart the app opens on setup, not on an old report.
     */
    val lastEnded: StateFlow<StoredTrip?> = _lastEnded.asStateFlow()

    /** The report has been read: the app goes back to setup. */
    fun closeReport() {
        _lastEnded.value = null
    }

    private val _latestEnded = MutableStateFlow<StoredTrip?>(null)

    /** The newest ended trip, stored or from this process, for home's 운행 기록 link; null = none yet. */
    val latestEnded: StateFlow<StoredTrip?> = _latestEnded.asStateFlow()

    /** Reads [latestEnded] from the store (on start; a trip ending here updates it directly). */
    suspend fun loadLatestEnded() {
        runCatching { store.latestEnded() }.getOrNull()?.let { stored ->
            _latestEnded.update { mine -> if (mine == null || stored.startedAt > mine.startedAt) stored else mine }
        }
    }

    /** Home's 운행 기록: opens the newest ended trip's report again, closed the same way as after a trip. */
    fun openLatestReport() {
        if (_active.value == null) _latestEnded.value?.let { _lastEnded.value = it }
    }

    @Volatile private var latestFix: Pair<GeoFix, Instant>? = null

    /**
     * Latest GPS fix, attached to the events logged after it. Cleared when a trip starts (location is collected only
     * during a trip, so a held fix is where an earlier trip was) and null once older than [fixMaxAge] (counted from
     * when it arrived), so a stale position is never stored as where this trip is.
     */
    var lastFix: GeoFix?
        get() = latestFix?.takeIf { (_, at) -> clock.now() - at <= fixMaxAge }?.first
        set(value) {
            latestFix = value?.let { it to clock.now() }
        }

    /**
     * Starts a trip: `TRIP_STARTED {source}`, then `SETTINGS_CHANGED {fields}` when [settingsChanged] isn't empty.
     * A typed [TripSetup.pin] must be valid; with neither a PIN nor a hash the trip has no PIN (ADR 0015).
     */
    suspend fun start(
        setup: TripSetup,
        source: StartSource = StartSource.BUTTON,
        settingsChanged: Set<SettingsField> = emptySet(),
    ): Result<ActiveTrip> = mutex.withLock {
        runCatching {
            resumeLocked().getOrThrow()
            check(_active.value == null) { "a trip is already active" }
            require(setup.pinHash != null || setup.pin.isEmpty() || PinHasher.isValidPin(setup.pin)) {
                "PIN must be ${PinHasher.PIN_LENGTH} digits"
            }
            require(setup.destination.name.isNotBlank()) { "destination is empty" }
            val now = clock.now()
            val trip = StoredTrip(
                id = newId(),
                status = TripStatus.ACTIVE,
                destination = setup.destination,
                contactLabel = setup.contactLabel.trim(),
                pinHash = setup.pinHash ?: setup.pin.takeIf { it.isNotEmpty() }?.let(hasher::hash).orEmpty(),
                pinAttempts = PinAttempts(),
                startedAt = now,
                endedAt = null,
                lastSeenAt = now,
                arrival = setup.arrival.let { it.copy(parking = it.parking.trim(), entrance = it.entrance.trim(), keyNote = it.keyNote.trim()) },
                shareToken = newShareToken(),
            )
            store.insert(trip)
            _active.value = ActiveTrip(trip, TripState.IDLE)
            // Location is only collected during a trip, so any fix held now is from an earlier one.
            latestFix = null
            appendLocked(TripEvent.TripStarted(source)).onFailure { e ->
                // Without TRIP_STARTED the trip never began: don't leave it occupying the slot in IDLE.
                _active.value = null
                runCatching { store.setStatus(trip.id, TripStatus.ENDED, now) }
                throw e
            }
            // What changed in 설정 is for the 보호자 to know; the trip runs even if this one line can't be stored.
            if (settingsChanged.isNotEmpty()) {
                val fields = settingsChanged.sortedBy { it.ordinal }
                appendLocked(TripEvent.SettingsChanged(fields, TripTexts.settingsChanged(fields)))
            }
            checkNotNull(_active.value)
        }
    }

    /**
     * Logs [event] if the state machine allows it; a rejected event is not stored. The passenger ending the trip
     * is refused here: it needs the PIN, so it only goes through [endByPassenger].
     */
    suspend fun log(event: TripEvent): Result<EventRecord> {
        if (event is TripEvent.TripEnded && event.endReason == EndReason.ENDED_BY_PASSENGER) {
            return Result.failure(IllegalArgumentException("ended_by_passenger needs the PIN: use endByPassenger"))
        }
        if (event is TripEvent.TripEnded && event.endReason in NO_PIN_ENDS) {
            return Result.failure(IllegalArgumentException("${event.endReason.wire} follows the end rule: use endWithoutPin"))
        }
        return mutex.withLock { appendLocked(event) }
    }

    /**
     * Once per process: picks up a trip that was running when the process died, logs the gap
     * (`MONITORING_INTERRUPTED` at the last moment it was seen, with `gap_s`) and `MONITORING_RESUMED` now.
     * Later calls just return the current trip.
     */
    suspend fun resumeIfActive(): Result<ActiveTrip?> = mutex.withLock { resumeLocked() }

    /** A trip's stored log in seq order (for monitors rebuilding their state after a restart). */
    suspend fun events(tripId: String): Result<List<EventRecord>> = store.events(tripId)

    /** Past destinations for the setup screen's recents. */
    suspend fun recentDestinations(limit: Int = 5): List<Destination> = store.recentDestinations(limit)

    /** Records that monitoring is alive, so a later restart measures the right gap. */
    suspend fun heartbeat() = mutex.withLock {
        val current = _active.value ?: return@withLock
        val now = clock.now()
        store.touch(current.trip.id, now)
        _active.value = current.copy(trip = current.trip.copy(lastSeenAt = now))
    }

    /** Checks the passenger PIN with the 5-try lockout; attempts are stored so a restart doesn't reset them. */
    suspend fun checkPin(pin: String): Result<PinResult> = mutex.withLock { runCatching { checkPinLocked(pin) } }

    /**
     * Ends the trip as `ended_by_passenger`, only with the right PIN (SPEC: ending the trip needs the PIN).
     * A wrong PIN or the lockout comes back as the [PinResult] and the trip keeps running. Fails if the trip
     * can't end now (e.g. the safe-arrival check is open).
     */
    suspend fun endByPassenger(pin: String): Result<PinResult> = mutex.withLock {
        runCatching {
            val result = checkPinLocked(pin)
            if (result is PinResult.Accepted) {
                appendLocked(TripEvent.TripEnded(EndReason.ENDED_BY_PASSENGER, TripTexts.ENDED_BY_PASSENGER)).getOrThrow()
            }
            result
        }
    }

    /**
     * Closes the open safe-arrival check with the passenger's PIN (`SAFE_ARRIVAL_CONFIRMED`, `passenger_pin`).
     * Same lockout as [endByPassenger]. Fails if no check is open.
     */
    suspend fun confirmSafeArrivalByPin(pin: String, reason: String): Result<PinResult> = mutex.withLock {
        runCatching {
            check(_active.value?.state?.status == TripStatus.SAFE_ARRIVAL_CHECK) { "no safe-arrival check is open" }
            val result = checkPinLocked(pin)
            if (result is PinResult.Accepted) {
                appendLocked(TripEvent.SafeArrivalConfirmed(SafeArrivalCloser.PASSENGER_PIN, reason)).getOrThrow()
            }
            result
        }
    }

    /** What 운행 종료 does now for the running trip ([TripEndRule]), or null with no trip. */
    fun endOption(parked: Boolean): EndOption? {
        val current = _active.value ?: return null
        return TripEndRule.option(current.state, current.trip.pinOn, parked, TripEndRule.remainingM(lastFix, current.trip.destination))
    }

    /**
     * Ends a trip that has no PIN, by the arrival rule (ADR 0015): `ended_at_arrival` when [EndOption.Plain],
     * `ended_early` with the distance left when [EndOption.Hold] (the screen asks for the hold). Refused while
     * driving, with the safe-arrival check open, and on a trip that has a PIN.
     */
    suspend fun endWithoutPin(parked: Boolean): Result<EndReason> = mutex.withLock {
        runCatching {
            val current = checkNotNull(_active.value) { "no active trip" }
            val remaining = TripEndRule.remainingM(lastFix, current.trip.destination)
            val event = when (val option = TripEndRule.option(current.state, current.trip.pinOn, parked, remaining)) {
                EndOption.Plain -> TripEvent.TripEnded(EndReason.ENDED_AT_ARRIVAL, TripTexts.ENDED_AT_ARRIVAL)
                is EndOption.Hold -> TripEvent.TripEnded(EndReason.ENDED_EARLY, option.reason)
                else -> throw IllegalStateException("trip can't end without a PIN now: $option")
            }
            appendLocked(event).getOrThrow()
            event.endReason
        }
    }

    private suspend fun checkPinLocked(pin: String): PinResult {
        val current = checkNotNull(_active.value) { "no active trip" }
        check(current.trip.pinOn) { "this trip has no PIN" }
        val result = pinGate.check(pin, current.trip.pinHash, current.trip.pinAttempts)
        store.setPinAttempts(current.trip.id, result.attempts)
        _active.value = current.copy(trip = current.trip.copy(pinAttempts = result.attempts))
        // A new lockout (not a try during one) is reported to the contact (Checkpoint C). The PIN answer stands
        // even if the report can't be stored, so its Result is deliberately not checked.
        if (result is PinResult.LockedOut && result.attempts != current.trip.pinAttempts) {
            val lockedS = ((result.until - clock.now()).inWholeMilliseconds + 999).floorDiv(1000).toInt().coerceAtLeast(1)
            val failures = result.attempts.failures
            appendLocked(TripEvent.PinLockout(failures, lockedS, TripTexts.pinLockout(failures, lockedS.seconds)))
        }
        return result
    }

    private suspend fun resumeLocked(): Result<ActiveTrip?> = runCatching {
        if (resumeChecked) return@runCatching _active.value
        val trip = store.active()
        if (trip == null) {
            resumeChecked = true
            return@runCatching null
        }
        var loaded: List<EventRecord> = emptyList()
        val state = store.events(trip.id).mapCatching { log ->
            loaded = log
            TripStateMachine.replay(log.map { it.event }).getOrThrow()
        }
            .getOrElse { e ->
                // Don't guess the state of a log we can't read, but don't let it block every later trip either:
                // close it (its events stay stored as they are) and report the failure once.
                store.setStatus(trip.id, TripStatus.ENDED, trip.lastSeenAt)
                resumeChecked = true
                throw UnreadableTripLog(trip.id, e)
            }
        resumeChecked = true
        if (state.status == TripStatus.ENDED || state.status == TripStatus.IDLE) {
            // ENDED: died between logging TRIP_ENDED and updating the row. IDLE: died before TRIP_STARTED was
            // logged, so the trip never started. Either way there is nothing to resume.
            store.setStatus(trip.id, TripStatus.ENDED, trip.endedAt ?: trip.lastSeenAt)
            return@runCatching null
        }
        _active.value = ActiveTrip(trip, state, loaded)
        val now = clock.now()
        if (!state.interrupted) {
            val gap = now - trip.lastSeenAt
            appendLocked(TripEvent.MonitoringInterrupted(TripTexts.gapSeconds(gap), TripTexts.interrupted(gap)), at = trip.lastSeenAt)
                .getOrThrow()
        }
        appendLocked(TripEvent.MonitoringResumed(TripTexts.RESUMED), at = now).getOrThrow()
        _active.value
    }

    private suspend fun appendLocked(event: TripEvent, at: Instant = clock.now()): Result<EventRecord> {
        val current = _active.value ?: return Result.failure(IllegalStateException("no active trip"))
        val next = when (val t = TripStateMachine.apply(current.state, event)) {
            is Transition.Rejected -> return Result.failure(IllegalStateException(t.why))
            is Transition.Accepted -> t.state
        }
        return runCatching {
            val fix = lastFix
            val record = store.append(current.trip.id, at, fix?.lat, fix?.lng, event)
            val seenAt = maxOf(current.trip.lastSeenAt, at)
            store.touch(current.trip.id, seenAt)
            var trip = current.trip.copy(lastSeenAt = seenAt)
            if (next.status != current.state.status && next.status != TripStatus.IDLE) {
                val endedAt = at.takeIf { next.status == TripStatus.ENDED }
                store.setStatus(trip.id, next.status, endedAt)
                trip = trip.copy(status = next.status, endedAt = endedAt)
            }
            _active.value = if (next.status == TripStatus.ENDED) null else ActiveTrip(trip, next, current.log + record)
            if (next.status == TripStatus.ENDED) {
                _lastEnded.value = trip
                _latestEnded.value = trip
            }
            record
        }
    }
}

/** The running trip's event log couldn't be read back; the trip was closed instead of resumed. */
class UnreadableTripLog(tripId: String, cause: Throwable) : IllegalStateException("trip $tripId: unreadable event log", cause)
