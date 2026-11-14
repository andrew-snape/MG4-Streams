plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.mg4.streams"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.mg4.streams"
        minSdk = 28
        targetSdk = 28
        versionCode = 7
        versionName = "0.5.1"
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
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
    implementation("androidx.media3:media3-session:1.4.1")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("com.google.guava:guava:33.2.1-android")
    testImplementation("junit:junit:4.13.2")
}
