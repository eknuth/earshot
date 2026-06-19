// Vendored from k2-fsa/sherpa-onnx (Apache-2.0): sherpa-onnx/kotlin-api/OnlineStream.kt
// The sherpa-onnx Android runtime has no official Maven artifact, so the Kotlin API is
// vendored here for the benchmark sample app. The matching libsherpa-onnx-jni.so /
// libonnxruntime.so for each ABI must be dropped into src/main/jniLibs (see README).
// Do not edit: field names/order mirror the JNI contract in the prebuilt .so.

package com.k2fsa.sherpa.onnx

class OnlineStream(var ptr: Long = 0) {
    fun acceptWaveform(samples: FloatArray, sampleRate: Int) =
        acceptWaveform(ptr, samples, sampleRate)

    fun inputFinished() = inputFinished(ptr)

    fun setOption(key: String, value: String) = setOption(ptr, key, value)

    fun getOption(key: String): String = getOption(ptr, key)

    protected fun finalize() {
        if (ptr != 0L) {
            delete(ptr)
            ptr = 0
        }
    }

    fun release() = finalize()

    fun use(block: (OnlineStream) -> Unit) {
        try {
            block(this)
        } finally {
            release()
        }
    }

    private external fun acceptWaveform(ptr: Long, samples: FloatArray, sampleRate: Int)
    private external fun inputFinished(ptr: Long)
    private external fun setOption(ptr: Long, key: String, value: String)
    private external fun getOption(ptr: Long, key: String): String
    private external fun delete(ptr: Long)


    companion object {
        init {
            System.loadLibrary("sherpa-onnx-jni")
        }
    }
}
