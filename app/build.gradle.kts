plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val build = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1

android {
    namespace = "pl.eve.assistant"
    compileSdk = 35

    defaultConfig {
        applicationId = "pl.eve.assistant"
        minSdk = 26
        targetSdk = 35
        versionCode = build
        versionName = "1.0.$build"
    }

    // Stały klucz podpisu - dzięki niemu nowe wersje instalują się NA starą (bez odinstalowywania).
    signingConfigs {
        getByName("debug") {
            storeFile = file("eve-debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }
    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("debug")
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
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    // Vosk - rozpoznawanie mowy offline (wykrywanie słowa "EVE")
    implementation("com.alphacephei:vosk-android:0.3.47@aar")
    implementation("net.java.dev.jna:jna:5.13.0@aar")
}
