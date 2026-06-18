plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.vitalwork.wear"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.vitalwork.app"
        // Samsung Health Sensor SDK requires API 28+ (Galaxy Watch4 and later).
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
    lint {
        // False positive: registerForActivityResult is called on a Compose
        // ComponentActivity, not a FragmentActivity, so the Fragment 1.3.0
        // requirement does not apply. Suppress only this rule.
        disable += "InvalidFragmentVersionForActivityResult"
    }
}

kotlin {
    jvmToolchain(11)
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)

    // Wearable Data Layer (ChannelClient stream to the paired tablet)
    implementation(libs.play.services.wearable)
    // .await() bridge for the Play Services Tasks API + Dispatchers.Main
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.kotlinx.coroutines.android)

    // JSON stream encoding (same lib/version as :app)
    implementation(libs.kotlinx.serialization.json)

    // Samsung Health Sensor SDK (local .aar in wear/libs/)
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar"))))

    debugImplementation(libs.androidx.compose.ui.tooling)

    // Host unit tests (WatchSampleStore truncate-after-ack logic)
    testImplementation(libs.junit)
}
