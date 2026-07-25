plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// The native Whisper STT is built only when the whisper.cpp submodule is present.
// Without it (e.g. before you run `git submodule add ...`), the app still builds and
// falls back to Google STT at runtime.
val whisperSources = file("src/main/cpp/whisper.cpp")
val enableWhisper = whisperSources.exists()

android {
    namespace = "com.example.aicallresponder"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.aicallresponder"
        minSdk = 26          // ANSWER_PHONE_CALLS / TelecomManager.acceptRingingCall()
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        if (enableWhisper) {
            ndk {
                // arm64 is the primary target. Add "armeabi-v7a" only if you must support old
                // 32-bit devices (whisper.cpp builds there but is much slower).
                abiFilters += listOf("arm64-v8a")
            }
            externalNativeBuild {
                cmake {
                    cppFlags += "-std=c++17"
                }
            }
        }
    }

    if (enableWhisper) {
        externalNativeBuild {
            cmake {
                path = file("src/main/cpp/CMakeLists.txt")
                version = "3.22.1"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // Framework-only UI + java.net for HTTP keeps the dependency graph to just Kotlin coroutines,
    // which avoids AndroidX artifacts that aren't fully populated in this offline Gradle cache.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.6.4")
}
