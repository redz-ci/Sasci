plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Lit un paramètre depuis gradle.properties, -P..., ou une variable d'environnement.
fun cfg(name: String, default: String = ""): String {
    val fromProp = (project.findProperty(name) as String?)?.trim()
    if (!fromProp.isNullOrEmpty()) return fromProp
    val fromEnv = System.getenv(name)?.trim()
    if (!fromEnv.isNullOrEmpty()) return fromEnv
    return default
}

val buildNumber = cfg("GITHUB_RUN_NUMBER", "1").toIntOrNull() ?: 1

android {
    namespace = "ci.sas.livraisons"
    compileSdk = 35

    defaultConfig {
        applicationId = "ci.sas.livraisons"
        minSdk = 24
        targetSdk = 35
        versionCode = buildNumber
        versionName = "1.0.$buildNumber"
        buildConfigField("String", "SCRIPT_URL", "\"${cfg("SASCI_SCRIPT_URL")}\"")
        buildConfigField("String", "API_KEY", "\"${cfg("SASCI_API_KEY")}\"")
    }

    // Signature : si une clé est fournie (secrets GitHub ou variables d'environnement),
    // l'APK est signé avec elle — indispensable pour pouvoir mettre à jour l'appli
    // sans la désinstaller. Sinon, la clé de débogage standard d'Android est utilisée.
    val keystorePath = cfg("SASCI_KEYSTORE_PATH")
    signingConfigs {
        if (keystorePath.isNotEmpty()) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = cfg("SASCI_KEYSTORE_PASSWORD")
                keyAlias = cfg("SASCI_KEY_ALIAS", "sasci")
                keyPassword = cfg("SASCI_KEY_PASSWORD", cfg("SASCI_KEYSTORE_PASSWORD"))
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (keystorePath.isNotEmpty()) signingConfigs.getByName("release")
            else signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
}
