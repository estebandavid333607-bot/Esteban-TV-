plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.estebanruiz.estebantv"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.estebanruiz.estebantv"
        minSdk = 26
        targetSdk = 34
        versionCode = 3
        versionName = "2.1"
    }

    // Firma fija: todas las versiones futuras se firman igual y se instalan como actualizacion.
    signingConfigs {
        create("etv") {
            storeFile = file("estebantv.jks")
            storePassword = "estebantv2026"
            keyAlias = "estebantv"
            keyPassword = "estebantv2026"
        }
    }

    buildTypes {
        debug { signingConfig = signingConfigs.getByName("etv") }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("etv")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    lint { abortOnError = false }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.4.1")
    implementation("androidx.media3:media3-exoplayer-dash:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
}
