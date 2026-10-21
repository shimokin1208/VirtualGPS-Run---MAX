package com.virtualrun.app.algorithm

import com.google.android.gms.maps.model.LatLng
import com.virtualrun.app.map.GeoMath

/**
 * 路线点位列表的纯函数编辑规则。
 *
 * 这条规则集合刻意与 UI / ViewModel 分离，便于用 JVM 单元测试锁住行为：
 * - **不存在**任何按距离阈值自动闭环的逻辑；
 * - 闭环只能通过 [closeLoop] 显式调用；
 * - 追加点位永远只是追加，不会顺带改变路线拓扑。
 */
object RouteRules {

    /** 判定「已闭环」的距离容差（米）。仅用于避免重复追加起点，不作为自动闭环触发条件。 */
    const val LOOP_EPSILON_METERS = 1.0

    /** 允许闭环所需的最少点数。 */
    const val MIN_POINTS_FOR_LOOP = 3

    /** 最后一个点是否已经回到起点附近。 */
    fun isClosed(points: List<LatLng>): Boolean {
        if (points.size < 2) return false
        return GeoMath.distanceMeters(
            points.first().latitude, points.first().longitude,
            points.last().latitude, points.last().longitude
        ) <= LOOP_EPSILON_METERS
    }

    /** 是否满足闭环前提：至少 3 个点，且尚未闭环。 */
    fun canCloseLoop(points: List<LatLng>): Boolean =
        points.size >= MIN_POINTS_FOR_LOOP && !isClosed(points)

    /**
     * 追加一个点位。
     *
     * 注意：无论新点距离起点多近，这里都只做追加。
     * 早期版本会在「新点距起点 < 25m」时自动闭环，本方法刻意不再包含该行为。
     */
    fun append(points: List<LatLng>, point: LatLng): List<LatLng> = points + point

    /** 删除指定下标的点位；下标非法时原样返回。 */
    fun removeAt(points: List<LatLng>, index: Int): List<LatLng> {
        if (index !in points.indices) return points
        val result = ArrayList<LatLng>(points.size - 1)
        points.forEachIndexed { i, item -> if (i != index) result.add(item) }
        return result
    }

    /**
     * 把某个路径点挪到新坐标。
     *
     * 已闭环的路线首尾是同一个点：拖起点或拖那个重复的终点时，两头一起动，环不会被拽开。
     * 下标非法时原样返回。
     */
    fun movePoint(points: List<LatLng>, index: Int, point: LatLng): List<LatLng> {
        if (index !in points.indices) return points
        val result = ArrayList(points)
        val closed = isClosed(points)
        if (closed && (index == 0 || index == points.lastIndex)) {
            result[0] = point
            result[result.lastIndex] = point
        } else {
            result[index] = point
        }
        return result
    }

    /**
     * 用户主动闭环：把起点追加到末尾。
     * 点数不足或已经闭环时原样返回。
     */
    fun closeLoop(points: List<LatLng>): List<LatLng> {
        if (!canCloseLoop(points)) return points
        return points + points.first()
    }

    /**
     * 把点位重新编号为 0..n-1 的顺序列表。仅用于持久化，不改变坐标。
     */
    fun reorder(points: List<LatLng>, fromIndex: Int, toIndex: Int): List<LatLng> {
        if (fromIndex !in points.indices || toIndex !in points.indices || fromIndex == toIndex) {
            return points
        }
        val result = ArrayList(points)
        val moved = result.removeAt(fromIndex)
        result.add(toIndex, moved)
        return result
    }
}
