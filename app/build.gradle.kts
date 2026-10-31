import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.github.odlaver.tepi"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.odlaver.tepi"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    // Publisher key lives outside the repo
    val store = providers.gradleProperty("TEPI_STORE").orNull
    signingConfigs {
        if (store != null) create("tepi") {
            storeFile = file(store)
            storePassword = providers.gradleProperty("TEPI_STORE_PASSWORD").get()
            keyAlias = "tepi"
            keyPassword = providers.gradleProperty("TEPI_STORE_PASSWORD").get()
        }
    }

    buildTypes {
        debug {
            if (store != null) signingConfig = signingConfigs.getByName("tepi")
        }
        release {
            if (store != null) signingConfig = signingConfigs.getByName("tepi")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}
