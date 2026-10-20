// Top-level build file where you can add configuration options common to all sub-projects/modules.
//
// 版本对齐说明：
// 原配置为 Kotlin 1.9.20 + org.jetbrains.kotlin.plugin.compose 2.0.0，
// 后者要求 Kotlin 2.0.x，混用会在配置阶段直接失败。
// 这里统一到 Kotlin 2.0.0，并配套 AGP 8.5.2 / Gradle 8.7。
plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "2.0.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.0" apply false
}
