plugins {
    id("com.android.library")
}

android {
    namespace = "io.github.gsjsjzhznsz.bandqq.astrbot.engine"
    compileSdk = 37
    compileSdkMinor = 0  // v2.26.0：AGP 9 需 minor 字段才命中 platforms;android-37.0（app 模块 v2.25.1 已迁，本模块漏迁）

    defaultConfig {
        minSdk = 26
        // v2.13.0 胖包内嵌 AstrBot 引擎（proot + Ubuntu rootfs + NapCat/AstrBot 启动脚本）
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
