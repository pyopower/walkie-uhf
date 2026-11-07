import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Firma de las versiones release. En GitHub Actions llega por variables de entorno (secretos del repo);
// en local, de ~/keystores/walkie/keystore.properties. Nunca dentro del repositorio.
val signing = Properties().apply {
    val f = file(System.getenv("WALKIE_KEYSTORE_PROPS") ?: "${System.getProperty("user.home")}/keystores/walkie/keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
    System.getenv("WALKIE_STORE_FILE")?.let { setProperty("storeFile", it) }
    System.getenv("WALKIE_STORE_PASSWORD")?.let { setProperty("storePassword", it) }
    System.getenv("WALKIE_KEY_ALIAS")?.let { setProperty("keyAlias", it) }
    System.getenv("WALKIE_KEY_PASSWORD")?.let { setProperty("keyPassword", it) }
}

android {
    namespace = "ovh.adan.walkie"
    compileSdk = 36
    ndkVersion = "25.2.9519653"   // el último que compila para Android 4.4

    defaultConfig {
        applicationId = "ovh.adan.walkie"
        minSdk = 19
        targetSdk = 22             // solo para el T298S (Android 4.4); sin permisos en tiempo de ejecución
        versionCode = 12
        versionName = "0.6.4"
        ndk { abiFilters += listOf("armeabi-v7a") }
    }
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt") } }
    signingConfigs {
        create("release") {
            if (signing.containsKey("storeFile")) {
                storeFile = file(signing.getProperty("storeFile"))
                storePassword = signing.getProperty("storePassword")
                keyAlias = signing.getProperty("keyAlias")
                keyPassword = signing.getProperty("keyPassword")
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            if (signing.containsKey("storeFile")) signingConfig = signingConfigs.getByName("release")
        }
    }
    lint { checkReleaseBuilds = false; abortOnError = false }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
