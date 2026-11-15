package com.coral.gwigaon.trip

import com.coral.gwigaon.vehicle.Destination
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 설정 → 집 주소 choices and 도착 안내 presets (moved from the old setup form's tests, D3c). */
class SavedPlacesTest {
    @Test
    fun choicesPutRecentsFirstWithoutDuplicates() {
        val office = Destination("회사", 37.4, 127.1)
        val choices = destinationChoices(listOf(office, SavedPlaces.ALL[0]))
        assertEquals(listOf(office) + SavedPlaces.ALL, choices)
        assertEquals(SavedPlaces.ALL, destinationChoices(emptyList()))
    }

    @Test
    fun aSavedHomeOutsideTheListStaysChoosable() {
        val old = Destination("옛집", 37.5, 127.0)
        assertEquals(listOf(old) + SavedPlaces.ALL, destinationChoices(emptyList(), current = old))
        assertEquals(SavedPlaces.ALL, destinationChoices(emptyList(), current = SavedPlaces.ALL[1]))
    }

    @Test
    fun demoDestinationMatchesTheDemoRoute() {
        // scripts/demo-route.sh DEST_LAT/DEST_LNG
        assertEquals(Destination("양재역", 37.48460, 127.03420), SavedPlaces.ALL.first())
        assertTrue(ArrivalPresets.PARKING.isNotEmpty() && ArrivalPresets.KEY.isNotEmpty())
        assertTrue(ArrivalPresets.ENTRANCE.isNotEmpty())
    }
}
