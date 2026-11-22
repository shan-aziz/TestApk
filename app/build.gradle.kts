plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.tvwebapp"
    compileSdk = 35

    defaultConfig {
        // Change this to something unique to you before publishing (e.g. com.yourname.tvapp)
        applicationId = "com.example.tvwebapp"
        minSdk = 22      // Fire OS 5 and newer
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
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
    kotlinOptions {
        jvmTarget = "17"
    }
}
