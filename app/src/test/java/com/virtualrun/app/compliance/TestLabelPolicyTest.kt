package com.virtualrun.app.compliance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 锁住「测试标记不能被开关完全关闭」这条规则。
 *
 * 常驻标记（通知栏前缀、状态栏说明）与开关无关；
 * 开关只控制地图水印这一层额外标记。
 */
class TestLabelPolicyTest {

    @Test
    fun notificationTitleAlwaysCarriesTestPrefix() {
        // 无论开关打开还是关闭，通知栏都必须带 [测试] 前缀
        assertTrue(TestLabelPolicy.notificationTitle(true).startsWith(TestLabelPolicy.NOTIFICATION_PREFIX))
        assertTrue(TestLabelPolicy.notificationTitle(false).startsWith(TestLabelPolicy.NOTIFICATION_PREFIX))
        assertEquals(
            TestLabelPolicy.notificationTitle(true),
            TestLabelPolicy.notificationTitle(false)
        )
    }

    @Test
    fun defaultNotificationTitleIsPrefixed() {
        assertTrue(TestLabelPolicy.notificationTitle().startsWith(TestLabelPolicy.NOTIFICATION_PREFIX))
    }

    @Test
    fun watermarkFollowsTheSwitchOnly() {
        assertTrue(TestLabelPolicy.watermarkVisible(true))
        assertFalse(TestLabelPolicy.watermarkVisible(false))
    }

    @Test
    fun statusLineIsAlwaysPresentAndMentionsTestUsage() {
        assertTrue(TestLabelPolicy.STATUS_LINE.isNotBlank())
        assertTrue(TestLabelPolicy.STATUS_LINE.contains("测试用途"))
        assertTrue(TestLabelPolicy.STATUS_LINE.contains("模拟定位"))
    }

    @Test
    fun watermarkTextIsExplicit() {
        assertTrue(TestLabelPolicy.WATERMARK_TEXT.contains("测试"))
        assertTrue(TestLabelPolicy.WATERMARK_TEXT.contains("模拟定位"))
    }
}
