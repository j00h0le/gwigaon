package com.coral.gwigaon.trip.db

import com.coral.gwigaon.trip.ArrivalInstructions
import com.coral.gwigaon.trip.PinAttempts
import com.coral.gwigaon.trip.Settings
import com.coral.gwigaon.trip.SettingsStore
import com.coral.gwigaon.vehicle.Destination
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.Instant

/** [SettingsStore] on Room. Only column mapping lives here; behaviour is in SettingsRepository. */
class RoomSettingsStore(private val db: GwigaonDatabase) : SettingsStore {
    override suspend fun current(): Settings? = db.settings().get(SettingsEntity.CURRENT)?.toSettings()

    override suspend fun put(settings: Settings) {
        // Keep the lockout counters: a save is not a correct PIN.
        val old = db.settings().get(SettingsEntity.CURRENT)
        db.settings().put(settings.toEntity(SettingsEntity.CURRENT, old?.pinFailures ?: 0, old?.pinLockedUntil))
    }

    override suspend fun atLastTrip(): Settings? = db.settings().get(SettingsEntity.AT_LAST_TRIP)?.toSettings()

    override suspend fun putAtLastTrip(settings: Settings) =
        db.settings().put(settings.toEntity(SettingsEntity.AT_LAST_TRIP, 0, null))

    override suspend fun pinAttempts(): PinAttempts = db.settings().get(SettingsEntity.CURRENT)
        ?.let { PinAttempts(it.pinFailures, it.pinLockedUntil?.let(Instant::fromEpochMilliseconds)) }
        ?: PinAttempts()

    override suspend fun setPinAttempts(attempts: PinAttempts) = db.settings()
        .setPinAttempts(SettingsEntity.CURRENT, attempts.failures, attempts.lockedUntil?.toEpochMilliseconds())

    private fun Settings.toEntity(id: Int, failures: Int, lockedUntil: Long?) = SettingsEntity(
        id = id,
        homeLabel = home?.name,
        homeLat = home?.lat,
        homeLng = home?.lng,
        contacts = JsonArray(contacts.map(::JsonPrimitive)).toString(),
        arrivalParking = arrival.parking,
        arrivalEntrance = arrival.entrance,
        arrivalKeyNote = arrival.keyNote,
        arrivalSpoken = arrival.spoken,
        cues = cues,
        pinHash = pinHash,
        pinFailures = failures,
        pinLockedUntil = lockedUntil,
        linkShown = linkShown,
    )

    private fun SettingsEntity.toSettings(): Settings {
        val home = if (homeLabel != null && homeLat != null && homeLng != null) Destination(homeLabel, homeLat, homeLng) else null
        return Settings(
            home = home,
            contacts = Json.parseToJsonElement(contacts).jsonArray.map { it.jsonPrimitive.content },
            arrival = ArrivalInstructions(arrivalParking, arrivalEntrance, arrivalKeyNote, arrivalSpoken),
            cues = cues,
            pinHash = pinHash,
            linkShown = linkShown,
        )
    }
}
