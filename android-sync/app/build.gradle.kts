plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "io.github.gsjsjzhznsz.bandqq"
    compileSdk = 37
    compileSdkMinor = 0

    defaultConfig {
        applicationId = "io.github.gsjsjzhznsz.bandqq"
        minSdk = 26
        // v2.15.0 targetSdk 34 → 28（busybox error=13 根修）：Android 10+ 对 targetSdk≥29
        // 的应用启用 W^X（SELinux untrusted_app_29+ 禁止执行 app 数据目录内任何二进制），
        // 胖包引擎链路 busybox/bash/proot→rootfs 全在 files/engine 下，逐级 exec 全被拦
        // （"Cannot run program .../files/engine/bin/busybox: error=13, Permission denied"）。
        // 降回 28 走 legacy untrusted_app 域，恢复数据目录 exec 权限 —— 与 Termux/UserLAnd
        // 同款方案（GitHub 直发无商店 targetSdk 约束）；运行时权限代码均按 SDK_INT 守卫，不受影响。
        targetSdk = 28
        versionCode = 73
        versionName = "2.28.2"
    }

    // v2.13.0 双包分发（用户可二选一安装，同 applicationId 同 versionCode）：
    //   companion 瘦包/伴侣版：不含引擎，检测拉起独立 AstrBot Bubble App（v2.12.0 行为）
    //   bundled   胖包：内嵌 AstrBot 引擎（proot + Ubuntu rootfs，astrbot-engine 模块），
    //             开箱即得本机 NapCat，APK 体积增加约 60MB
    flavorDimensions += "dist"
    productFlavors {
        create("companion") {
            dimension = "dist"
        }
        create("bundled") {
            dimension = "dist"
        }
    }


    signingConfigs {
        create("release") {
            val ks = rootProject.file("../keystore.jks")
            storeFile = if (ks.exists()) ks else rootProject.file("keystore.jks")
            storePassword = "bandqq123"
            keyAlias = "bandqq"
            keyPassword = "bandqq123"
            // v2.26.1：显式启用 v1+v2+v3 三代签名方案（此前 AGP 对 minSdk≥24 默认仅 v2，
            // 个别魔改 ROM 的 PackageParser 只认 v1 JAR 签名会报“解析包时出现问题”）。
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
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


    buildFeatures {
        compose = true
        // 关于页展示版本号需要 BuildConfig.VERSION_NAME（AGP 8 默认关闭生成）
        buildConfig = true
    }

    lint {
        checkReleaseBuilds = false
    }

    // v2.26.1：引擎链路五件套是预构建静态二进制（busybox/proot/bash/loader/talloc），
    // 禁止 AGP 的 stripDebugSymbols 用 llvm-strip 重写它们——CI 实测 NDK 28 的 llvm-strip
    // 对 osm0sis busybox（NDK r15c 构建）会重排段表（shstrtab 重排+段头改写，143 字节差异），
    // 对体积零收益（1,498,688B 前后不变），却引入“打包产物与仓库制品不一致”的变量；
    // libsudo.so 是脚本垫片本就剥不动，一并保留原样。
    packaging {
        jniLibs {
            keepDebugSymbols += listOf(
                "**/libbusybox.so",
                "**/libproot.so",
                "**/libbash.so",
                "**/libloader.so",
                "**/liblibtalloc.so.2.so",
                "**/libsudo.so",
            )
        }
    }
}

dependencies {
    add("bundledImplementation", project(":astrbot-engine"))

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
    // ⚠️ 1.12+ 是 miuix 0.9.3 弹窗系统的硬性要求：MiuixPopupHost → PopupEntry →
    // NavigationBackHandler（androidx.navigationevent.compose）依赖
    // LocalNavigationEventDispatcherOwner / ViewTree owner，只有 activity 1.12+ 的
    // ComponentActivity 才会提供。1.9.1 时点击 Monet/预测性返回等一切弹弹窗的选项直接
    // IllegalStateException 崩溃（v2.4.6 根治）。
    implementation("androidx.activity:activity-compose:1.12.4")

    implementation("top.yukonga.miuix.kmp:miuix-ui-android:0.9.3")
    implementation("top.yukonga.miuix.kmp:miuix-icons-android:0.9.3")
    implementation("top.yukonga.miuix.kmp:miuix-blur-android:0.9.3")
    implementation("top.yukonga.miuix.kmp:miuix-preference-android:0.9.3")
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:6.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}