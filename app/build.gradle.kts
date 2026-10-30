plugins {
    id("com.android.application")
}

android {
    namespace = "com.adownloader.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.adownloader.app"
        minSdk = 29
        targetSdk = 36
        versionCode = 2
        versionName = "0.1.9"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
