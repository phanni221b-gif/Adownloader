import java.util.Base64

plugins {
    id("com.android.application")
}

android {
    namespace = "com.adownloader.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.adownloader.app"
        minSdk = 29
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
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

val generatedIconRes = layout.buildDirectory.dir("generated/adownloaderIconRes")
val prepareLauncherIcon by tasks.registering {
    val source = file("src/main/icon/adownloader_icon.b64")
    inputs.file(source)
    outputs.dir(generatedIconRes)
    doLast {
        val out = generatedIconRes.get().file("drawable-nodpi/adownloader_icon.jpg").asFile
        out.parentFile.mkdirs()
        out.writeBytes(Base64.getMimeDecoder().decode(source.readText()))
    }
}

android.sourceSets.getByName("main").res.srcDir(generatedIconRes)
tasks.named("preBuild").configure { dependsOn(prepareLauncherIcon) }

dependencies {
    implementation("androidx.webkit:webkit:1.18.0")
}
