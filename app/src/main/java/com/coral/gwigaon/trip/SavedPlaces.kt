package com.coral.gwigaon.trip

import com.coral.gwigaon.vehicle.Destination

/**
 * Built-in places for 설정 → 집 주소 (ADR 0002): NaviHelper has no place search and the car has no geocoder,
 * so 집 comes from recents plus this list. The 양재역/강남역 entries match scripts/demo-route.sh.
 */
object SavedPlaces {
    val ALL: List<Destination> = listOf(
        Destination("양재역", 37.48460, 127.03420),
        Destination("강남역", 37.49794, 127.02762),
        Destination("서울역", 37.55468, 126.97060),
        Destination("판교역", 37.39476, 127.11116),
    )
}

/** Preset chips for 설정 → 도착 안내 (SPEC arrival: chips plus short text; `design/d2/proto/settings.html`). */
object ArrivalPresets {
    val PARKING = listOf("지상", "지하 1층", "지하 2층", "지하 3층")
    val ENTRANCE = listOf("정문", "후문")
    val KEY = listOf("승객에게 직접", "차 안에 두기", "경비실에 맡기기")
}

/**
 * Saved places first, then recents that aren't already in the list, so each place shows once. A [current] 집 that is
 * in neither (saved from an older list) leads, so editing 설정 never hides the choice already made.
 */
fun destinationChoices(
    recents: List<Destination>,
    saved: List<Destination> = SavedPlaces.ALL,
    current: Destination? = null,
): List<Destination> {
    val recentFirst = recents.filter { r -> saved.none { it.samePlace(r) } }
    val list = recentFirst + saved
    return if (current != null && list.none { it.samePlace(current) }) listOf(current) + list else list
}

private fun Destination.samePlace(other: Destination) = name == other.name && lat == other.lat && lng == other.lng
