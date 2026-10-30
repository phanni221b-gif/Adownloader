plugins {
    id("com.android.application")
}

android {
    namespace = "com.adownloader.app"
    compileSdk {
        version = release(37) {
            minorApiLevel = 2
        }
    }

    defaultConfig {
        applicationId = "com.adownloader.app"
        minSdk = 30
        targetSdk = 36
        versionCode = 7
        versionName = "0.3.0"

        ndk {
            // Current release is optimized for modern Android phones such as Fold8.
            // Removing x86/32-bit Gecko binaries cuts the APK size dramatically.
            abiFilters += listOf("arm64-v8a")
        }
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

dependencies {
    implementation("org.mozilla.geckoview:geckoview:159.0.20261022150525")
}
