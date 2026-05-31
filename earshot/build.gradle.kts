plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
}

kotlin {
    androidTarget {
        compilations.all {
            kotlinOptions {
                jvmTarget = "17"
            }
        }
    }

    listOf(
        iosX64(),
        iosArm64(),
        iosSimulatorArm64()
    ).forEach {
        it.binaries.framework {
            baseName = "Earshot"
            isStatic = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
        androidMain.dependencies {
            implementation(libs.kotlinx.coroutines.android)
            // Whisper runs through ONNX Runtime on Android; extensions supply the BPE decoder op.
            implementation(libs.onnxruntime.android)
            implementation(libs.onnxruntime.extensions.android)
        }
        // iosMain needs no extra deps: audio extraction uses AVFoundation and the ASR
        // runtime (WhisperKit / CoreML) is injected from the Swift side at runtime.
    }
}

android {
    namespace = "dev.knuth.earshot"
    compileSdk = 34

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
