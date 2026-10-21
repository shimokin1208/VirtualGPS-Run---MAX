package com.virtualrun.app.algorithm

import com.google.android.gms.maps.model.LatLng
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 锁住「不按距离自动闭环」这条规则。
 *
 * 这类回归测试存在的意义：早期实现会在新点距离起点 < 25m 时自动闭环，
 * 导致用户想加一个靠近起点的途经点时路线被意外闭合成环。
 */
class RouteRulesTest {

    private fun point(lat: Double, lng: Double) = LatLng(lat, lng)

    @Test
    fun appendNeverAutoClosesEvenWhenNewPointIsVeryCloseToStart() {
        val start = point(39.9042, 116.4074)
        // 与起点相距约 1 米
        val almostStart = point(39.904209, 116.4074)

        val route = mutableListOf(point(39.9042, 116.4074), point(39.9046, 116.4080), point(39.9050, 116.4070))
        val updated = RouteRules.append(route, almostStart)

        assertEquals(4, updated.size)
        assertEquals(almostStart, updated.last())
        assertFalse("追加点位不应产生闭环", RouteRules.isClosed(updated))
    }

    @Test
    fun appendKeepsUserSelectionOrder() {
        val a = point(39.9042, 116.4074)
        val b = point(39.9046, 116.4080)
        val c = point(39.9050, 116.4070)

        var route = emptyList<LatLng>()
        route = RouteRules.append(route, a)
        route = RouteRules.append(route, b)
        route = RouteRules.append(route, c)

        assertEquals(listOf(a, b, c), route)
    }

    @Test
    fun closeLoopRequiresThreePoints() {
        val two = listOf(point(0.0, 0.0), point(0.0, 0.001))
        assertEquals(two, RouteRules.closeLoop(two))
        assertFalse(RouteRules.canCloseLoop(two))
    }

    @Test
    fun closeLoopAppendsStartPointOnce() {
        val start = point(39.9042, 116.4074)
        val route = listOf(start, point(39.9046, 116.4080), point(39.9050, 116.4070))

        val closed = RouteRules.closeLoop(route)

        assertEquals(4, closed.size)
        assertEquals(start, closed.last())
        assertTrue(RouteRules.isClosed(closed))
        // 幂等：再次闭环不会继续变长
        assertEquals(closed, RouteRules.closeLoop(closed))
    }

    @Test
    fun removeAtDropsOnlyTheRequestedIndex() {
        val a = point(0.0, 0.0)
        val b = point(0.0, 0.001)
        val c = point(0.0, 0.002)
        val route = listOf(a, b, c)

        assertEquals(listOf(a, c), RouteRules.removeAt(route, 1))
        assertEquals(listOf(b, c), RouteRules.removeAt(route, 0))
        assertEquals(route, RouteRules.removeAt(route, 9))
        assertEquals(route, RouteRules.removeAt(route, -1))
    }

    @Test
    fun isClosedIsFalseForEmptyOrSinglePointRoutes() {
        assertFalse(RouteRules.isClosed(emptyList()))
        assertFalse(RouteRules.isClosed(listOf(point(0.0, 0.0))))
    }

    @Test
    fun movePointReplacesOnlyTheRequestedIndex() {
        val a = point(0.0, 0.0)
        val b = point(0.0, 0.001)
        val c = point(0.0, 0.002)
        val moved = point(0.0, 0.0015)

        assertEquals(listOf(a, moved, c), RouteRules.movePoint(listOf(a, b, c), 1, moved))
        assertEquals(listOf(a, b, c), RouteRules.movePoint(listOf(a, b, c), 9, moved))
        assertEquals(listOf(a, b, c), RouteRules.movePoint(listOf(a, b, c), -1, moved))
    }

    @Test
    fun movePointOnClosedLoopKeepsBothEndsTogether() {
        val start = point(39.9042, 116.4074)
        val via = point(39.9046, 116.4080)
        val end = point(39.9050, 116.4070)
        val closed = RouteRules.closeLoop(listOf(start, via, end))
        val shifted = point(39.9043, 116.4075)

        val fromStart = RouteRules.movePoint(closed, 0, shifted)
        assertEquals(shifted, fromStart.first())
        assertEquals(shifted, fromStart.last())
        assertEquals(via, fromStart[1])
        assertTrue(RouteRules.isClosed(fromStart))

        val fromDuplicateEnd = RouteRules.movePoint(closed, closed.lastIndex, shifted)
        assertEquals(fromStart, fromDuplicateEnd)

        val viaMoved = point(39.9060, 116.4090)
        val movedVia = RouteRules.movePoint(closed, 1, viaMoved)
        assertEquals(start, movedVia.first())
        assertEquals(start, movedVia.last())
        assertEquals(viaMoved, movedVia[1])
        assertTrue(RouteRules.isClosed(movedVia))
    }

    @Test
    fun reorderMovesPointAndKeepsSize() {
        val a = point(0.0, 0.0)
        val b = point(0.0, 0.001)
        val c = point(0.0, 0.002)

        val moved = RouteRules.reorder(listOf(a, b, c), fromIndex = 2, toIndex = 0)
        assertEquals(listOf(c, a, b), moved)
    }
}
