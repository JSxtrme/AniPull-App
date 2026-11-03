plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.js.anipull"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.js.anipull"
        minSdk = 26
        targetSdk = 36
        versionCode = 21
        versionName = "1.9-dev4.8.3"
    }

    buildTypes {
        release {
            // Release variant = no debuggable flag, no debug overhead.
            // Signed with the same debug key as the stage-1 APK on purpose,
            // so `adb install -r` updates in place. A Play Store release
            // key is a later step, not this phone-test build.
            isMinifyEnabled = false
            isDebuggable = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // Kotlin 2.x: jvmTarget as a String is an error, use compilerOptions DSL.
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    // Versions match the set already cached from the ReChord build, so the
    // build can run offline on this VM (Java proxy tunneling is unreliable).
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.foundation:foundation:1.10.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.0")
    // Haze 1.3.1, already in the local Gradle cache from the BitChord /
    // ReChord build, so this resolves offline: real backdrop blur for
    // the translucent dock, not a fake tint. Toggle in Settings.
    implementation("dev.chrisbanes.haze:haze:1.3.1")
    implementation("dev.chrisbanes.haze:haze-materials:1.3.1")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
