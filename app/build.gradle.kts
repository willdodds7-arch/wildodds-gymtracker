import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlin.serialization)
}

// Supabase project URL + anon (publishable) key. Local dev reads local.properties (gitignored);
// CI supplies the same two values as repo/action secrets via environment variables. Only the
// anon key ever reaches this file/BuildConfig — the secret/service_role key must never appear here.
val localProperties = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun secretOrEnv(propertyKey: String, envKey: String): String =
    (localProperties.getProperty(propertyKey)?.takeIf { it.isNotBlank() })
        ?: System.getenv(envKey).orEmpty()

android {
    namespace = "com.wildodds.gymtracker"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.wildodds.gymtracker"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "2.0.0"
        multiDexEnabled = true

        buildConfigField("String", "SUPABASE_URL", "\"${secretOrEnv("supabase.url", "SUPABASE_URL")}\"")
        buildConfigField("String", "SUPABASE_ANON_KEY", "\"${secretOrEnv("supabase.anonKey", "SUPABASE_ANON_KEY")}\"")

        // Only consumed by ProfilesRlsIntegrationTest (a manual, @Ignore'd-by-default live-network
        // test) — never referenced by app code. Blank for anyone who hasn't set up test accounts.
        buildConfigField("String", "RLS_TEST_EMAIL_A", "\"${secretOrEnv("supabase.rlsTestEmailA", "SUPABASE_RLS_TEST_EMAIL_A")}\"")
        buildConfigField("String", "RLS_TEST_PASSWORD_A", "\"${secretOrEnv("supabase.rlsTestPasswordA", "SUPABASE_RLS_TEST_PASSWORD_A")}\"")
        buildConfigField("String", "RLS_TEST_EMAIL_B", "\"${secretOrEnv("supabase.rlsTestEmailB", "SUPABASE_RLS_TEST_EMAIL_B")}\"")
        buildConfigField("String", "RLS_TEST_PASSWORD_B", "\"${secretOrEnv("supabase.rlsTestPasswordB", "SUPABASE_RLS_TEST_PASSWORD_B")}\"")
    }

    signingConfigs {
        getByName("debug") {
            storeFile = file("${System.getProperty("user.home")}/.android/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }

    // ── Edition (single offline product) ────────────────────────────────────────
    // Wild Odds Offline: no accounts/cloud/social; runs offline forever, never touches the network.
    // A single `offline` flavor keeps the `.offline` applicationId (so existing installs upgrade) and
    // supplies the app_name string.
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
    implementation(libs.health.connect.client)

    // Local JSON (de)serialization for retention/export models + WorkManager for opt-in reminders.
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.work.runtime.ktx)

    // Supabase backend (v2 online-first: Auth, Postgrest, Functions). BOM pins module versions.
    implementation(platform(libs.supabase.bom))
    implementation(libs.supabase.auth)
    implementation(libs.supabase.postgrest)
    implementation(libs.supabase.functions)
    implementation(libs.ktor.client.okhttp)

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
