plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.kira.tts"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.kira.tts"
        minSdk = 26
        targetSdk = 34
        versionCode = System.getenv("BUILD_NUMBER")?.toIntOrNull() ?: 1
        versionName = System.getenv("VERSION_NAME") ?: "1.0-dev"
    }

    signingConfigs {
        create("release") {
            storeFile = file("signing/release.keystore")
            storePassword = "tts-kira-mk32-2026"
            keyAlias = "tts-kira"
            keyPassword = "tts-kira-mk32-2026"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("com.github.mik3y:usb-serial-for-android:3.7.0")
}
