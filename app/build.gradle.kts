plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.gdm.offlineflow"
    compileSdk = 34
    ndkVersion = "26.1.10909125"

    defaultConfig {
        applicationId = "com.gdm.offlineflow"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"

        // arm64 only: every modern phone, and it keeps the APK small and the build fast.
        ndk { abiFilters += listOf("arm64-v8a") }

        externalNativeBuild {
            cmake {
                // Force optimized native code even in the debug APK. An unoptimized
                // whisper build is many times slower and burns far more battery.
                arguments += listOf("-DCMAKE_BUILD_TYPE=Release", "-DGGML_OPENMP=OFF")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
