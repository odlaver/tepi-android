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
        minSdk = 26
        targetSdk = 36
        versionCode = 27
        versionName = "2.6.0"
    }

    // Lite has no native code, runs anywhere
    flavorDimensions += "tier"
    productFlavors {
        create("lite") {
            dimension = "tier"
            isDefault = true
        }
        create("full") {
            dimension = "tier"
            ndk { abiFilters += listOf("arm64-v8a") }
        }
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

// Full adds on-device translation; QR scanner runs inside Play services
dependencies {
    "fullImplementation"("com.google.mlkit:translate:17.0.3")
    "fullImplementation"("com.google.mlkit:language-id:17.0.6")
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")
}
