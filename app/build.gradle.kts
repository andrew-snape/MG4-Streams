plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.mg4.streams"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.mg4.streams"
        minSdk = 28
        targetSdk = 28
        versionCode = 9
        versionName = "0.6.1"
        // The head unit is ARM (MediaTek MT2712); drop the x86 copies of the FFmpeg library.
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
        // GitHub "owner/repo" the in-app updater checks for releases. Change it in a fork.
        buildConfigField("String", "UPDATE_REPO", "\"andrew-snape/MG4-Streams\"")
    }

    buildFeatures {
        buildConfig = true
    }

    lint {
        // Head unit is Android 9 (API 28) and the app isn't on Google Play.
        disable += "ExpiredTargetSdkVersion"
    }

    buildTypes {
        release {
            // R8 drops the unused parts of Media3, Guava and Kotlin, and unused resources:
            // about 14 MB (debug) down to a few MB, so updates download faster on the car's data.
            // Signed afterwards with the platform key (scripts/release.sh), not here.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.activity:activity:1.9.2")
    implementation("androidx.media3:media3-exoplayer:1.5.0")
    implementation("androidx.media3:media3-exoplayer-hls:1.5.0")
    implementation("androidx.media3:media3-ui:1.5.0")
    implementation("androidx.media3:media3-session:1.5.0")
    // FFmpeg audio decoders (AC3, E-AC3, DTS, TrueHD...) the head unit lacks, for USB video.
    // Jellyfin's prebuilt Media3 extension; its version must match the Media3 version above.
    implementation("org.jellyfin.media3:media3-ffmpeg-decoder:1.5.0+1")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("com.google.guava:guava:33.2.1-android")
    testImplementation("junit:junit:4.13.2")
}
