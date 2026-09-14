import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
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
    compileSdk = 36

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

/* ---------------------------------------------------------------------------
 * Bundled catalogue.
 *
 * The standalone app ships the same datasets the FastAPI backend serves, so the
 * MedEx index / NEML / DGDA / TRIPS / geofence features work with no server.
 * They are copied from the repository's own data/ directory at build time rather
 * than duplicated into git, which keeps the Android module self-contained without
 * committing a second 16 MB copy of medex_full.json.
 *
 * Set -Pmedlenx.dataDir=/some/path to override the source location.
 * ------------------------------------------------------------------------- */
val medlenxDataDir: File = (project.findProperty("medlenx.dataDir") as String?)
    ?.let { file(it) }
    ?: rootProject.projectDir.parentFile.resolve("data")

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

val copyMedLenXAssets by tasks.registering(Copy::class) {
    group = "medlenx"
    description = "Copies the MedLenX datasets into the APK assets."
    from(medlenxDataDir) {
        include(bundledDatasets)
    }
    into(layout.projectDirectory.dir("src/main/assets/data"))
    doFirst {
        if (!medlenxDataDir.isDirectory) {
            logger.warn(
                "MedLenX data dir not found at {} - run from the repo checkout or pass " +
                    "-Pmedlenx.dataDir=/abs/path. The app will start but the drug index " +
                    "will be empty.", medlenxDataDir
            )
        }
    }
}

tasks.named("preBuild") { dependsOn(copyMedLenXAssets) }

/* Keep generated datasets and the local key out of git. */
tasks.named("clean") {
    doLast { delete(layout.projectDirectory.dir("src/main/assets/data")) }
}

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
