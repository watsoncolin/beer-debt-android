import java.util.Properties

// AGP 9 has built-in Kotlin, so no kotlin.android plugin; the Compose and
// serialization compiler plugins are still applied explicitly.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

val SENTRY_DSN = "https://35ee3d92a59402f6aeecfc05c0413fd3@o4508774188711936.ingest.us.sentry.io/4512076313853952"

// Upload-key signing, loaded from keystore.properties at the repo root
// (gitignored; CI writes it from secrets). Absent → release builds are unsigned.
val keystoreProps = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

android {
    namespace = "me.colinwatson.beerdebt"
    compileSdk = 37

    defaultConfig {
        applicationId = "me.colinwatson.beerdebt"
        minSdk = 28
        targetSdk = 37
        // CI passes -PversionCode=<run number> for release builds; local builds default to 1.
        versionCode = (project.findProperty("versionCode") as String?)?.toInt() ?: 1
        // Matches MARKETING_VERSION in beer-debt-ios per shipped feature.
        versionName = "1.0"
        vectorDrawables { useSupportLibrary = true }
        // Sentry (org pawfect-edit, project beer-debt-android). The DSN is not a
        // secret; it only lets the app send events. Blank disables Sentry.
        buildConfigField("String", "SENTRY_DSN", "\"${project.findProperty("sentryDsn") ?: SENTRY_DSN}\"")
        // Set by the release workflow so the uploaded R8 mapping matches this build.
        manifestPlaceholders["sentryProguardUuid"] = (project.findProperty("sentryProguardUuid") as String?) ?: ""
    }

    signingConfigs {
        if (keystoreProps.getProperty("storeFile") != null) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(project(":engine"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.health.connect)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.sentry.android)

    testImplementation(libs.junit)
}
