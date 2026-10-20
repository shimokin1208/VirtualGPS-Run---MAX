package com.virtualrun.app.map

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import java.util.Locale

/**
 * 纯 Kotlin 地理计算工具。
 *
 * 特意不依赖 android.location.Location，这样这部分逻辑可以在普通 JVM 单元测试里直接运行
 * （android.location 的方法在单元测试中只会抛 "not mocked"）。
 */
object GeoMath {

    private const val EARTH_RADIUS_METERS = 6_371_008.8

    /** 两点间大圆距离，单位米。 */
    fun distanceMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val phi1 = Math.toRadians(lat1)
        val phi2 = Math.toRadians(lat2)
        val dPhi = Math.toRadians(lat2 - lat1)
        val dLambda = Math.toRadians(lng2 - lng1)
        val a = sin(dPhi / 2).pow(2) +
            cos(phi1) * cos(phi2) * sin(dLambda / 2).pow(2)
        return 2 * EARTH_RADIUS_METERS * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }

    /** 经纬度是否落在合法范围内。 */
    fun isValidLatLng(lat: Double, lng: Double): Boolean =
        !lat.isNaN() && !lng.isNaN() &&
            lat.isFinite() && lng.isFinite() &&
            lat in -90.0..90.0 && lng in -180.0..180.0

    /**
     * 以米为单位的东北偏移量平移一个坐标。
     * 小偏移量的近似算法，用于地图测试数据的路线变体生成。
     */
    fun offsetMeters(lat: Double, lng: Double, northMeters: Double, eastMeters: Double): Pair<Double, Double> {
        val metersPerDegreeLat = 111_320.0
        val cosLat = cos(Math.toRadians(lat)).coerceAtLeast(1e-6)
        val metersPerDegreeLng = metersPerDegreeLat * cosLat
        val newLat = (lat + northMeters / metersPerDegreeLat).coerceIn(-90.0, 90.0)
        var newLng = lng + eastMeters / metersPerDegreeLng
        // 跨 180 度经线时归一化
        if (newLng > 180.0) newLng -= 360.0
        if (newLng < -180.0) newLng += 360.0
        return newLat to newLng
    }

    /** 用于界面展示的经纬度文本，固定使用 Locale.US 以免小数点受系统语言影响。 */
    fun formatLatLng(lat: Double, lng: Double, digits: Int = 6): String {
        val safeDigits = digits.coerceIn(2, 8)
        return String.format(Locale.US, "%.${safeDigits}f, %.${safeDigits}f", lat, lng)
    }
}
