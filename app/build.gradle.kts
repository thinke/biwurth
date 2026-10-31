plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "io.github.thinke.biwurth"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.thinke.biwurth"
        // Built for one wall tablet (Android 16); 30 keeps the kiosk and window APIs free of version checks.
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Installed by hand on our own tablet: the debug key will do, and lets a release build
            // replace a debug one without losing the settings.
            signingConfig = signingConfigs.getByName("debug")
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
}
