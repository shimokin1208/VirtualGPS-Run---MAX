package com.virtualrun.app.persist

import com.google.android.gms.maps.model.LatLng
import com.virtualrun.app.algorithm.RouteClassifier

/**
 * LatLng 列表与持久化模型之间的转换。
 *
 * 单独抽出来是为了让「顺序 / 类型 / 名称」的映射规则可以被单元测试覆盖，
 * 而不需要启动 Android 环境。
 */
object SavedRouteFactory {

    fun create(
        name: String,
        points: List<LatLng>,
        isLoop: Boolean,
        nowMillis: Long
    ): SavedRoute {
        val types = RouteClassifier.classify(points)
        val saved = points.mapIndexed { index, latLng ->
            SavedPoint(
                order = index,
                lat = latLng.latitude,
                lng = latLng.longitude,
                type = types.getOrElse(index) { RoutePointType.VIA }
            )
        }
        return SavedRoute(
            name = name,
            points = saved,
            isLoop = isLoop,
            savedAtMillis = nowMillis
        )
    }

    /** 按保存的顺序还原成 LatLng 列表；顺序字段缺失时按数组顺序。 */
    fun toLatLngList(route: SavedRoute): List<LatLng> =
        route.points.sortedBy { it.order }.map { LatLng(it.lat, it.lng) }
}
