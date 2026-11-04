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
        versionCode = 9
        versionName = "1.0"
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
