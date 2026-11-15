package com.coral.gwigaon.route

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class LatLng(val lat: Double, val lng: Double)

object Geo {
    private const val EARTH_RADIUS_M = 6_371_000.0

    /** Great-circle (haversine) distance in meters; good to well under a meter at city scale. */
    fun distanceM(a: LatLng, b: LatLng): Double {
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLng = Math.toRadians(b.lng - a.lng)
        val h = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLng / 2) * sin(dLng / 2)
        return 2 * EARTH_RADIUS_M * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }

    /** "420m", "1.2km", "12km" — the way the contact page and seed data write distances. */
    fun formatDistance(meters: Double): String {
        val m = meters.coerceAtLeast(0.0)
        if (m < 1000) return "${(m / 10).toInt() * 10}m"
        val tenthsKm = ((m + 50) / 100).toLong()
        return if (tenthsKm >= 100 || tenthsKm % 10 == 0L) "${tenthsKm / 10}km" else "${tenthsKm / 10}.${tenthsKm % 10}km"
    }
}
