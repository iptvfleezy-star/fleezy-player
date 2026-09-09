plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// Single source of truth for the app version: the top-level VERSION file
// (repo root). versionName is read verbatim ("x.y.z") and versionCode is
// DERIVED deterministically as major*10000 + minor*100 + patch so versionCode
// stays monotonic while VERSION remains the single source of truth.
// 1.0.29 → versionName "1.0.29", versionCode 10029 (> the legacy code 39, so
// installs over existing builds stay monotonic).
val versionFile = rootProject.file("../VERSION")
val appVersionName: String = versionFile.readText().trim()
val appVersionCode: Int = run {
    val parts = appVersionName.split(".").map { it.trim().toInt() }
    require(parts.size >= 3) { "VERSION must be x.y.z, got '$appVersionName'" }
    parts[0] * 10_000 + parts[1] * 100 + parts[2]
}

android {
    namespace = "com.ultratv.tv.nativeapp"
    compileSdk = 35

    defaultConfig {
        // Different applicationId during development so it can be installed
        // alongside the existing Capacitor build (com.ultratv.tv).
        applicationId = "stream.fleezy.player"
        minSdk = 28
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName
        vectorDrawables { useSupportLibrary = true }
    }

    // Production signing uses the permanent Fleezy release keystore.
    // Debug builds use Android's debug key and a different applicationId
    // (stream.fleezy.player.debug), so there is no key-rotation relationship
    // between alpha installs and the eventual customer release.
    signingConfigs {
        getByName("debug") {
            // CI explicitly pins debug builds to the repository's disposable
            // Fleezy alpha key. This makes successive alpha APKs update-compatible
            // instead of relying on whatever debug keystore AGP happens to resolve.
            val debugKsPath = System.getenv("FLEEZY_DEBUG_KEYSTORE")
            if (!debugKsPath.isNullOrBlank() && file(debugKsPath).exists()) {
                storeFile = file(debugKsPath)
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = true
            }
        }

        create("release") {
            val ksPath = System.getenv("FLEEZY_KEYSTORE")
            if (!ksPath.isNullOrBlank() && file(ksPath).exists()) {
                storeFile = file(ksPath)
                storePassword = System.getenv("FLEEZY_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("FLEEZY_KEY_ALIAS")
                keyPassword = System.getenv("FLEEZY_KEY_PASSWORD")
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = true
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            isMinifyEnabled = false
        }
        release {
            // R8 full-mode: shrinks resources + obfuscates code. ~18 MB → ~8 MB.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf(
            "-opt-in=androidx.tv.material3.ExperimentalTvMaterial3Api",
            "-opt-in=androidx.media3.common.util.UnstableApi",
            "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
        )
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    sourceSets["main"].kotlin.srcDirs("src/main/kotlin")
    sourceSets["test"].kotlin.srcDirs("src/test/kotlin")

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.isReturnDefaultValues = true
    }

    // Lint Vital runs during assembleRelease and blocks on any "error" severity
    // issue. We're shipping a hobby APK with no Play track, and the errors it
    // raises are typically about resource configurations that don't affect
    // runtime — flip abortOnError off and only fail the build on actual code
    // issues (caught by the compiler).
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    packaging {
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "META-INF/DEPENDENCIES",
            "META-INF/LICENSE*",
            "META-INF/NOTICE*",
        )
    }
}

// Export Room schemas so future version bumps can ship verified Migration
// objects (and so the schema history is tracked in VCS under app/schemas).
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.foundation)
    implementation(libs.compose.tv.foundation)
    implementation(libs.compose.tv.material)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime)
    implementation(libs.lifecycle.viewmodel)
    implementation(libs.navigation.compose)
    implementation(libs.hilt.navigation.compose)

    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.exoplayer.dash)
    implementation(libs.media3.ui)
    implementation(libs.media3.session)
    implementation(libs.media3.datasource.rtmp)
    implementation("androidx.documentfile:documentfile:1.0.1")

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    implementation(libs.coil.compose)
    implementation(libs.coroutines.android)
    implementation(libs.serialization.json)
    implementation(libs.datastore.preferences)
    implementation(libs.work.runtime)
    implementation(libs.hilt.work)
    ksp(libs.hilt.work.compiler)
    implementation(libs.paging.runtime)
    implementation(libs.paging.compose)
    implementation(libs.room.paging)

    implementation(libs.okhttp)

    // Tests — runs on the local JVM with Robolectric for Android types we
    // can't easily strip out (android.util.Base64).
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.13")
    testImplementation("org.json:json:20240303")
}
