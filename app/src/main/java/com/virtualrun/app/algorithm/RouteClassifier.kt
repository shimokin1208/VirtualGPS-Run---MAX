package com.virtualrun.app.algorithm

import com.google.android.gms.maps.model.LatLng
import com.virtualrun.app.persist.RoutePointType

/**
 * 根据点位顺序推断每个点的类型：起点 / 途经点 / 终点。
 *
 * 顺序即用户选点顺序，不做任何重排或自动闭环。
 */
object RouteClassifier {

    fun classify(points: List<LatLng>): List<RoutePointType> = when {
        points.isEmpty() -> emptyList()
        points.size == 1 -> listOf(RoutePointType.START)
        else -> List(points.size) { index ->
            when (index) {
                0 -> RoutePointType.START
                points.lastIndex -> RoutePointType.END
                else -> RoutePointType.VIA
            }
        }
    }

    /** 终点是否就是起点（用户显式闭环后的状态）。 */
    fun endsAtStart(points: List<LatLng>): Boolean = RouteRules.isClosed(points)
}
