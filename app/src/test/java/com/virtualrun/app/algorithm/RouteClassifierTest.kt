package com.virtualrun.app.algorithm

import com.google.android.gms.maps.model.LatLng
import com.virtualrun.app.persist.RoutePointType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteClassifierTest {

    @Test
    fun emptyRouteHasNoTypes() {
        assertEquals(emptyList<RoutePointType>(), RouteClassifier.classify(emptyList()))
    }

    @Test
    fun singlePointIsStart() {
        assertEquals(
            listOf(RoutePointType.START),
            RouteClassifier.classify(listOf(LatLng(0.0, 0.0)))
        )
    }

    @Test
    fun twoPointsAreStartAndEnd() {
        val points = listOf(LatLng(0.0, 0.0), LatLng(0.0, 0.001))
        assertEquals(
            listOf(RoutePointType.START, RoutePointType.END),
            RouteClassifier.classify(points)
        )
    }

    @Test
    fun middlePointsAreVia() {
        val points = listOf(
            LatLng(0.0, 0.0),
            LatLng(0.0, 0.001),
            LatLng(0.0, 0.002),
            LatLng(0.0, 0.003)
        )
        assertEquals(
            listOf(
                RoutePointType.START,
                RoutePointType.VIA,
                RoutePointType.VIA,
                RoutePointType.END
            ),
            RouteClassifier.classify(points)
        )
    }

    @Test
    fun endsAtStartDetectsExplicitLoop() {
        val start = LatLng(39.9042, 116.4074)
        val open = listOf(start, LatLng(39.9046, 116.4080), LatLng(39.9050, 116.4070))
        assertFalse(RouteClassifier.endsAtStart(open))
        assertTrue(RouteClassifier.endsAtStart(open + start))
    }
}
