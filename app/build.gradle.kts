plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.tokalot.app"
    compileSdk = 34
    ndkVersion = "26.1.10909125"

    defaultConfig {
        applicationId = "com.tokalot.app"
        minSdk = 26
        targetSdk = 34
        // Bump both for every release you install on top of an older one.
        versionCode = 27
        versionName = "1.23-beta3"

        // arm64 only: every modern phone, and it keeps the APK small and the build fast.
        // Override with -Pabis=x86_64 for the emulator used by the screenshot workflow.
        val abis = (project.findProperty("abis") as String?)?.split(",") ?: listOf("arm64-v8a")
        ndk { abiFilters += abis }

        externalNativeBuild {
            cmake {
                // Force optimized native code even in the debug APK. An unoptimized
                // whisper build is many times slower and burns far more battery.
                arguments += listOf("-DCMAKE_BUILD_TYPE=Release", "-DGGML_OPENMP=OFF")
            }
        }
    }

    // One permanent key signs every build, so updates always install over the old app.
    // The key never lives in the repo: CI gets it from GitHub secrets, local builds from
    // environment variables. Without it (e.g. someone else's fork) builds fall back to the
    // debug key, which works but can't update your installed copy.
    val keystorePath = System.getenv("TOKALOT_KEYSTORE")
    signingConfigs {
        if (keystorePath != null && file(keystorePath).exists()) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("TOKALOT_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("TOKALOT_KEY_ALIAS") ?: "tokalot"
                keyPassword = System.getenv("TOKALOT_KEY_PASSWORD") ?: System.getenv("TOKALOT_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    // Skip the release-time lint pass: it downloads extra tooling and adds minutes to CI.
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    // Unit tests run on a plain JVM, where Android's built-in org.json is only a stub.
    testImplementation("org.json:json:20240303")
}
