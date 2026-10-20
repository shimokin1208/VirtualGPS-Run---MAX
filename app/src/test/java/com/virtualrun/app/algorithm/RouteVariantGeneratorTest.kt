package com.virtualrun.app.algorithm

import com.google.android.gms.maps.model.LatLng
import com.virtualrun.app.map.GeoMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteVariantGeneratorTest {

    private val a = LatLng(39.9042, 116.4074)
    private val b = LatLng(39.9046, 116.4080)
    private val c = LatLng(39.9050, 116.4070)

    @Test
    fun offsetMovesEveryPointByRequestedDistance() {
        val shifted = RouteVariantGenerator.offset(listOf(a, b, c), northMeters = 100.0, eastMeters = 0.0)

        assertEquals(3, shifted.size)
        shifted.forEachIndexed { index, point ->
            val original = listOf(a, b, c)[index]
            val moved = GeoMath.distanceMeters(
                original.latitude, original.longitude,
                point.latitude, point.longitude
            )
            assertEquals("point $index moved $moved m", 100.0, moved, 1.0)
        }
    }

    @Test
    fun offsetByZeroKeepsCoordinates() {
        val shifted = RouteVariantGenerator.offset(listOf(a, b), 0.0, 0.0)
        assertEquals(a.latitude, shifted[0].latitude, 1e-12)
        assertEquals(b.longitude, shifted[1].longitude, 1e-12)
    }

    @Test
    fun repeatJoinsRouteWithoutDuplicatingJoints() {
        val repeated = RouteVariantGenerator.repeat(listOf(a, b, c), times = 2)
        // 开放路线重复两遍 = 6 个点（第二遍整段追加）
        assertEquals(6, repeated.size)
        assertEquals(a, repeated.first())
        assertEquals(c, repeated.last())
        assertEquals(listOf(a, b, c, a, b, c), repeated)
        repeated.zipWithNext().forEach { (first, second) ->
            assertTrue("zero-length segment", first != second)
        }
    }

    @Test
    fun repeatOnceReturnsInputUnchanged() {
        val route = listOf(a, b, c)
        assertEquals(route, RouteVariantGenerator.repeat(route, times = 1))
        assertEquals(route, RouteVariantGenerator.repeat(route, times = 0))
    }

    @Test
    fun repeatClosedLoopDropsSharedEndpoints() {
        val closed = listOf(a, b, c, a)
        val repeated = RouteVariantGenerator.repeat(closed, times = 2)
        // 4 点 + (第二次跳过开头的 a) 3 点
        assertEquals(7, repeated.size)
        assertEquals(a, repeated.last())
    }

    @Test
    fun shiftStartRotatesOpenRoute() {
        val shifted = RouteVariantGenerator.shiftStart(listOf(a, b, c), startIndex = 1)
        assertEquals(listOf(b, c, a), shifted)
    }

    @Test
    fun shiftStartKeepsLoopClosed() {
        val shifted = RouteVariantGenerator.shiftStart(listOf(a, b, c, a), startIndex = 1)
        assertEquals(listOf(b, c, a, b), shifted)
        assertTrue(RouteRules.isClosed(shifted))
    }

    @Test
    fun shiftStartWithInvalidIndexReturnsInput() {
        val route = listOf(a, b, c)
        assertEquals(route, RouteVariantGenerator.shiftStart(route, startIndex = 0))
        assertEquals(route, RouteVariantGenerator.shiftStart(route, startIndex = 99))
    }

    @Test
    fun parallelVariantsAreSpacedAndDoNotOverlap() {
        val variants = RouteVariantGenerator.parallelVariants(listOf(a, b), count = 3, spacingMeters = 50.0)

        assertEquals(3, variants.size)
        assertEquals(a, variants[0].first())
        val gap = GeoMath.distanceMeters(
            variants[0][0].latitude, variants[0][0].longitude,
            variants[1][0].latitude, variants[1][0].longitude
        )
        assertEquals(50.0, gap, 1.0)
    }

    @Test
    fun parallelVariantsWithZeroCountIsEmpty() {
        assertTrue(RouteVariantGenerator.parallelVariants(listOf(a, b), 0, 10.0).isEmpty())
    }
}
