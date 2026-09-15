import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    // No `kotlin.android`: AGP 9 has Kotlin built in and rejects the plugin outright.
    // See the note in the root build.gradle.kts.
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

/**
 * The OpenRouter key is read from android/local.properties (git-ignored) so it never
 * lands in version control.
 *
 * SECURITY: BuildConfig fields are compiled into the APK and are trivially extractable
 * by anyone who unpacks it. Treat this key as expendable and rate-limited. For a
 * production release, proxy the call through your own backend instead.
 */
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun secret(key: String): String =
    (localProps.getProperty(key) ?: System.getenv(key) ?: "").trim()

val openRouterKey: String = secret("OPENROUTER_API_KEY")

android {
    namespace = "com.medlenx.lab"
    // 37, not 36: haze-android 1.7.3 and the Compose 1.12.0 artifacts it pulls in both
    // declare minCompileSdk 37, and :app:checkDebugAarMetadata fails the build otherwise.
    // Deliberately NOT raising targetSdk alongside it - compileSdk only decides which
    // APIs are visible at compile time, while targetSdk opts into new runtime behaviour,
    // which this app has not been tested against.
    compileSdk = 37

    defaultConfig {
        applicationId = "com.medlenx.lab"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "OPENROUTER_API_KEY", "\"$openRouterKey\"")
        buildConfigField("String", "OPENROUTER_BASE_URL", "\"https://openrouter.ai/api/v1/chat/completions\"")
        buildConfigField("String", "VL_MODEL_PRIMARY", "\"qwen/qwen3-vl-235b-a22b-instruct\"")
        buildConfigField("String", "VL_MODEL_FALLBACK", "\"qwen/qwen3-vl-30b-a3b-instruct\"")
        buildConfigField("String", "VL_DISPLAY_NAME", "\"MedLenX VL\"")
        buildConfigField("String", "VL_VERSION", "\"1.0-Pro\"")
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Signed with the debug key until a real keystore is supplied.
            signingConfig = signingConfigs.named("debug").get()
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

/*
 * AGP 9 removed the `android.kotlinOptions` block; the JVM target now lives on the
 * Kotlin extension. Pattern taken from android/nowinandroid's KotlinAndroid.kt,
 * which compiles under this exact AGP/Kotlin pair.
 */
kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

/* ------------------------------------------------------------------------
 * Bundled datasets.
 *
 * The standalone app ships the same datasets the FastAPI backend served, so the
 * MedEx index / NEML / DGDA / TRIPS / geofence features work with no server.
 *
 * They are COMMITTED under src/main/assets/data/, so this module builds from a
 * bare checkout of this branch with nothing outside it. That is deliberate: the
 * branch is meant to be downloaded and compiled on its own.
 *
 * `refreshMedLenXAssets` re-copies them from an external data/ directory, but
 * only when asked for with -Pmedlenx.dataDir=/abs/path. It never runs on its
 * own, so a build cannot clobber the committed files.
 * ------------------------------------------------------------------------- */
val assetDataDir = layout.projectDirectory.dir("src/main/assets/data")

val bundledDatasets = listOf(
    "medex_full.json",      // 16 MB - full MedEx catalogue, streamed at runtime
    "bd_locations.json",    // 8 divisions / 64 districts / 508 upazilas cascade
    "bd_geo.json",          // district centroids for geofencing
    "neml_list.json",       // DGDA National Essential Medicines List
    "trips_waiver.json",    // LDC TRIPS waiver watch list
    "dgda_prices.json",     // MRP ceilings / banned / price-adjusted flags
    "health_days.json",     // WHO / UN health-day calendar
    "pharma_jobs.json",     // job board seed
    "pharma_news.json",     // news seed
)

val refreshSource: File? = (project.findProperty("medlenx.dataDir") as String?)
    ?.let { file(it) }

val refreshMedLenXAssets = tasks.register<Copy>("refreshMedLenXAssets") {
    group = "medlenx"
    description = "Re-copies the datasets from -Pmedlenx.dataDir into the APK assets."
    if (refreshSource != null) {
        from(refreshSource) { include(bundledDatasets) }
    }
    into(assetDataDir)
    onlyIf { refreshSource != null }
}

/**
 * Fails the build instead of producing an APK whose drug index is silently
 * empty - the failure mode the old copy-at-build-time arrangement degraded into.
 */
val checkMedLenXAssets = tasks.register("checkMedLenXAssets") {
    group = "medlenx"
    description = "Verifies every bundled dataset is present in the APK assets."
    doLast {
        val missing = bundledDatasets.filter { !assetDataDir.file(it).asFile.isFile }
        check(missing.isEmpty()) {
            "Missing bundled datasets under src/main/assets/data: $missing. " +
                "Restore them, or run ./gradlew refreshMedLenXAssets " +
                "-Pmedlenx.dataDir=/abs/path/to/data"
        }
    }
}

tasks.named("preBuild") { dependsOn(checkMedLenXAssets) }

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.exifinterface)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.navigation.compose)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.datastore.preferences)

    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.coil.compose)
    implementation(libs.osmdroid.android)
    // Only haze core is needed: the bar uses an explicit HazeStyle to match
    // Figma's rgba(255,255,255,0.92) + blur(12px), not a HazeMaterials preset.
    implementation(libs.haze)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
