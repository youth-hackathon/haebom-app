plugins {
    id("com.android.application")
}

android {
    namespace = "ai.toolus.mobileguide"
    compileSdk = 36

    defaultConfig {
        applicationId = "ai.toolus.mobileguide"
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
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

// No dependencies: the app uses the Android SDK only. The models are called by the guide server, not by the phone.
