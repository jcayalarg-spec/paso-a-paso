plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

/* Dirección de la base de datos de Firebase (no es secreta: también está en web/ayuda/config.js). */
val dbUrl: String = (project.findProperty("DB_URL") as String?)?.trim()?.takeIf { it.isNotEmpty() }
    ?: "https://paso-a-paso-36d00-default-rtdb.firebaseio.com"

android {
    namespace = "com.pasoapaso.ayuda"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.pasoapaso.ayuda"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
        buildConfigField("String", "DB_URL", "\"$dbUrl\"")
    }

    buildFeatures { buildConfig = true }

    /* Llave SOLO para pruebas. Para la Play Store se usa la firma de Google Play. */
    signingConfigs {
        create("prueba") {
            storeFile = file("pasoapaso-prueba.jks")
            storePassword = "pasoapaso"
            keyAlias = "prueba"
            keyPassword = "pasoapaso"
        }
    }

    buildTypes {
        getByName("debug") { signingConfig = signingConfigs.getByName("prueba") }
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("prueba")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}
