package com.virtualrun.app.persist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteCodecTest {

    private fun sampleRoute() = SavedRoute(
        name = "学校操场",
        points = listOf(
            SavedPoint(0, 39.904200, 116.407400, RoutePointType.START),
            SavedPoint(1, 39.904600, 116.408000, RoutePointType.VIA),
            SavedPoint(2, 39.905000, 116.407000, RoutePointType.END)
        ),
        isLoop = false,
        savedAtMillis = 1_700_000_000_000L
    )

    @Test
    fun roundTripPreservesEverythingExceptSavedAt() {
        val original = sampleRoute()
        val decoded = requireNotNull(RouteCodec.decode(RouteCodec.encode(original)))

        assertEquals(original.name, decoded.name)
        assertEquals(original.points, decoded.points)
        assertFalse(decoded.isLoop)
        assertEquals(3, decoded.points.size)
        assertEquals(listOf(0, 1, 2), decoded.points.map { it.order })
        assertEquals(
            listOf(RoutePointType.START, RoutePointType.VIA, RoutePointType.END),
            decoded.points.map { it.type }
        )
    }

    @Test
    fun emptyRouteRoundTrips() {
        val empty = SavedRoute("空路线", emptyList(), false, 0L)
        val decoded = requireNotNull(RouteCodec.decode(RouteCodec.encode(empty)))

        assertEquals("空路线", decoded.name)
        assertEquals(0, decoded.points.size)
    }

    @Test
    fun loopFlagIsPreserved() {
        val closed = sampleRoute().copy(isLoop = true)
        val decoded = requireNotNull(RouteCodec.decode(RouteCodec.encode(closed)))
        assertTrue(decoded.isLoop)
    }

    @Test
    fun specialCharactersInNameSurviveRoundTrip() {
        val tricky = sampleRoute().copy(name = "A|B\\C\nD\rE")
        val decoded = requireNotNull(RouteCodec.decode(RouteCodec.encode(tricky)))
        assertEquals("A|B\\C\nD\rE", decoded.name)
    }

    @Test
    fun nullOrBlankReturnsNull() {
        assertNull(RouteCodec.decode(null))
        assertNull(RouteCodec.decode(""))
        assertNull(RouteCodec.decode("   \n  "))
    }

    @Test
    fun unknownVersionReturnsNull() {
        assertNull(RouteCodec.decode("V2|loop=0|count=0|name=x"))
    }

    @Test
    fun declaredCountMismatchReturnsNull() {
        val text = "V1|loop=0|count=3|name=x\n0|START|39.9|116.4\n1|END|39.91|116.41"
        assertNull(RouteCodec.decode(text))
    }

    @Test
    fun unknownPointTypeReturnsNull() {
        assertNull(RouteCodec.decode("V1|loop=0|count=1|name=x\n0|MIDDLE|39.9|116.4"))
    }

    @Test
    fun invalidCoordinateReturnsNull() {
        assertNull(RouteCodec.decode("V1|loop=0|count=1|name=x\n0|START|999.0|116.4"))
        assertNull(RouteCodec.decode("V1|loop=0|count=1|name=x\n0|START|39.9|999.0"))
    }

    @Test
    fun malformedPointLineReturnsNull() {
        assertNull(RouteCodec.decode("V1|loop=0|count=1|name=x\n0|START|39.9"))
        assertNull(RouteCodec.decode("V1|loop=0|count=1|name=x\nnot-a-number|START|39.9|116.4"))
    }

    @Test
    fun outOfOrderPointsAreSortedOnDecode() {
        val text = "V1|loop=0|count=2|name=x\n1|END|39.91|116.41\n0|START|39.9|116.4"
        val decoded = requireNotNull(RouteCodec.decode(text))

        assertEquals(listOf(0, 1), decoded.points.map { it.order })
        assertEquals(39.9, decoded.points.first().lat, 1e-9)
    }

    @Test
    fun extraBlankLinesAreIgnored() {
        val text = "V1|loop=0|count=1|name=x\n\n0|START|39.9|116.4\n\n"
        val decoded = requireNotNull(RouteCodec.decode(text))
        assertEquals(1, decoded.points.size)
    }
}
