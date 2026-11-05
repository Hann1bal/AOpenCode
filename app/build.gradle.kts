import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Single source of truth for the W^X workaround, also exposed to the app so a
// launch failure can report which targetSdk it ran under.
val targetSdkLevel = 28

/**
 * The release version the app reports.
 *
 * Hardcoding "1.0.0" made every diagnostics report and every update check lie:
 * the running build was 1.0.18 while VERSION_NAME still said 1.0.0. The tag push
 * passes `-PappVersion=<tag>`; otherwise the VERSION file at the repo root is
 * the source, so the number exists in the tree rather than only in git metadata
 * the CI checkout does not fetch.
 */
val appVersionName: String = run {
    val fromProperty = (findProperty("appVersion") as? String)?.trim()?.removePrefix("v")
    if (!fromProperty.isNullOrEmpty()) return@run fromProperty
    val file = rootProject.file("VERSION")
    val fromFile = if (file.exists()) file.readText().trim().removePrefix("v") else ""
    fromFile.ifEmpty { "0.0.0" }
}

/**
 * The signing key.
 *
 * A committed PKCS12 keystore, so every build is signed by the same key and a
 * new APK installs straight over the old one. Without a stable key each CI
 * runner creates its own debug keystore, and Android rejects the update with
 * INSTALL_FAILED_UPDATE_INCOMPATIBLE — the "uninstall it first" problem.
 *
 * `KEYSTORE_PATH` still overrides this for a real release key. Blank counts as
 * unset: the workflow passes every one of these through from a secret, and an
 * unset secret arrives as an empty string rather than a missing variable.
 */
fun envOrNull(name: String): String? = System.getenv(name)?.takeIf { it.isNotBlank() }

val releaseKeystore: File =
    envOrNull("KEYSTORE_PATH")?.let { rootProject.file(it) }
        ?: rootProject.file("keystore/aopencode.p12")

android {
    namespace = "dev.opencode.mobile"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.opencode.mobile"
        minSdk = 26          // Android 8.0+
        // W^X: apps targeting API 29+ cannot execve() files inside their own home
        // directory, which is exactly how the bundled musl runtime is launched.
        // The alternative (nativeLibraryDir) only accepts lib*.so names, so the
        // musl loader and its SONAME-named libraries cannot live there. Staying
        // at 28 keeps exec working — the same route Termux takes. compileSdk is
        // unaffected, so we still build against 35 APIs.
        targetSdk = targetSdkLevel

        buildConfigField("int", "TARGET_SDK", targetSdkLevel.toString())
        // Kept at 1 on purpose: the APK is sideloaded over itself, and a bumped
        // code with a debug key would force an uninstall first. The human-readable
        // version is what tracks releases.
        versionCode = 1
        versionName = appVersionName

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
            storeType = "PKCS12"
            storeFile = releaseKeystore
            storePassword = envOrNull("KEYSTORE_PASSWORD") ?: "aopencode"
            keyAlias = envOrNull("KEY_ALIAS") ?: "aopencode"
            keyPassword = envOrNull("KEY_PASSWORD") ?: "aopencode"
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
            // Without a usable keystore the release APK would come out unsigned
            // and refuse to install, so fall back to debug signing.
            signingConfig = if (releaseKeystore.exists()) {
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
