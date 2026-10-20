import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Load signing config from keystore.properties (not committed to git)
val keystoreProperties = Properties()
val ksFile = rootProject.file("keystore.properties")
if (ksFile.exists()) {
    keystoreProperties.load(ksFile.inputStream())
}

android {
    namespace = "dev.zerokool.headpop"
    compileSdk = 34

    defaultConfig {
        applicationId = "dev.zerokool.headpop"
        minSdk = 24
        targetSdk = 34
        versionCode = 6
        versionName = "1.5"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (ksFile.exists()) {
                signingConfig = signingConfigs.create("release") {
                    storeFile = file(keystoreProperties["storeFile"] as String)
                    storePassword = keystoreProperties["storePassword"] as String
                    keyAlias = keystoreProperties["keyAlias"] as String
                    keyPassword = keystoreProperties["keyPassword"] as String
                }
            }
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

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.camera:camera-camera2:1.3.4")
    implementation("androidx.camera:camera-lifecycle:1.3.4")
    implementation("androidx.camera:camera-view:1.3.4")
    implementation("com.google.mlkit:face-detection:16.1.6")
}