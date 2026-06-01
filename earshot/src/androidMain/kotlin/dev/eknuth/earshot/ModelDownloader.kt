package dev.eknuth.earshot

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Android implementation of ModelDownloader.
 * Downloads and manages transcription models in app-specific storage.
 * Supports multi-file models (encoder, decoder, tokens) for Whisper.
 */
actual class ModelDownloader(
    private val modelsDir: File
) {
    companion object {
        private const val TAG = "ModelDownloader"
    }

    init {
        if (!modelsDir.exists()) {
            modelsDir.mkdirs()
        }
    }

    /**
     * Check if all model files are downloaded.
     */
    actual suspend fun isModelDownloaded(modelName: String): Boolean =
        withContext(Dispatchers.IO) {
            val modelDir = File(modelsDir, modelName)
            if (!modelDir.exists()) return@withContext false

            // Check for Olive model (single file) or legacy Whisper model files
            val oliveModel = File(modelDir, "model.onnx")
            if (oliveModel.exists() && oliveModel.length() > 0) {
                return@withContext true
            }

            // Legacy: Check for sherpa-onnx Whisper model files
            val encoder = File(modelDir, "encoder.onnx")
            val decoder = File(modelDir, "decoder.onnx")
            val tokens = File(modelDir, "tokens.txt")

            encoder.exists() && encoder.length() > 0 &&
                decoder.exists() && decoder.length() > 0 &&
                tokens.exists() && tokens.length() > 0
        }

    /**
     * Get the path to the model directory.
     * Returns null if model is not fully downloaded.
     */
    actual fun getModelPath(modelName: String): String? {
        val modelDir = File(modelsDir, modelName)
        if (!modelDir.exists()) return null

        // Check for Olive model (single file)
        val oliveModel = File(modelDir, "model.onnx")
        if (oliveModel.exists() && oliveModel.length() > 0) {
            return modelDir.absolutePath
        }

        // Legacy: Check for sherpa-onnx Whisper model files
        val encoder = File(modelDir, "encoder.onnx")
        val decoder = File(modelDir, "decoder.onnx")
        val tokens = File(modelDir, "tokens.txt")

        return if (encoder.exists() && decoder.exists() && tokens.exists()) {
            modelDir.absolutePath
        } else {
            null
        }
    }

    /**
     * Download model with progress updates.
     */
    actual fun downloadModel(modelInfo: ModelInfo): Flow<DownloadProgress> = flow {
        val modelDir = File(modelsDir, modelInfo.name)
        modelDir.mkdirs()

        val files = modelInfo.files.ifEmpty {
            // Backward compatibility: single file download
            listOf(
                ModelFileInfo(
                    filename = "${modelInfo.name}.onnx",
                    downloadUrl = modelInfo.downloadUrl,
                    sizeBytes = modelInfo.sizeBytes
                )
            )
        }

        val totalSize = files.sumOf { it.sizeBytes }
        var totalDownloaded = 0L

        for (fileInfo in files) {
            val outputFile = File(modelDir, fileInfo.filename)
            val tempFile = File(modelDir, "${fileInfo.filename}.tmp")

            Log.d(TAG, "Downloading ${fileInfo.filename} from ${fileInfo.downloadUrl}")

            downloadSingleFile(
                url = fileInfo.downloadUrl,
                outputFile = outputFile,
                tempFile = tempFile,
                expectedSize = fileInfo.sizeBytes
            ) { bytesDownloaded ->
                totalDownloaded = files.takeWhile { it != fileInfo }.sumOf { it.sizeBytes } + bytesDownloaded
                val progress = (totalDownloaded.toFloat() / totalSize).coerceIn(0f, 1f)
                emit(DownloadProgress(totalDownloaded, totalSize, progress))
            }
        }

        emit(DownloadProgress(totalSize, totalSize, 1f))
    }.flowOn(Dispatchers.IO)

    private suspend fun downloadSingleFile(
        url: String,
        outputFile: File,
        tempFile: File,
        expectedSize: Long,
        onProgress: suspend (Long) -> Unit
    ) {
        var connection: HttpURLConnection? = null
        var outputStream: FileOutputStream? = null

        try {
            tempFile.delete()

            connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 30_000
            connection.readTimeout = 120_000  // Longer timeout for large files
            connection.requestMethod = "GET"
            // Follow redirects (HuggingFace uses them)
            connection.instanceFollowRedirects = true

            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                throw Exception("HTTP error: $responseCode for $url")
            }

            val totalBytes = connection.contentLengthLong.takeIf { it > 0 } ?: expectedSize

            outputStream = FileOutputStream(tempFile)
            val inputStream = connection.inputStream
            val buffer = ByteArray(8192)
            var bytesDownloaded = 0L

            while (true) {
                val bytesRead = inputStream.read(buffer)
                if (bytesRead == -1) break

                outputStream.write(buffer, 0, bytesRead)
                bytesDownloaded += bytesRead
                onProgress(bytesDownloaded)
            }

            outputStream.close()
            outputStream = null

            // Move temp file to final location
            outputFile.delete()
            if (!tempFile.renameTo(outputFile)) {
                throw Exception("Failed to move downloaded file: ${outputFile.name}")
            }

            Log.d(TAG, "Downloaded ${outputFile.name}: ${outputFile.length()} bytes")

        } catch (e: Exception) {
            tempFile.delete()
            throw e
        } finally {
            outputStream?.close()
            connection?.disconnect()
        }
    }

    actual suspend fun downloadModelSync(modelInfo: ModelInfo): ModelDownloadResult =
        withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Starting download for ${modelInfo.name}")

                downloadModel(modelInfo).collect { progress ->
                    Log.d(TAG, "Download progress: ${(progress.progress * 100).toInt()}%")
                }

                val modelPath = getModelPath(modelInfo.name)
                if (modelPath != null) {
                    Log.d(TAG, "Download complete: $modelPath")
                    ModelDownloadResult.Success(modelPath)
                } else {
                    ModelDownloadResult.Error("Download completed but model files not found")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Download failed", e)
                ModelDownloadResult.Error(
                    message = "Download failed: ${e.message}",
                    cause = e
                )
            }
        }

    actual suspend fun deleteModel(modelName: String): Boolean =
        withContext(Dispatchers.IO) {
            val modelDir = File(modelsDir, modelName)
            if (modelDir.exists()) {
                modelDir.deleteRecursively()
            } else {
                true
            }
        }

    actual suspend fun getTotalModelSize(): Long =
        withContext(Dispatchers.IO) {
            modelsDir.walkTopDown()
                .filter { it.isFile }
                .sumOf { it.length() }
        }
}
