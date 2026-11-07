plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "ovh.adan.walkie"
    compileSdk = 36
    ndkVersion = "25.2.9519653"   // el último que compila para Android 4.4

    defaultConfig {
        applicationId = "ovh.adan.walkie"
        minSdk = 19
        targetSdk = 22             // solo para el T298S (Android 4.4); sin permisos en tiempo de ejecución
        versionCode = 2
        versionName = "0.2.0"
        ndk { abiFilters += listOf("armeabi-v7a") }
    }
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt") } }
    lint { checkReleaseBuilds = false; abortOnError = false }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
