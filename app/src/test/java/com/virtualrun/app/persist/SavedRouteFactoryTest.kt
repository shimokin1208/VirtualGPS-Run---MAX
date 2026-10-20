package com.virtualrun.app.persist

import com.google.android.gms.maps.model.LatLng
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SavedRouteFactoryTest {

    @Test
    fun createAssignsOrderAndTypes() {
        val points = listOf(
            LatLng(39.9042, 116.4074),
            LatLng(39.9046, 116.4080),
            LatLng(39.9050, 116.4070)
        )
        val route = SavedRouteFactory.create("测试", points, isLoop = false, nowMillis = 123L)

        assertEquals("测试", route.name)
        assertEquals(false, route.isLoop)
        assertEquals(123L, route.savedAtMillis)
        assertEquals(listOf(0, 1, 2), route.points.map { it.order })
        assertEquals(
            listOf(RoutePointType.START, RoutePointType.VIA, RoutePointType.END),
            route.points.map { it.type }
        )
    }

    @Test
    fun createPreservesCoordinates() {
        val points = listOf(LatLng(39.9042, 116.4074), LatLng(39.9050, 116.4070))
        val route = SavedRouteFactory.create("x", points, isLoop = false, nowMillis = 0L)

        assertEquals(39.9042, route.points[0].lat, 1e-12)
        assertEquals(116.4074, route.points[0].lng, 1e-12)
        assertEquals(39.9050, route.points[1].lat, 1e-12)
        assertEquals(116.4070, route.points[1].lng, 1e-12)
    }

    @Test
    fun toLatLngListFollowsSavedOrder() {
        val route = SavedRoute(
            name = "x",
            points = listOf(
                SavedPoint(2, 3.0, 3.0, RoutePointType.END),
                SavedPoint(0, 1.0, 1.0, RoutePointType.START),
                SavedPoint(1, 2.0, 2.0, RoutePointType.VIA)
            ),
            isLoop = false,
            savedAtMillis = 0L
        )
        val restored = SavedRouteFactory.toLatLngList(route)
        assertEquals(3, restored.size)
        assertEquals(1.0, restored[0].latitude, 1e-12)
        assertEquals(2.0, restored[1].latitude, 1e-12)
        assertEquals(3.0, restored[2].latitude, 1e-12)
    }

    @Test
    fun emptyRouteProducesEmptyList() {
        val route = SavedRoute.empty("x", 0L)
        assertTrue(SavedRouteFactory.toLatLngList(route).isEmpty())
    }
}
