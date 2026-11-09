plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val buildNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
// SANJ_TEST=1 builds "Sanj TV Test": its own app id (installs beside Sanj TV) and its own update channel.
val testBuild = System.getenv("SANJ_TEST") == "1"

android {
    namespace = "ca.sanjwad.tvplayer"
    compileSdk = 34

    defaultConfig {
        applicationId = if (testBuild) "ca.sanjwad.tvplayer.test" else "ca.sanjwad.tvplayer"
        manifestPlaceholders["appLabel"] = if (testBuild) "Sanj TV Test" else "Sanj TV"
        manifestPlaceholders["appBanner"] = if (testBuild) "@drawable/banner_test" else "@drawable/banner"
        buildConfigField("String", "UPDATE_TAG", "\"${if (testBuild) "stalker-test" else "latest"}\"")
        buildConfigField("boolean", "TEST_BUILD", "$testBuild")
        minSdk = 25
        targetSdk = 34
        versionCode = buildNumber
        versionName = "0.1.$buildNumber"
        // Built-in TMDB key, supplied by the TMDB_KEY secret in GitHub Actions (never stored in the code).
        buildConfigField("String", "OMDB_KEY", "\"${(System.getenv("OMDB_KEY") ?: "").trim()}\"")
        buildConfigField("String", "OPENSUBS_KEY", "\"${(System.getenv("OPENSUBS_KEY") ?: "").trim()}\"")
        // Address of the sync service (sync-worker/), kept in sync-url.txt; blank = sync off.
        val syncUrl = rootProject.file("sync-url.txt").takeIf { it.exists() }?.readText()?.trim().orEmpty()
        buildConfigField("String", "SYNC_URL", "\"$syncUrl\"")
        buildConfigField("String", "TMDB_KEY", "\"${(System.getenv("TMDB_KEY") ?: "").trim()}\"")
    }

    signingConfigs {
        create("release") {
            storeFile = file("release.keystore")
            storePassword = "tvplayer"
            keyAlias = "tvplayer"
            keyPassword = "tvplayer"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.03")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")

    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
    implementation("androidx.media3:media3-datasource-okhttp:1.4.1")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
