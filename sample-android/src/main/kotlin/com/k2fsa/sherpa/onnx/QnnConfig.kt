// Vendored from k2-fsa/sherpa-onnx (Apache-2.0): sherpa-onnx/kotlin-api/QnnConfig.kt
// The sherpa-onnx Android runtime has no official Maven artifact, so the Kotlin API is
// vendored here for the benchmark sample app. The matching libsherpa-onnx-jni.so /
// libonnxruntime.so for each ABI must be dropped into src/main/jniLibs (see README).
// Do not edit: field names/order mirror the JNI contract in the prebuilt .so.

package com.k2fsa.sherpa.onnx

data class QnnConfig(
    var backendLib: String = "",
    var contextBinary: String = "",
    var systemLib: String = "",
)
