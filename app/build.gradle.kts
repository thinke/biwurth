plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "io.github.thinke.biwurth"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.thinke.biwurth"
        // 26 (Android 8.0) so older wall tablets can run it too; newer kiosk, window and installer
        // APIs sit behind version checks.
        minSdk = 26
        targetSdk = 36
        // CI passes these from the release tag (v1.2.3 -> 1.2.3); local builds use the defaults.
        versionCode = System.getenv("BIWURTH_VERSION_CODE")?.toInt() ?: 1
        versionName = System.getenv("BIWURTH_VERSION_NAME") ?: "0.1.0"
    }

    // Release signing comes from the environment so no key material lives in the repo (the
    // SnapTV keystore). Without it, local release builds fall back to the debug key.
    val keystore = System.getenv("BIWURTH_KEYSTORE")
    signingConfigs {
        if (keystore != null) {
            create("release") {
                storeFile = file(keystore)
                storePassword = System.getenv("BIWURTH_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("BIWURTH_KEY_ALIAS")
                keyPassword = System.getenv("BIWURTH_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    // Home Assistant WebSocket and REST API
    implementation(libs.okhttp)
    // The tablet as a Home Assistant device (MQTT discovery)
    implementation(libs.paho.mqtt)
    // Camera tiles: RTSP (Frigate / go2rtc) and HLS, hardware decoded
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.rtsp)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.ui)
    // QR code for setting up a new tablet (Provisioning)
    implementation(libs.zxing.core)
}
