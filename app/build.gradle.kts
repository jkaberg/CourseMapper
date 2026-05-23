plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.roborazzi)
}

// CI passes the tag in VERSION_NAME ("v1.2.3" or "1.2.3"), local builds get the fallback
val appVersionName: String =
    (System.getenv("VERSION_NAME") ?: "").trim().removePrefix("v").ifBlank { "1.0.0" }

// versionCode from the version name (1.2.3 -> 10203), not the CI run number which
// resets when the workflow is renamed. Minor and patch max out at 99.
val appVersionCode: Int =
    appVersionName.substringBefore('-').split('.')
        .mapNotNull { it.toIntOrNull() }
        .takeIf { it.size == 3 }
        ?.let { (major, minor, patch) -> major * 10_000 + minor * 100 + patch }
        // "0.0.0-dev" gives 0, which AGP rejects
        ?.takeIf { it > 0 }
        ?: 1

// release signing from the environment, without a keystore assembleRelease
// still works and gives an unsigned APK
val releaseKeystorePath: String? = System.getenv("KEYSTORE_PATH")?.takeIf { it.isNotBlank() }

android {
    namespace  = "com.coursemapper"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.coursemapper"
        minSdk        = 26
        targetSdk     = 35
        versionCode   = appVersionCode
        versionName   = appVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (releaseKeystorePath != null) {
            create("release") {
                storeFile     = file(releaseKeystorePath)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias      = System.getenv("KEY_ALIAS")
                keyPassword   = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // findByName, null means no key here and an unsigned APK
            signingConfig   = signingConfigs.findByName("release")
            isMinifyEnabled = true
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

    kotlinOptions {
        jvmTarget = "11"
    }

    buildFeatures {
        compose = true
        // About shows the running version
        buildConfig = true
    }

    testOptions {
        unitTests {
            // Robolectric needs real resources for the Room and screenshot tests
            isIncludeAndroidResources = true
        }
    }

    // exported schemas, used by the migration tests
    ksp {
        arg("room.schemaLocation", "$projectDir/schemas")
    }

    // schemas on the unit test classpath, AGP doesn't merge test assets for
    // unit tests
    sourceSets {
        getByName("test") {
            resources.srcDirs(files("$projectDir/schemas"))
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            // JUnit 5 jars from mockk-android all ship these
            excludes += "/META-INF/{LICENSE.md,LICENSE-notice.md}"
        }
    }
}

// screenshot baselines are committed, recordRoborazziDebug rewrites them and
// verifyRoborazziDebug fails on any changed pixel
roborazzi {
    outputDir.set(file("src/test/screenshots"))
}

dependencies {
    // Compose BOM - pins all Compose library versions together
    val composeBom = platform(libs.compose.bom)
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    // Core AndroidX
    implementation(libs.appcompat)
    implementation(libs.activity.compose)
    implementation(libs.navigation.compose)
    implementation(libs.lifecycle.viewmodel)
    implementation(libs.lifecycle.compose)
    implementation(libs.lifecycle.service)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    // Room
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // Coroutines
    implementation(libs.coroutines.android)

    // Location
    implementation(libs.play.services.location)

    // MapLibre
    implementation(libs.maplibre.android)

    // WorkManager (background offline scheduler)
    implementation(libs.work.runtime.ktx)
    implementation(libs.hilt.work)
    ksp(libs.hilt.work.compiler)

    // DataStore
    implementation(libs.datastore.prefs)

    // Testing
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.json)
    // real in-memory Room under Robolectric, DAO logic lives in SQL
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.room.testing)

    // screenshot tests run as JVM unit tests
    testImplementation(composeBom)
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.roborazzi.junit.rule)

    androidTestImplementation(libs.junit.ext)
    androidTestImplementation(libs.androidx.test.core)
    // the navigation VM takes concrete classes, needs mocks that work on ART
    androidTestImplementation(libs.mockk.android)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.compose.ui.test.junit4)
}
