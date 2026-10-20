plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.virtualrun.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.virtualrun.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 7
        versionName = "1.1.0"

    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    // 注意：使用 org.jetbrains.kotlin.plugin.compose（Kotlin 2.0+）后不再需要
    // composeOptions.kotlinCompilerExtensionVersion，该字段已被插件接管。
    testOptions {
        // 单元测试不启动 Android 运行时；未被 mock 的框架方法返回默认值而不是抛异常。
        unitTests.isReturnDefaultValues = true
    }
    lint {
        disable += "InvalidFragmentVersionForActivityResult"
        abortOnError = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation(platform("androidx.compose:compose-bom:2024.09.02"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // LatLng route coordinate model
    implementation("com.google.android.gms:play-services-maps:18.2.0")

    // Google Play 融合定位：跑步软件开始记录后通常会切换到 FLP 通道
    implementation("com.google.android.gms:play-services-location:21.0.1")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")

    // Lifecycle
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")

    // osmdroid - 开源 OpenStreetMap 地图，无需 API Key
    implementation("org.osmdroid:osmdroid-android:6.1.18")

    testImplementation("junit:junit:4.13.2")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
