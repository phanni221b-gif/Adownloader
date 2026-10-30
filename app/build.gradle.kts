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
        versionCode = 7
        versionName = "0.3.0"
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
    // Stable GeckoView is bundled with the APK, so the browser engine no longer
    // depends on the phone's Android System WebView package.
    implementation("org.mozilla.geckoview:geckoview:159.0.20261022150525")
}
