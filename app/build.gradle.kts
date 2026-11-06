import java.util.Properties
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
        versionCode = 46
        versionName = "1.9-dev5.5"
    }

    // 1.9-dev4.8.8-stablekey: STABLE release key, Route A.
    // How the wiring works, in plain terms:
    // - The build needs 4 things to sign: keystore file, alias,
    //   keystore password, key password.
    // - Locally those 4 things live ONLY in signing.properties,
    //   a small text file in this app folder, on this VM only.
    // - 1.9-dev5.4: GitHub Actions signs the same way, but from
    //   environment variables instead (ANIPULL_KEYSTORE_FILE,
    //   ANIPULL_KEY_ALIAS, ANIPULL_STORE_PASSWORD,
    //   ANIPULL_KEY_PASSWORD), fed from encrypted repo secrets.
    //   Env vars win when ALL four are set; otherwise the local
    //   signing.properties path below is used as before.
    // - build.gradle.kts below does NOT contain any password and
    //   never logs one. It only says "if a signing source exists,
    //   read it and use it; if none exists, fall back to the
    //   debug key so a fresh checkout still builds for testing".
    // - Pushes to GitHub use an explicit file list (push-files
    //   --paths-file), and signing.properties + release.keystore
    //   are NEVER on that list, so they can never leak into the
    //   repo. The APK itself contains only the signature, never
    //   the passwords.
    val signingProps = Properties()
    val signingPropsFile = file("signing.properties")
    if (signingPropsFile.exists()) {
        signingPropsFile.inputStream().use { signingProps.load(it) }
    }
    val envStoreFile = System.getenv("ANIPULL_KEYSTORE_FILE")
    val envStorePassword = System.getenv("ANIPULL_STORE_PASSWORD")
    val envKeyAlias = System.getenv("ANIPULL_KEY_ALIAS")
    val envKeyPassword = System.getenv("ANIPULL_KEY_PASSWORD")
    val hasEnvKey = !envStoreFile.isNullOrBlank() && !envStorePassword.isNullOrBlank() &&
        !envKeyAlias.isNullOrBlank() && !envKeyPassword.isNullOrBlank()
    val hasStableKey = hasEnvKey || signingProps.containsKey("storePassword")
    if (hasStableKey) {
        signingConfigs {
            create("release") {
                storeFile = if (hasEnvKey) file(envStoreFile) else file(signingProps.getProperty("storeFile"))
                storePassword = if (hasEnvKey) envStorePassword else signingProps.getProperty("storePassword")
                keyAlias = if (hasEnvKey) envKeyAlias else signingProps.getProperty("keyAlias")
                keyPassword = if (hasEnvKey) envKeyPassword else signingProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // Release variant = no debuggable flag, no debug overhead.
            // Signed with the stable release key when present,
            // otherwise the debug key (fresh checkout fallback).
            isMinifyEnabled = false
            isDebuggable = false
            signingConfig = if (hasStableKey) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
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
