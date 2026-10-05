import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

// release 签名：凭据放项目根目录 keystore.properties（已 gitignore），不进版本库
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}

// Room schema 导出目录：8 个 Migration（v4→v12）可据此做迁移回归测试
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

android {
    namespace = "com.bookkeeping.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.bookkeeping.app"
        minSdk = 26
        targetSdk = 33
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }

        // sherpa-onnx AAR 自带 4 个 ABI，只保留真机需要的两个，避免 APK 膨胀
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }

    signingConfigs {
        create("release") {
            if (keystoreProps.isNotEmpty()) {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
            // minSdk=26，V2 签名已足够（Android 8.0+ 全支持 V2）；
            // AGP 在此 minSdk 下会自动跳过 V1，属预期行为
            enableV1Signing = true
            enableV2Signing = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // 没有 keystore.properties 时退化为不签名，保证换机器也能编译
            signingConfig = if (keystoreProps.isNotEmpty()) {
                signingConfigs.getByName("release")
            } else {
                null
            }
        }
        debug {
            isDebuggable = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions { jvmTarget = "17" }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // sherpa-onnx 模型文件（assets/model/*.onnx）不压缩，加快加载与安装
    androidResources {
        noCompress += "onnx"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // AndroidX
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")

    // Compose BOM
    val composeBom = platform("androidx.compose:compose-bom:2024.09.02")
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    // Room
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // WorkManager
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // Glance Widget
    implementation("androidx.glance:glance-appwidget:1.1.1")

    // Biometric (密码锁/指纹)
    implementation("androidx.biometric:biometric:1.1.0")
    // ⚠️ 必须显式声明：biometric:1.1.0 传递依赖的是 fragment:1.2.5，
    // 而 1.2.5 的 FragmentActivity 仍在校验「requestCode 只能用低 16 位」，
    // 与 activity:1.9.x 的 ActivityResultRegistry（生成 24 位 requestCode）冲突，
    // 导致任何 rememberLauncherForActivityResult().launch() 直接抛
    // IllegalArgumentException: Can only use lower 16 bits for requestCode（CSV 导入即崩溃）。
    // fragment 1.3.0 起已移除该校验。MainActivity 为指纹解锁必须继承 FragmentActivity，
    // 故此处只能升版本，不能改成 ComponentActivity。
    implementation("androidx.fragment:fragment:1.8.2")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // sherpa-onnx 官方 AAR（classes.jar + 双架构 so 一体），语音离线识别用
    implementation(files("libs/sherpa-onnx-1.13.8.aar"))
}
