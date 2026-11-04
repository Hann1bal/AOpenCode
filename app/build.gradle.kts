import java.util.Properties

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
            val ksPath = System.getenv("KEYSTORE_PATH")
            if (ksPath != null && file(ksPath).exists()) {
                signingConfig = signingConfigs.getByName("release")
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

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    packaging {
        // The bundled backend assets are already-compressed binaries; skip re-compressing
        // to keep extraction fast and avoid corrupting ELF payloads.
        assets {
            noCompress += "opencode"
            noCompress += "so"
            noCompress += "so.1"
            noCompress += "so.6"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    // QR scanning for `opencode pair` pairing codes
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
}
