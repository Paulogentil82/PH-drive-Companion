package com.phdrive.companion.util

import com.phdrive.companion.model.LocalTripPoint
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

object LocationUtils {

    private const val EARTH_RADIUS_KM = 6371.0

    fun calculateHaversineDistanceKm(
        lat1: Double,
        lon1: Double,
        lat2: Double,
        lon2: Double
    ): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val rLat1 = Math.toRadians(lat1)
        val rLat2 = Math.toRadians(lat2)

        val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(rLat1) * cos(rLat2) *
                sin(dLon / 2) * sin(dLon / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return EARTH_RADIUS_KM * c
    }

    fun calculateTotalDistanceKm(points: List<LocalTripPoint>): Double {
        if (points.size < 2) return 0.0
        var total = 0.0
        val sorted = points.sortedBy { it.sequenceNumber }
        for (i in 0 until sorted.size - 1) {
            val p1 = sorted[i]
            val p2 = sorted[i + 1]
            total += calculateHaversineDistanceKm(p1.latitude, p1.longitude, p2.latitude, p2.longitude)
        }
        return total
    }

    fun formatIso8601Utc(epochMillis: Long): String {
        val df = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        df.timeZone = TimeZone.getTimeZone("UTC")
        return df.format(Date(epochMillis))
    }

    fun speedMpsToKmh(speedMps: Float): Double {
        return (speedMps * 3.6)
    }

    fun normalizeBearing(bearing: Float): Double {
        var b = bearing.toDouble() % 360.0
        if (b < 0) b += 360.0
        return b
    }

    fun isValidCoordinates(lat: Double, lon: Double): Boolean {
        return lat in -90.0..90.0 && lon in -180.0..180.0 && (lat != 0.0 || lon != 0.0)
    }
}
