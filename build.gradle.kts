plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "cat.sergibonet.diagnosi"
    compileSdk = 34

    defaultConfig {
        applicationId = "cat.sergibonet.tauler"
        minSdk = 30
        targetSdk = 33
        versionCode = 11
        versionName = "2.0-beta1.2"
    }

    // Clau fixa (inclosa al projecte) perquè cada versió nova es pugui instal·lar
    // damunt de l'anterior sense desinstal·lar res. No és secreta: és de proves.
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
    // Mapes vectorials sense connexió (OpenStreetMap)
    implementation("org.mapsforge:mapsforge-map-android:0.25.0")
    implementation("org.mapsforge:mapsforge-themes:0.25.0")
}
