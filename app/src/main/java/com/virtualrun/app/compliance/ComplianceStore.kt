package com.virtualrun.app.compliance

import android.content.Context

/**
 * 合规相关的本地状态：首次使用须知是否已确认、测试模式开关是否开启。
 *
 * 复用 SharedPreferences，不引入新依赖。
 */
class ComplianceStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun hasAcceptedNotice(): Boolean = prefs.getBoolean(KEY_ACCEPTED, false)

    fun acceptNotice() {
        prefs.edit().putBoolean(KEY_ACCEPTED, true).apply()
    }

    /**
     * 测试模式：控制是否在地图上叠加测试水印。
     *
     * 注意：关闭它**不会**移除通知栏与状态栏里的测试标记，那些是常驻的，
     * 见 [TestLabelPolicy]。本开关只影响「额外水印」这一层。
     */
    fun isTestModeEnabled(): Boolean = prefs.getBoolean(KEY_TEST_MODE, true)

    fun setTestModeEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_TEST_MODE, enabled).apply()
    }

    companion object {
        const val PREFS_NAME = "compliance"
        private const val KEY_ACCEPTED = "notice_accepted"
        private const val KEY_TEST_MODE = "test_mode"
    }
}
