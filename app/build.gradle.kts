plugins {
    id("com.android.application")
}

val geckoVersion = project.property("geckoVersion").toString()
val sourceCommit = providers.exec { commandLine("git", "rev-parse", "HEAD"); isIgnoreExitValue = true }.standardOutput.asText.get().trim().let { if (it.matches(Regex("[0-9a-f]{40}"))) it else "UNKNOWN" }

android {
    buildFeatures { buildConfig = true }
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
        versionCode = 21
        versionName = "0.7.1"
        buildConfigField("String", "GECKO_VERSION", "\"$geckoVersion\"")
        buildConfigField("String", "COMMIT_SHA", "\"$sourceCommit\"")

        ndk {
            // Current release is optimized for modern Android phones such as Fold8.
            // Removing x86/32-bit Gecko binaries cuts the APK size dramatically.
            abiFilters += listOf(providers.gradleProperty("testAbi").getOrElse("arm64-v8a"))
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
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
    defaultConfig { testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner" }
    // Robolectric renders layouts to PNG on the JVM (ui-render tests) so UI changes can be
    // reviewed without an emulator.
    testOptions { unitTests.isIncludeAndroidResources = true }
}

dependencies {
    implementation("org.mozilla.geckoview:geckoview:$geckoVersion")
    implementation("androidx.window:window-java:1.5.1")
    androidTestImplementation("androidx.window:window-testing:1.5.1")
    implementation("com.google.android.material:material:1.13.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.17")
    testImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
}
