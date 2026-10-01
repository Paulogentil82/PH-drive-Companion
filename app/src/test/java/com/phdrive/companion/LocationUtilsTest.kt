package com.phdrive.companion

import com.phdrive.companion.model.LocalTripPoint
import com.phdrive.companion.util.LocationUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationUtilsTest {

    @Test
    fun testCalculateHaversineDistanceKm() {
        // Distância entre São Paulo (-23.5505, -46.6333) e Rio de Janeiro (-22.9068, -43.1729) ~ 357 km
        val distance = LocationUtils.calculateHaversineDistanceKm(
            -23.5505, -46.6333,
            -22.9068, -43.1729
        )
        assertTrue(distance in 350.0..370.0)
    }

    @Test
    fun testCalculateTotalDistanceKm() {
        val points = listOf(
            LocalTripPoint(
                localId = "1",
                localTripId = "trip1",
                userId = "user1",
                latitude = -23.5505,
                longitude = -46.6333,
                capturedAt = 1000L,
                sequenceNumber = 1
            ),
            LocalTripPoint(
                localId = "2",
                localTripId = "trip1",
                userId = "user1",
                latitude = -22.9068,
                longitude = -43.1729,
                capturedAt = 2000L,
                sequenceNumber = 2
            )
        )
        val total = LocationUtils.calculateTotalDistanceKm(points)
        assertTrue(total in 350.0..370.0)
    }

    @Test
    fun testFormatIso8601Utc() {
        val epoch = 1700000000000L // 2023-11-14T22:13:20.000Z
        val iso = LocationUtils.formatIso8601Utc(epoch)
        assertTrue(iso.contains("T"))
        assertTrue(iso.endsWith("Z"))
    }

    @Test
    fun testSpeedMpsToKmh() {
        val kmh = LocationUtils.speedMpsToKmh(10.0f)
        assertEquals(36.0, kmh, 0.001)
    }

    @Test
    fun testNormalizeBearing() {
        assertEquals(90.0, LocationUtils.normalizeBearing(90.0f), 0.001)
        assertEquals(270.0, LocationUtils.normalizeBearing(-90.0f), 0.001)
    }

    @Test
    fun testIsValidCoordinates() {
        assertTrue(LocationUtils.isValidCoordinates(-23.55, -46.63))
        assertFalse(LocationUtils.isValidCoordinates(0.0, 0.0))
        assertFalse(LocationUtils.isValidCoordinates(100.0, 0.0))
    }
}
