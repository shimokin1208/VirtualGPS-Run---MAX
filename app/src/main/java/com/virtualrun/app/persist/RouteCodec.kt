package com.virtualrun.app.persist

import com.virtualrun.app.map.GeoMath

/**
 * 路线的文本编解码。
 *
 * 设计目标：
 * 1. 纯 Kotlin，无 Android 依赖，可直接在 JVM 单元测试里往返验证；
 * 2. 版本化，方便以后扩展字段而不破坏旧数据；
 * 3. 容错：任何格式异常都返回 null，由调用方回退到空路线，绝不崩溃。
 *
 * 格式（第一行为头，之后每行一个点）：
 * ```
 * V1|loop=0|count=2|name=学校操场
 * 0|START|39.904200|116.407400
 * 1|END|39.905000|116.408000
 * ```
 * 字段以 `|` 分隔，名称中的 `\`、`|`、换行会被转义。
 */
object RouteCodec {

    const val VERSION = "V1"
    private const val SEPARATOR = '|'
    private const val FIELD_COUNT = 4

    fun encode(route: SavedRoute): String {
        val sb = StringBuilder()
        sb.append(VERSION)
        sb.append(SEPARATOR).append("loop=").append(if (route.isLoop) 1 else 0)
        sb.append(SEPARATOR).append("count=").append(route.points.size)
        sb.append(SEPARATOR).append("name=").append(escape(route.name))
        route.points.forEach { point ->
            sb.append('\n')
            sb.append(point.order).append(SEPARATOR)
            sb.append(point.type.name).append(SEPARATOR)
            sb.append(point.lat).append(SEPARATOR)
            sb.append(point.lng)
        }
        return sb.toString()
    }

    fun decode(raw: String?): SavedRoute? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null

        val lines = text.split('\n')
        val header = lines.firstOrNull()?.trim().orEmpty()
        if (header.isEmpty()) return null

        val headerFields = header.split(SEPARATOR)
        if (headerFields.firstOrNull() != VERSION) return null

        var isLoop = false
        var declaredCount = -1
        var name = ""
        for (index in 1 until headerFields.size) {
            val field = headerFields[index]
            val divider = field.indexOf('=')
            if (divider <= 0) continue
            val key = field.substring(0, divider)
            val value = field.substring(divider + 1)
            when (key) {
                "loop" -> isLoop = value == "1"
                "count" -> declaredCount = value.toIntOrNull() ?: -1
                "name" -> name = unescape(value)
            }
        }

        val points = ArrayList<SavedPoint>()
        for (lineIndex in 1 until lines.size) {
            val line = lines[lineIndex].trim()
            if (line.isEmpty()) continue
            val parts = line.split(SEPARATOR)
            if (parts.size != FIELD_COUNT) return null
            val order = parts[0].toIntOrNull() ?: return null
            val type = RoutePointType.fromName(parts[1]) ?: return null
            val lat = parts[2].toDoubleOrNull() ?: return null
            val lng = parts[3].toDoubleOrNull() ?: return null
            if (!GeoMath.isValidLatLng(lat, lng)) return null
            points.add(SavedPoint(order = order, lat = lat, lng = lng, type = type))
        }

        if (declaredCount >= 0 && declaredCount != points.size) return null

        val ordered = points.sortedBy { it.order }
        return SavedRoute(
            name = name,
            points = ordered,
            isLoop = isLoop,
            savedAtMillis = 0L
        )
    }

    internal fun escape(value: String): String {
        val sb = StringBuilder(value.length + 8)
        for (ch in value) {
            when (ch) {
                '\\' -> sb.append("\\\\")
                SEPARATOR -> sb.append("\\p")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    internal fun unescape(value: String): String {
        val sb = StringBuilder(value.length)
        var index = 0
        while (index < value.length) {
            val ch = value[index]
            if (ch == '\\' && index + 1 < value.length) {
                when (value[index + 1]) {
                    '\\' -> { sb.append('\\'); index += 2 }
                    'p' -> { sb.append(SEPARATOR); index += 2 }
                    'n' -> { sb.append('\n'); index += 2 }
                    'r' -> { sb.append('\r'); index += 2 }
                    else -> { sb.append(ch); index += 1 }
                }
            } else {
                sb.append(ch)
                index += 1
            }
        }
        return sb.toString()
    }
}
