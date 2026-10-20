package com.virtualrun.app.persist

/**
 * 点位类型。用于在界面和持久化数据里区分起点 / 途经点 / 终点。
 */
enum class RoutePointType {
    START,
    VIA,
    END;

    companion object {
        fun fromName(raw: String?): RoutePointType? =
            entries.firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) }
    }
}

/**
 * 持久化用的点位。刻意不使用 LatLng，保持纯数据、可在 JVM 单元测试里直接构造。
 *
 * @param order 点位在路线中的顺序（0 = 起点）
 * @param type  点位类型（起点 / 途经 / 终点）
 */
data class SavedPoint(
    val order: Int,
    val lat: Double,
    val lng: Double,
    val type: RoutePointType
)

/**
 * 一条被保存的路线。
 *
 * @param name         路线名称（用户可改，默认值见 RouteStore.DEFAULT_ROUTE_NAME）
 * @param points       完整点位列表（不是只存最后一个点）
 * @param isLoop       是否由用户主动开启了闭环
 * @param savedAtMillis 最近一次保存时间
 */
data class SavedRoute(
    val name: String,
    val points: List<SavedPoint>,
    val isLoop: Boolean,
    val savedAtMillis: Long
) {
    companion object {
        fun empty(name: String, nowMillis: Long): SavedRoute =
            SavedRoute(name = name, points = emptyList(), isLoop = false, savedAtMillis = nowMillis)
    }
}
