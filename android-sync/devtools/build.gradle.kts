plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "io.github.gsjsjzhznsz.bandqq.devtools"
    compileSdk = 37
    compileSdkMinor = 0  // v2.26.0：AGP 9 需 minor 字段才命中 platforms;android-37.0（app 模块 v2.25.1 已迁，本模块漏迁）

    defaultConfig {
        applicationId = "io.github.gsjsjzhznsz.bandqq.devtools"
        minSdk = 26
        targetSdk = 34
        versionCode = 7
        versionName = "1.4.0"
    }

    signingConfigs {
        create("release") {
            val ks = rootProject.file("../keystore.jks")
            storeFile = if (ks.exists()) ks else rootProject.file("keystore.jks")
            storePassword = "bandqq123"
            keyAlias = "bandqq"
            keyPassword = "bandqq123"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }


    lint {
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // v1.4.0：UI 与主 APK 同风格（miuix 0.9.3 HyperOS 设计语言）
    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.activity:activity-compose:1.12.4")
    implementation("top.yukonga.miuix.kmp:miuix-ui-android:0.9.3")
    implementation("top.yukonga.miuix.kmp:miuix-icons-android:0.9.3")
    implementation("top.yukonga.miuix.kmp:miuix-blur-android:0.9.3")
}
