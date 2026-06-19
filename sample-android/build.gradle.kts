plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "dev.eknuth.earshot.sample"
    compileSdk = 34

    defaultConfig {
        applicationId = "dev.eknuth.earshot.sample"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            // Real-device benchmark target (Pixel 9a). sherpa-onnx prebuilt .so are per-ABI;
            // limit to arm64-v8a so the APK only carries what the test device needs.
            abiFilters += "arm64-v8a"
        }
    }

    buildFeatures {
        compose = true
    }

    packaging {
        jniLibs {
            // Both the Whisper path (Microsoft onnxruntime-android, pulled in transitively via
            // :earshot) and the sherpa-onnx Parakeet path ship a libonnxruntime.so. Two copies
            // can't coexist under one ABI, so keep the first. Both are official ONNX Runtime
            // builds; the benchmark runs one model family per APK.
            pickFirsts += "**/libonnxruntime.so"
        }
    }

    // sherpa-onnx native libraries (libsherpa-onnx-jni.so, libonnxruntime.so) for arm64-v8a are
    // NOT vendored in git. Download them from a sherpa-onnx Android release and drop them in
    // src/main/jniLibs/arm64-v8a/ before running the Parakeet benchmark. See README.

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(project(":earshot"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
