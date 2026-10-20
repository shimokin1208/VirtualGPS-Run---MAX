package com.virtualrun.app.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeoMathTest {

    @Test
    fun samePointHasZeroDistance() {
        assertEquals(0.0, GeoMath.distanceMeters(39.9042, 116.4074, 39.9042, 116.4074), 1e-6)
    }

    @Test
    fun oneDegreeOfLatitudeIsAbout111Kilometers() {
        val distance = GeoMath.distanceMeters(0.0, 0.0, 1.0, 0.0)
        assertTrue("distance=$distance", distance in 110_000.0..112_000.0)
    }

    @Test
    fun distanceIsSymmetric() {
        val forward = GeoMath.distanceMeters(39.9042, 116.4074, 39.9142, 116.4174)
        val backward = GeoMath.distanceMeters(39.9142, 116.4174, 39.9042, 116.4074)
        assertEquals(forward, backward, 1e-6)
        assertTrue("约 1.3km，实际 $forward", forward in 1_000.0..2_000.0)
    }

    @Test
    fun offsetNorthMovesLatitudeOnly() {
        val (lat, lng) = GeoMath.offsetMeters(39.9042, 116.4074, northMeters = 100.0, eastMeters = 0.0)
        assertTrue(lat > 39.9042)
        assertEquals(116.4074, lng, 1e-9)
        // 100 米约 0.000898 度
        assertEquals(0.000898, lat - 39.9042, 5e-5)
    }

    @Test
    fun offsetIsReversibleWithinTolerance() {
        val (lat, lng) = GeoMath.offsetMeters(39.9042, 116.4074, 250.0, -400.0)
        val (backLat, backLng) = GeoMath.offsetMeters(lat, lng, -250.0, 400.0)
        assertEquals(39.9042, backLat, 1e-6)
        assertEquals(116.4074, backLng, 1e-6)
    }

    @Test
    fun latitudeRangeIsValidated() {
        assertTrue(GeoMath.isValidLatLng(39.9, 116.4))
        assertTrue(GeoMath.isValidLatLng(-90.0, 180.0))
        assertFalse(GeoMath.isValidLatLng(91.0, 0.0))
        assertFalse(GeoMath.isValidLatLng(0.0, 181.0))
        assertFalse(GeoMath.isValidLatLng(Double.NaN, 0.0))
    }

    @Test
    fun formatLatLngUsesDotDecimalSeparatorRegardlessOfLocale() {
        assertEquals("39.904200, 116.407400", GeoMath.formatLatLng(39.9042, 116.4074))
        assertEquals("39.90, 116.41", GeoMath.formatLatLng(39.9042, 116.4074, digits = 2))
    }
}
