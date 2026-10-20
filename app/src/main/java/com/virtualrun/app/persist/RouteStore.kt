package com.virtualrun.app.persist

import android.content.Context

/**
 * 路线持久化。复用现有方案（SharedPreferences），不引入新依赖。
 *
 * 只存储一个「当前编辑中的路线」，符合本应用单一工作区的使用方式。
 */
class RouteStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 读取上次保存的路线；没有保存过或数据损坏时返回 null。 */
    fun load(): SavedRoute? {
        val raw = prefs.getString(KEY_ROUTE, null) ?: return null
        val decoded = RouteCodec.decode(raw) ?: return null
        return decoded.copy(savedAtMillis = prefs.getLong(KEY_SAVED_AT, 0L))
    }

    /** 覆盖写入当前路线。写失败不抛出，仅返回 false。 */
    fun save(route: SavedRoute): Boolean {
        return try {
            prefs.edit()
                .putString(KEY_ROUTE, RouteCodec.encode(route))
                .putLong(KEY_SAVED_AT, route.savedAtMillis)
                .apply()
            true
        } catch (error: Exception) {
            false
        }
    }

    fun clear() {
        try {
            prefs.edit().remove(KEY_ROUTE).remove(KEY_SAVED_AT).apply()
        } catch (_: Exception) {
            // 清理失败不影响内存中的路线状态
        }
    }

    fun hasSavedRoute(): Boolean = prefs.contains(KEY_ROUTE)

    companion object {
        const val PREFS_NAME = "route_editor"
        const val DEFAULT_ROUTE_NAME = "未命名路线"
        private const val KEY_ROUTE = "saved_route"
        private const val KEY_SAVED_AT = "saved_at"
    }
}
