// Vendored from k2-fsa/sherpa-onnx (Apache-2.0): sherpa-onnx/kotlin-api/WaveReader.kt
// The sherpa-onnx Android runtime has no official Maven artifact, so the Kotlin API is
// vendored here for the benchmark sample app. The matching libsherpa-onnx-jni.so /
// libonnxruntime.so for each ABI must be dropped into src/main/jniLibs (see README).
// Do not edit: field names/order mirror the JNI contract in the prebuilt .so.

// Copyright (c)  2023  Xiaomi Corporation
package com.k2fsa.sherpa.onnx

import android.content.res.AssetManager

data class WaveData(
    val samples: FloatArray,
    val sampleRate: Int,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as WaveData

        if (!samples.contentEquals(other.samples)) return false
        if (sampleRate != other.sampleRate) return false

        return true
    }

    override fun hashCode(): Int {
        var result = samples.contentHashCode()
        result = 31 * result + sampleRate
        return result
    }
}

class WaveReader {
    companion object {

        fun readWave(
            assetManager: AssetManager,
            filename: String,
        ): WaveData {
            return readWaveFromAsset(assetManager, filename)
        }

        fun readWave(
            filename: String,
        ): WaveData {
            return readWaveFromFile(filename)
        }

        // Read a mono wave file asset
        external fun readWaveFromAsset(
            assetManager: AssetManager,
            filename: String,
        ): WaveData

        // Read a mono wave file from disk
        external fun readWaveFromFile(
            filename: String,
        ): WaveData

        init {
            System.loadLibrary("sherpa-onnx-jni")
        }
    }
}
