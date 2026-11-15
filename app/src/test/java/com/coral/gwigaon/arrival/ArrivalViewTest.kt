package com.coral.gwigaon.arrival

import com.coral.gwigaon.trip.ActiveTrip
import com.coral.gwigaon.trip.ArrivalInstructions
import com.coral.gwigaon.trip.EventRecord
import com.coral.gwigaon.trip.PanelNoteKind
import com.coral.gwigaon.trip.PinAttempts
import com.coral.gwigaon.trip.SafeArrivalCloser
import com.coral.gwigaon.trip.StartSource
import com.coral.gwigaon.trip.StoredTrip
import com.coral.gwigaon.trip.TripEvent
import com.coral.gwigaon.trip.TripStateMachine
import com.coral.gwigaon.trip.TripStatus
import com.coral.gwigaon.vehicle.Destination
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** D4 도착 and the count-up wait (ADR 0020) from the trip and its log. */
class ArrivalViewTest {
    private val zone = ZoneId.of("Asia/Seoul")
    private val t0 = Instant.parse("2026-11-13T16:00:00Z") // 01:00 in Seoul
    private val home = Destination("역삼동 · 집", 37.500, 127.036)
    private val notes = ArrivalInstructions("지하 2층 B구역", "101동 출입구", "승객에게 직접")
    private val delay = 3.minutes

    private fun trip(pinHash: String = "", arrival: ArrivalInstructions = notes) = StoredTrip(
        id = "t1", status = TripStatus.ACTIVE, destination = home, contactLabel = "엄마, 동생", pinHash = pinHash,
        pinAttempts = PinAttempts(), startedAt = t0, endedAt = null, lastSeenAt = t0, arrival = arrival,
    )

    private var seq = 0
    private fun at(d: Duration, event: TripEvent) = EventRecord(++seq, t0 + d, null, null, event)

    private fun active(vararg log: EventRecord, pinHash: String = "", arrival: ArrivalInstructions = notes): ActiveTrip {
        val state = TripStateMachine.replay(log.map { it.event }).getOrThrow()
        return ActiveTrip(trip(pinHash, arrival), state, log.toList())
    }

    private val started = at(Duration.ZERO, TripEvent.TripStarted(StartSource.BUTTON))
    private val near = at(25.minutes, TripEvent.NearDestination())
    private val arrived = at(31.minutes, TripEvent.Arrived()) // 01:31
    private val opened = at(34.minutes, TripEvent.SafeArrivalCheckOpened()) // 01:34

    private fun view(a: ActiveTrip, now: Duration) = ArrivalRule.build(a, t0 + now, zone, delay)

    @Test
    fun noArrivalScreenBeforeArrivingOrWhileOnlyNear() {
        assertNull(view(active(started), 5.minutes))
        assertNull(view(active(started, near), 26.minutes))
    }

    @Test
    fun arrivedWithoutAPinShowsTheNotesAndThatGettingOutEndsIt() {
        val v = view(active(started, near, arrived), 32.minutes)!!
        assertEquals(ArrivalStage.ARRIVED, v.stage)
        assertEquals("역삼동 · 집 · 01:31 도착", v.subline)
        assertEquals(listOf(PanelNoteKind.ENTRANCE, PanelNoteKind.PARKING, PanelNoteKind.KEY), v.notes.map { it.kind })
        assertEquals("도착했어요", v.title)
        assertEquals("승객이 내리면 운행이 끝나요.", v.howItEnds)
        assertEquals("하차 전에 승객 상태를 한 번 확인해 주세요", v.hint)
        assertTrue(!v.pinOn)
    }

    @Test
    fun arrivedWithAPinSaysThePinEndsItToo() {
        val v = view(active(started, near, arrived, pinHash = "h"), 32.minutes)!!
        assertTrue(v.pinOn)
        assertEquals("PIN을 입력해야 운행이 끝나요", v.howItEnds)
    }

    @Test
    fun theDemoDelayCountsInSeconds() {
        val demoOpened = at(31.minutes + 30.seconds, TripEvent.SafeArrivalCheckOpened())
        val v = ArrivalRule.build(active(started, near, arrived, demoOpened), t0 + 31.minutes + 40.seconds, zone, 30.seconds)!!
        assertEquals("40초째 하차하지 않음", v.title)
    }

    @Test
    fun noNotesNoCard() {
        assertTrue(view(active(started, near, arrived, arrival = ArrivalInstructions()), 32.minutes)!!.notes.isEmpty())
    }

    @Test
    fun theOpenCheckIsACountUpWaitThatNamesWhoWasTold() {
        val v = view(active(started, near, arrived, opened), 35.minutes + 20.seconds)!!
        assertEquals(ArrivalStage.WAITING, v.stage)
        assertEquals("4분째 하차하지 않음", v.title)
        assertEquals("역삼동 · 집 · 01:31 도착", v.subline)
        assertEquals("엄마 · 동생", v.contacts)
        assertEquals("01:34", v.checkOpenedAt)
        assertEquals("승객이 내리면 운행이 끝나요.", v.howItEnds)
        assertNull(v.hint)
        assertTrue(v.notes.isEmpty())
        // It keeps counting while nobody answers.
        assertEquals("12분째 하차하지 않음", view(active(started, near, arrived, opened), 43.minutes)!!.title)
    }

    @Test
    fun onAPinTripTheWaitSaysBothWaysOut() {
        val v = view(active(started, near, arrived, opened, pinHash = "h"), 35.minutes)!!
        assertEquals("승객이 내리거나 PIN을 입력하면 운행이 끝나요.", v.howItEnds)
    }

    @Test
    fun aTripStoredBeforeAdr0020WithADriverConfirmationStillWaits() {
        val confirmed = at(36.minutes, TripEvent.SafeArrivalConfirmed(SafeArrivalCloser.DRIVER_CONFIRMED))
        val v = view(active(started, near, arrived, opened, confirmed), 37.minutes)!!
        assertEquals(ArrivalStage.WAITING, v.stage)
        assertEquals("01:34", v.checkOpenedAt)
    }

    @Test
    fun aSeatEmptyOrPinConfirmationLeavesTheArrivalScreens() {
        for (closer in listOf(SafeArrivalCloser.SEAT_EMPTY, SafeArrivalCloser.PASSENGER_PIN)) {
            val confirmed = at(36.minutes, TripEvent.SafeArrivalConfirmed(closer))
            assertNull(closer.name, view(active(started, near, arrived, opened, confirmed), 37.minutes))
        }
    }

    @Test
    fun seatedAndContactsFormatting() {
        assertEquals("0초", ArrivalRule.seated((-5).seconds))
        assertEquals("59초", ArrivalRule.seated(59.seconds))
        assertEquals("1분", ArrivalRule.seated(119.seconds))
        assertEquals("엄마", ArrivalRule.contacts(" 엄마 ,"))
        assertEquals("", ArrivalRule.contacts(""))
    }

    @Test
    fun replaySpeaksTheNotesOnly() {
        assertEquals(
            "승객이 남긴 안내입니다. 출입문은 101동 출입구, 주차는 지하 2층 B구역, 차 키는 승객에게 직접 부탁드립니다. 감사합니다.",
            ArrivalRule.replay(active(started, near, arrived)),
        )
        assertNull(ArrivalRule.replay(active(started, near, arrived, arrival = ArrivalInstructions())))
    }
}
