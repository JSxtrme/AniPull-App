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
        versionCode = 68
        versionName = "2.0-dev2.7"
        // 2.0-dev1.5: Cronet ships a native lib per ABI (~7 MB for
        // arm64, ~4 MB for armv7, ~7 MB each for x86/x86_64). Only
        // real-phone ABIs are packaged — x86/x86_64 are emulators
        // and Chromebooks, and every phone this app is for is
        // arm64 (JS's Nothing Phone 2a included). Without this the
        // APK would carry ~26 MB of native libs.
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
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
            // 1.9 stable: R8 on (minify + shrinkResources). Conservative
            // proguard-rules.pro keeps only the manifest-reached entry
            // points; Compose/Haze/AndroidX ship their own consumer rules.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
    // 2.0-dev1.5: Cronet (Chromium network stack) for the Pahe
    // engine path — the replayed clearance needs Chrome's TLS
    // fingerprint, which HttpURLConnection cannot present (see
    // CronetHttp.kt). Vendored AARs (api + common + embedded
    // 141.7340.3) because this VM's Java tooling cannot download
    // from Google Maven; the Maven-coordinate form is
    // implementation("org.chromium.net:cronet-embedded:141.7340.3")
    // etc. — same version, one line each, once local Gradle
    // downloads work.
    implementation(files("libs/cronet-api-141.7340.3.aar"))
    implementation(files("libs/cronet-common-141.7340.3.aar"))
    implementation(files("libs/cronet-embedded-141.7340.3.aar"))
    // Transitive deps of the three Cronet AARs (from their POMs).
    // files() deps do not resolve transitives, so they are vendored
    // explicitly: cronet-shared holds CronetLogger/CronetManifest,
    // httpengine-native-provider holds HttpEngineNativeProvider,
    // protobuf-javalite is needed by the proto classes in
    // cronet-common, jsr305 by the @Nonnull annotations.
    implementation(files("libs/cronet-shared-141.7340.3.aar"))
    implementation(files("libs/httpengine-native-provider-141.7340.3.aar"))
    implementation(files("libs/protobuf-javalite-3.22.3.jar"))
    implementation(files("libs/jsr305-3.0.1.jar"))
    // Haze 1.3.1, already in the local Gradle cache from the BitChord /
    // ReChord build, so this resolves offline: real backdrop blur for
    // the translucent dock, not a fake tint. Toggle in Settings.
    implementation("dev.chrisbanes.haze:haze:1.3.1")
    implementation("dev.chrisbanes.haze:haze-materials:1.3.1")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
