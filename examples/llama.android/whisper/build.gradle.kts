@file:Suppress("UnstableApiUsage")

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.jetbrains.kotlin.android)
}

android {
    namespace = "com.example.llama.whisper"
    compileSdk = 35
    ndkVersion = "29.0.13846066 rc3"

    defaultConfig {
        minSdk = 33
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
        externalNativeBuild {
            cmake {
                arguments += "-DCMAKE_BUILD_TYPE=Release"
                arguments += "-DANDROID_STL=c++_shared"
                arguments += "-WHISPER_BUILD_EXAMPLES=OFF"
                arguments += "-WHISPER_BUILD_TESTS=OFF"
                arguments += "-WHISPER_BUILD_SERVER=OFF"
                arguments += "-DGGML_CUDA=OFF"
                arguments += "-DCMAKE_MESSAGE_LOG_LEVEL=DEBUG"
                arguments += "-DCMAKE_VERBOSE_MAKEFILE=ON"
                arguments += "-DBUILD_SHARED_LIBS=OFF"
                // 16KB Page alignment requirement
                arguments += "-DCMAKE_SHARED_LINKER_FLAGS=-Wl,-z,max-page-size=16384 -fvisibility=hidden"
                arguments += "-DCMAKE_MODULE_LINKER_FLAGS=-Wl,-z,max-page-size=16384 -fvisibility=hidden"
            }
        }
    }
    externalNativeBuild {
        cmake {
            path("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(libs.androidx.core.ktx)
}
