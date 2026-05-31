package dev.knuth.earshot

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * iOS implementation of ModelDownloader.
 *
 * Intentionally left as a no-op stub (EDW-975): on iOS, WhisperKit fetches and
 * caches its CoreML model internally, so the shared module never drives model
 * download. See the Swift [NativeTranscriptionProvider]'s `ensureModelDownloaded`.
 */
actual class ModelDownloader {

    actual suspend fun isModelDownloaded(modelName: String): Boolean = false

    actual fun getModelPath(modelName: String): String? = null

    actual fun downloadModel(modelInfo: ModelInfo): Flow<DownloadProgress> = emptyFlow()

    actual suspend fun downloadModelSync(modelInfo: ModelInfo): ModelDownloadResult =
        ModelDownloadResult.Error("iOS model download not yet implemented")

    actual suspend fun deleteModel(modelName: String): Boolean = false

    actual suspend fun getTotalModelSize(): Long = 0L
}
