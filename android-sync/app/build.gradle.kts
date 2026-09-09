plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.example.bandqq"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.example.bandqq"
        minSdk = 26
        targetSdk = 34
        versionCode = 4
        versionName = "1.2.0"
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

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        compose = true
    }

    lint {
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation(files("libs/xms-wearable-lib_1.4_release.aar"))
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:okhttp-sse:4.12.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.google.code.gson:gson:2.11.0")

    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.activity:activity-compose:1.9.1")

    implementation("top.yukonga.miuix.kmp:miuix-ui-android:0.9.3")
    implementation("top.yukonga.miuix.kmp:miuix-icons-android:0.9.3")

    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}