import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.license)
}

// Open-source licence report (Phase 6). Generates JSON at build time from the resolved deps;
// tools/build_oss_licenses.py converts the offline-debug report into legal/open-source-licenses.md,
// which is bundled + rendered offline like the other legal docs.
licenseReport {
    generateJsonReport = true
    generateHtmlReport = false
    generateCsvReport = false
    generateTextReport = false
}

android {
    namespace = "com.wildodds.gymtracker"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.wildodds.gymtracker"
        minSdk = 26
        targetSdk = 36
        versionCode = 3
        versionName = "2.1.0"
        multiDexEnabled = true
    }

    // Release signing (Phase 7). Credentials come from keystore.properties (gitignored) or CI env
    // vars — never committed. You generate + back up the keystore yourself (see docs/launch-runbook.md).
    // When no release keystore is configured, the release build falls back to debug signing so a
    // local `assembleOfflineRelease` still works for R8 smoke-testing (that artifact is NOT
    // Play-uploadable — Play rejects debug-signed bundles).
    val keystoreProps = Properties().apply {
        val f = rootProject.file("keystore.properties")
        if (f.exists()) f.inputStream().use { load(it) }
    }
    fun ksProp(key: String, env: String): String =
        (keystoreProps.getProperty(key)?.takeIf { it.isNotBlank() }) ?: System.getenv(env).orEmpty()
    val releaseStorePath = ksProp("storeFile", "RELEASE_STORE_FILE")
    val hasReleaseKeystore = releaseStorePath.isNotBlank() && file(releaseStorePath).exists()

    signingConfigs {
        getByName("debug") {
            storeFile = file("${System.getProperty("user.home")}/.android/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = file(releaseStorePath)
                storePassword = ksProp("storePassword", "RELEASE_STORE_PASSWORD")
                keyAlias = ksProp("keyAlias", "RELEASE_KEY_ALIAS")
                keyPassword = ksProp("keyPassword", "RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName(if (hasReleaseKeystore) "release" else "debug")
        }
    }

    // ── Edition (single product) ────────────────────────────────────────────────
    // Wild Odds Gym Tracker is local-first: all training data lives on-device and the app works
    // fully offline. An account is OPTIONAL and used only for the Friends (social) layer — there are
    // no ads and no in-app purchases. The paid Creator/Marketplace tier is built but parked behind
    // the `feat_creator_marketplace` feature flag (default off) for the free launch.
    // The flavor is still named `offline` to keep the `.offline` applicationId (so existing installs
    // upgrade); it also supplies the app_name string.
    flavorDimensions += "edition"
    productFlavors {
        create("offline") {
            dimension = "edition"
            applicationIdSuffix = ".offline"
            versionNameSuffix = "-offline"
            resValue("string", "app_name", "Wild Odds Gym Tracker")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"

        // Compose STRONG SKIPPING. By default a composable with any unstable parameter (a
        // `List`, a lambda capturing an unstable value, a data class from another module) is not
        // skippable, so it re-executes on every recomposition of its parent even when nothing it
        // reads has changed. This app passes plenty of those around — session/exercise lists,
        // callbacks — so most of the screen tree was un-skippable. Strong skipping compares such
        // parameters by instance and memoises lambdas, which makes them skippable.
        freeCompilerArgs += listOf(
            "-P", "plugin:androidx.compose.compiler.plugins.kotlin:experimentalStrongSkipping=true"
        )
    }

    // Local unit tests run on Robolectric and need the merged Android resources/assets
    // (this is also how the migration test reaches the exported Room schema JSON).
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }

    // The exported Room schemas (see the `ksp { ... }` block below) are bundled as
    // debug-only assets so Robolectric's MigrationTestHelper can validate against them.
    // They are NOT included in release builds.
    sourceSets {
        getByName("debug") {
            assets.srcDir("schemas")
        }
        // Bundle the canonical legal docs (Phase 6) as assets so they render OFFLINE in-app —
        // single source of truth in /legal, never fetched from the network.
        getByName("main") {
            assets.srcDir("$rootDir/legal")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/LICENSE.md",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
                "META-INF/NOTICE.md",
                "META-INF/*.RSA",
                "META-INF/*.SF",
                "META-INF/*.DSA",
                "META-INF/AL2.0",
                "META-INF/LGPL2.1",
                "META-INF/versions/9/module-info.class",
                "META-INF/versions/11/module-info.class",
                "mozilla/public-suffix-list.txt",
                "DebugProbesKt.bin"
            )
        }
    }
}

// Export the Room schema as JSON on every build (one file per DB version). Checked into
// app/schemas/. IMPORTANT: every future migration must bump AppDatabase.version and
// regenerate the schema (just build) so the new <version>.json is checked in — the
// migration test validates the migrated DB against it.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    // Installs the bundled baseline profile (src/main/baseline-prof.txt) on API 28–30, where the
    // platform won't pick it up on its own. On 31+ the platform reads it from the APK directly.
    implementation(libs.androidx.profileinstaller)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.multidex)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.compose.animation)
    implementation(libs.compose.foundation)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.navigation.compose)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.datastore.preferences)
    implementation(libs.coroutines.android)
    implementation(libs.gson)
    implementation(libs.glance.appwidget)
    implementation(libs.glance.material3)
    implementation(libs.okhttp)
    implementation(libs.pdfbox.android)
    // On-device OCR for scanned PDFs and photos of printed programs. BUNDLED model (see the
    // version catalog): no download, no Play Services dependency, works in airplane mode on first
    // launch. This is the only way to satisfy "fully offline" for the image path.
    implementation(libs.mlkit.text.recognition)
    implementation(libs.health.connect.client)

    // Local JSON (de)serialization for retention/export models + WorkManager for opt-in reminders.
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.work.runtime.ktx)

    // ── Local unit tests (src/test) — run on the JVM via Robolectric ──────────────
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.turbine)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.core.ktx)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.room.testing)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    // Provides the ComponentActivity that createComposeRule() hosts the content in.
    debugImplementation(libs.compose.ui.test.manifest)

    // ── Instrumented tests (src/androidTest) — require a device/emulator ──────────
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.core.ktx)
    androidTestImplementation(libs.room.testing)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
}
