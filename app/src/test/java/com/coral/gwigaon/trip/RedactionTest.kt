package com.coral.gwigaon.trip

import com.coral.gwigaon.trip.db.TripEntity
import com.coral.gwigaon.vehicle.Destination
import org.junit.Assert.assertFalse
import org.junit.Test

/** Q3: printing a setup or a stored trip (a log line, an exception message) must not leak the secrets in it. */
class RedactionTest {
    private val arrival = ArrivalInstructions("지하 2층 B구역", "101동 정문", "현관 비번 4711")
    private val secrets = listOf("2580", "언니", "우리집", "4711", "지하 2층", "101동", "pbkdf2")

    @Test
    fun tripSetupHidesPinContactDestinationAndNotes() {
        assertNoSecrets(TripSetup(Destination("우리집", 37.5, 127.0), "언니", "2580", arrival).toString())
    }

    @Test
    fun arrivalInstructionsHideTheirText() {
        assertNoSecrets(arrival.toString())
    }

    @Test
    fun tripEntityHidesPinHashContactAndNotes() {
        val entity = TripEntity(
            id = "t1",
            status = "active",
            destinationLabel = "우리집",
            destinationLat = 37.5,
            destinationLng = 127.0,
            contactLabel = "언니",
            pinHash = "pbkdf2-sha256\$1000\$c2FsdA\$aGFzaA",
            startedAt = 0,
            lastSeenAt = 0,
            arrivalParking = arrival.parking,
            arrivalEntrance = arrival.entrance,
            arrivalKeyNote = arrival.keyNote,
        )
        assertNoSecrets(entity.toString())
    }

    private fun assertNoSecrets(printed: String) {
        secrets.forEach { assertFalse("'$it' in $printed", printed.contains(it)) }
    }
}
