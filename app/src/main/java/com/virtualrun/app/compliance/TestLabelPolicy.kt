package com.virtualrun.app.compliance

/**
 * 测试用途标记的集中定义。
 *
 * 设计原则：**常驻标记不可关闭，开关只增加额外标记**。
 *
 * - 通知栏标题前缀、状态栏说明 → 常驻，与开关无关；
 * - 地图水印 → 由「测试模式」开关控制（默认开）。
 *
 * 这样这个开关永远不会变成「把模拟定位藏起来」的入口。
 */
object TestLabelPolicy {

    /** 通知栏标题固定前缀。 */
    const val NOTIFICATION_PREFIX = "[测试]"

    /** 状态栏里的常驻说明。 */
    const val STATUS_LINE = "测试用途：本应用输出模拟定位，不得用于提交真实校园跑成绩"

    /** 地图水印文案。 */
    const val WATERMARK_TEXT = "测试用途 · 模拟定位"

    /**
     * 通知栏标题。
     *
     * 无论测试模式开关是什么状态，返回值都带 [NOTIFICATION_PREFIX]，
     * 参数只为让调用方语义清晰、并为将来可能的差异化留出位置。
     */
    fun notificationTitle(testModeEnabled: Boolean = true): String =
        "$NOTIFICATION_PREFIX 虚拟跑步进行中"

    /** 是否在地图上叠加测试水印。 */
    fun watermarkVisible(testModeEnabled: Boolean): Boolean = testModeEnabled
}
