package com.virtualrun.app.algorithm

import com.google.android.gms.maps.model.LatLng
import com.virtualrun.app.map.GeoMath

/**
 * 地图测试用的路线变体生成器。
 *
 * 用途仅限于**测试本应用自身的渲染与轨迹播放**（例如验证不同长度/不同位置的路线、
 * 重复播放同一路线、把起点挪到路线中段）。它只是普通的地理坐标变换，
 * 不包含任何针对第三方应用检测机制的规避逻辑。
 */
object RouteVariantGenerator {

    /** 整体平移路线（正北 northMeters，正东 eastMeters，单位米）。 */
    fun offset(points: List<LatLng>, northMeters: Double, eastMeters: Double): List<LatLng> =
        points.map { point ->
            val (lat, lng) = GeoMath.offsetMeters(
                point.latitude, point.longitude, northMeters, eastMeters
            )
            LatLng(lat, lng)
        }

    /**
     * 把同一条路线首尾相接重复 [times] 次，用于「重复测试数据」。
     * 接缝处的重复点会被去掉，避免出现零长度线段。
     */
    fun repeat(points: List<LatLng>, times: Int): List<LatLng> {
        if (points.size < 2 || times <= 1) return points
        val result = ArrayList<LatLng>(points.size * times)
        result.addAll(points)
        repeat(times - 1) {
            val last = result.last()
            val startIndex = if (points.first() == last) 1 else 0
            if (startIndex < points.size) {
                result.addAll(points.subList(startIndex, points.size))
            }
        }
        return result
    }

    /**
     * 把路线旋转成「从第 [startIndex] 个点开始」，其余顺序不变。
     * 已闭环的路线会保持闭环（末尾重新接回新的起点）。
     */
    fun shiftStart(points: List<LatLng>, startIndex: Int): List<LatLng> {
        if (startIndex !in points.indices || startIndex == 0) return points
        val closed = RouteRules.isClosed(points)
        val open = if (closed) points.dropLast(1) else points
        if (startIndex >= open.size) return points
        val rotated = open.subList(startIndex, open.size) + open.subList(0, startIndex)
        return if (closed) rotated + rotated.first() else rotated
    }

    /**
     * 生成 [count] 条平行、互不重叠的路线，作为「不同路线」的测试数据。
     * 第 0 条与原路线完全相同。
     */
    fun parallelVariants(
        points: List<LatLng>,
        count: Int,
        spacingMeters: Double
    ): List<List<LatLng>> {
        if (count <= 0) return emptyList()
        return (0 until count).map { index ->
            if (index == 0) points else offset(points, northMeters = index * spacingMeters, eastMeters = 0.0)
        }
    }
}
