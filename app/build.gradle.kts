plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "de.musikassistent"
    compileSdk = 34

    defaultConfig {
        applicationId = "de.musikassistent"
        minSdk = 29
        targetSdk = 34
        versionCode = 2
        versionName = "1.1"
        // OnePlus 9 Pro = arm64, spart Platz
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    // Fester Schlüssel, damit neue Versionen sich über die alte installieren lassen
    signingConfigs {
        create("fest") {
            storeFile = file("signing.keystore")
            storePassword = "musik123"
            keyAlias = "musik"
            keyPassword = "musik123"
        }
    }

    buildTypes {
        getByName("debug") { signingConfig = signingConfigs.getByName("fest") }
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("fest")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    // Offline-Spracherkennung (Aktivierungswort läuft lokal, ohne Internet)
    implementation("net.java.dev.jna:jna:5.13.0@aar")
    implementation("com.alphacephei:vosk-android:0.3.47@aar")
}
