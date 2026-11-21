import java.util.Properties

plugins {
    id("com.android.application")
}

// Where the two servers are, for this build: local.properties (not in the repository) or the environment.
//   haebom.guideBase=http://host:8765        (or HAEBOM_GUIDE_BASE)
//   haebom.ttsBase=http://host:6081/<path>   (or HAEBOM_TTS_BASE)
// A deployed server's address can end in a secret path, so it is never written in the source. A build that names
// none uses this computer through `adb reverse`. The address can still be changed on the phone (DEBUG_CONFIG).
val localProperties = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

fun serverAddress(key: String, env: String, fallback: String): String =
    (localProperties.getProperty(key) ?: System.getenv(env) ?: fallback).trim().trimEnd('/')

android {
    namespace = "ai.toolus.mobileguide"
    compileSdk = 36

    defaultConfig {
        applicationId = "ai.toolus.mobileguide"
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        buildConfigField("String", "GUIDE_BASE", "\"${serverAddress("haebom.guideBase", "HAEBOM_GUIDE_BASE", "http://127.0.0.1:8765")}\"")
        buildConfigField("String", "TTS_BASE", "\"${serverAddress("haebom.ttsBase", "HAEBOM_TTS_BASE", "http://127.0.0.1:8766")}\"")
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// The app itself uses the Android SDK only: the models are called by the servers, not by the phone.
dependencies {
    // PaletteContrastTest: every colour pair the app draws, against the WCAG contrast rules (plain JVM)
    testImplementation("junit:junit:4.13.2")
}
