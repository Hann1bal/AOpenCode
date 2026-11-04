import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.opencode.mobile"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.opencode.mobile"
        minSdk = 26          // Android 8.0+
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        // Only arm64 — the bundled opencode backend is linux-arm64-musl
        ndk {
            abiFilters += "arm64-v8a"
        }

        // GitHub "owner/name" for in-app update checks. Empty = updates disabled.
        buildConfigField(
            "String",
            "UPDATE_REPO",
            "\"${System.getenv("UPDATE_REPO") ?: ""}\""
        )
    }

    signingConfigs {
        create("release") {
            // CI provides these via env vars / secrets. Local debug builds fall back to debug signing.
            val ksPath = System.getenv("KEYSTORE_PATH")
            if (ksPath != null && file(ksPath).exists()) {
                storeFile = file(ksPath)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Without a CI keystore the release APK would come out unsigned and
            // refuse to install, so fall back to the debug signing config.
            val ksPath = System.getenv("KEYSTORE_PATH")
            signingConfig = if (ksPath != null && file(ksPath).exists()) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
        debug {
            // debug signing by default
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    // The bundled backend assets are already-compressed binaries; skip re-compressing
    // to keep extraction fast and avoid corrupting ELF payloads. AGP 8.x exposes this
    // through androidResources.noCompress (there is no `packaging.assets` block).
    androidResources {
        noCompress += "opencode"
        noCompress += "so"
        noCompress += "so.1"
        noCompress += "so.6"
    }
}

// Kotlin 2.x: compilerOptions replaces the deprecated kotlinOptions block.
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    // Used by activity_main.xml (swipeRefresh); not pulled in transitively.
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    // QR scanning for `opencode pair` pairing codes
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
}
