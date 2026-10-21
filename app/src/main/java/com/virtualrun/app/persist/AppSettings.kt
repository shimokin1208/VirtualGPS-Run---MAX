package com.virtualrun.app.persist

import android.content.Context

/** 跟地图无关的应用设置。目前只有「拖动编辑路径点」。 */
class AppSettings(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isDragEditEnabled(): Boolean = prefs.getBoolean(KEY_DRAG_EDIT, true)

    fun setDragEditEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_DRAG_EDIT, enabled).apply()
    }

    companion object {
        private const val PREFS_NAME = "app_settings"
        const val KEY_DRAG_EDIT = "drag_edit_points"
    }
}
