plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.jatz.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.jatz.app"
        minSdk = 26
        targetSdk = 34
        // JATZ_VERSION_CODE comes from the CI run number (build-apk.yml),
        // which only ever goes up -- required for Android to accept a
        // sideloaded APK as an UPDATE (same package, higher versionCode)
        // rather than a conflicting install that demands an uninstall first,
        // which would wipe the library and loved tracks. Falls back to 1 for
        // any build that isn't CI (there shouldn't be one, but a missing env
        // var must never silently produce an unusable versionCode).
        versionCode = (System.getenv("JATZ_VERSION_CODE")?.toIntOrNull()) ?: 1
        versionName = System.getenv("JATZ_VERSION_NAME") ?: "1.0-dev"
    }

    // A signing key CI generates fresh on every run (the AGP-default "debug"
    // keystore, auto-created when ~/.android/debug.keystore doesn't exist)
    // makes every single build a different, mutually-incompatible app as far
    // as Android's installer is concerned -- installing a new one over an
    // old one is then a package conflict, not an update, and the only way
    // forward is to uninstall first and lose the whole library. This
    // signingConfig is instead sourced from a keystore committed once to
    // GitHub Secrets (JATZ_KEYSTORE_B64 etc., decoded by build-apk.yml
    // before Gradle runs) and reused for every future build, so updates are
    // finally real updates.
    signingConfigs {
        create("jatz") {
            val ksPath = System.getenv("JATZ_KEYSTORE_PATH")
            if (ksPath != null) {
                storeFile = file(ksPath)
                storePassword = System.getenv("JATZ_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("JATZ_KEY_ALIAS")
                keyPassword = System.getenv("JATZ_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            if (System.getenv("JATZ_KEYSTORE_PATH") != null) {
                signingConfig = signingConfigs.getByName("jatz")
            }
        }
        release {
            // Sideload-only app (see PIANO.md — YouTube stream extraction is
            // against YouTube's ToS, so this never ships to the Play Store).
            // R8 is left off for v1: NewPipeExtractor bundles a Rhino JS
            // interpreter for YouTube's signature deobfuscation that needs
            // careful keep-rules, and getting a real APK in hand matters more
            // right now than shaving a few MB.
            isMinifyEnabled = false
            signingConfig = if (System.getenv("JATZ_KEYSTORE_PATH") != null) {
                signingConfigs.getByName("jatz")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            // NewPipeExtractor pulls in nanojson/rhino/jsoup; a couple of them
            // ship duplicate META-INF license files that break packaging.
            excludes += "META-INF/DEPENDENCIES"
            excludes += "META-INF/LICENSE*"
            excludes += "META-INF/NOTICE*"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.activity:activity-compose:1.9.2")

    val composeBom = platform("androidx.compose:compose-bom:2024.09.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.navigation:navigation-compose:2.8.0")

    // Background daily fetch (WorkManager self-reschedules to the next
    // 07:00 local time — see work/DailyFetchWorker.kt).
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // Playback: MediaSessionService gives background audio, lock-screen
    // controls, Bluetooth/Android Auto and the notification, for free.
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-session:1.4.1")
    implementation("androidx.media3:media3-common:1.4.1")

    // 1.6.3, not a newer 1.7.x: kotlinx.serialization core runtime 1.7.x
    // requires Kotlin 2.0+, and this project is pinned to Kotlin 1.9.24
    // (the old composeOptions.kotlinCompilerExtensionVersion mechanism,
    // simpler than migrating to the Kotlin 2.0 Compose compiler plugin).
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    implementation("io.coil-kt:coil-compose:2.6.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // On-device YouTube resolution: search + stream-URL extraction, no API
    // key, no cookies. Sideload-only for exactly this reason (see PIANO.md).
    implementation("com.github.TeamNewPipe:NewPipeExtractor:v0.26.5")
}
