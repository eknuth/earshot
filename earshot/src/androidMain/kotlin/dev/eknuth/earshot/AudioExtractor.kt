package dev.eknuth.earshot

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min

/**
 * Android implementation of AudioExtractor using MediaExtractor and MediaCodec.
 * Extracts audio from video files and converts to WAV format for transcription.
 */
actual class AudioExtractor {

    actual suspend fun extract(
        videoPath: String,
        outputPath: String,
        targetSampleRate: Int,
        targetChannels: Int,
        maxDurationSeconds: Int?
    ): AudioExtractionResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        val videoFile = File(videoPath)

        if (!videoFile.exists()) {
            return@withContext AudioExtractionResult.Error("Video file not found: $videoPath")
        }

        var extractor: MediaExtractor? = null
        var decoder: MediaCodec? = null
        var outputStream: FileOutputStream? = null

        try {
            extractor = MediaExtractor()
            extractor.setDataSource(videoPath)

            // Find audio track
            val audioTrackIndex = findAudioTrack(extractor)
            if (audioTrackIndex < 0) {
                return@withContext AudioExtractionResult.Error("No audio track found in video")
            }

            extractor.selectTrack(audioTrackIndex)
            val inputFormat = extractor.getTrackFormat(audioTrackIndex)
            val mime = inputFormat.getString(MediaFormat.KEY_MIME)
                ?: return@withContext AudioExtractionResult.Error("Could not determine audio MIME type")

            val sourceSampleRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val sourceChannels = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val durationUs = inputFormat.getLongOrDefault(MediaFormat.KEY_DURATION, 0L)

            // Calculate max samples to extract
            val maxDurationUs = maxDurationSeconds?.let { it * 1_000_000L }
            val actualDurationUs = if (maxDurationUs != null) min(durationUs, maxDurationUs) else durationUs

            // Create decoder
            decoder = MediaCodec.createDecoderByType(mime)
            decoder.configure(inputFormat, null, null, 0)
            decoder.start()

            // Prepare output file
            val outputFile = File(outputPath)
            outputFile.parentFile?.mkdirs()
            outputStream = FileOutputStream(outputFile)

            // Write placeholder WAV header (will be updated at end)
            val wavHeader = ByteArray(44)
            outputStream.write(wavHeader)

            // Extract and decode audio
            val decodeResult = decodeAudio(
                extractor = extractor,
                decoder = decoder,
                maxDurationUs = actualDurationUs
            )

            // Use actual output format from decoder (may differ from input format)
            val actualSourceSampleRate = decodeResult.sampleRate
            val actualSourceChannels = decodeResult.channels

            // Resample if necessary
            val resampledData = resampleAudio(
                pcmData = decodeResult.pcmData,
                sourceSampleRate = actualSourceSampleRate,
                sourceChannels = actualSourceChannels,
                targetSampleRate = targetSampleRate,
                targetChannels = targetChannels
            )

            // Write PCM data
            outputStream.write(resampledData)
            outputStream.close()
            outputStream = null

            // Update WAV header with correct sizes
            writeWavHeader(
                file = outputFile,
                sampleRate = targetSampleRate,
                channels = targetChannels,
                bitsPerSample = AudioFormat.BITS_PER_SAMPLE,
                dataSize = resampledData.size
            )

            val extractionTimeMs = System.currentTimeMillis() - startTime
            val audioDurationMs = (actualDurationUs / 1000).coerceAtLeast(1)

            AudioExtractionResult.Success(
                audioPath = outputFile.absolutePath,
                sampleRate = targetSampleRate,
                channels = targetChannels,
                durationMs = audioDurationMs,
                extractionTimeMs = extractionTimeMs
            )

        } catch (e: Exception) {
            AudioExtractionResult.Error(
                message = "Audio extraction failed: ${e.message}",
                cause = e
            )
        } finally {
            outputStream?.close()
            decoder?.stop()
            decoder?.release()
            extractor?.release()
        }
    }

    actual fun isAvailable(): Boolean = true

    private fun findAudioTrack(extractor: MediaExtractor): Int {
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME)
            if (mime?.startsWith("audio/") == true) {
                return i
            }
        }
        return -1
    }

    /**
     * Decode audio from MediaExtractor to raw PCM samples (16-bit).
     *
     * Returns a DecodeResult containing the raw PCM data and detected format info.
     * Handles both 16-bit PCM and float PCM output from MediaCodec.
     */
    private fun decodeAudio(
        extractor: MediaExtractor,
        decoder: MediaCodec,
        maxDurationUs: Long
    ): DecodeResult {
        val outputData = mutableListOf<Byte>()
        val bufferInfo = MediaCodec.BufferInfo()
        var sawInputEOS = false
        var sawOutputEOS = false
        var currentTimeUs = 0L
        val timeoutUs = 10_000L
        var outputFormat: MediaFormat? = null
        var isFloatOutput = false

        while (!sawOutputEOS) {
            // Feed input
            if (!sawInputEOS) {
                val inputBufferIndex = decoder.dequeueInputBuffer(timeoutUs)
                if (inputBufferIndex >= 0) {
                    val inputBuffer = decoder.getInputBuffer(inputBufferIndex)!!
                    val sampleSize = extractor.readSampleData(inputBuffer, 0)

                    if (sampleSize < 0 || (maxDurationUs > 0 && extractor.sampleTime > maxDurationUs)) {
                        decoder.queueInputBuffer(
                            inputBufferIndex, 0, 0, 0,
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM
                        )
                        sawInputEOS = true
                    } else {
                        val presentationTimeUs = extractor.sampleTime
                        decoder.queueInputBuffer(
                            inputBufferIndex, 0, sampleSize,
                            presentationTimeUs, 0
                        )
                        extractor.advance()
                    }
                }
            }

            // Drain output
            val outputBufferIndex = decoder.dequeueOutputBuffer(bufferInfo, timeoutUs)
            when {
                outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    // Get the actual output format from decoder
                    outputFormat = decoder.outputFormat
                    isFloatOutput = try {
                        val pcmEncoding = outputFormat.getInteger(MediaFormat.KEY_PCM_ENCODING)
                        pcmEncoding == android.media.AudioFormat.ENCODING_PCM_FLOAT
                    } catch (e: Exception) {
                        false // Default to 16-bit if not specified
                    }
                }
                outputBufferIndex >= 0 -> {
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        sawOutputEOS = true
                    }

                    if (bufferInfo.size > 0) {
                        val outputBuffer = decoder.getOutputBuffer(outputBufferIndex)!!
                        outputBuffer.position(bufferInfo.offset)
                        outputBuffer.limit(bufferInfo.offset + bufferInfo.size)

                        if (isFloatOutput) {
                            // Convert float PCM to 16-bit PCM
                            val floatBuffer = outputBuffer.order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                            val numSamples = bufferInfo.size / 4 // 4 bytes per float
                            for (i in 0 until numSamples) {
                                val floatSample = floatBuffer.get()
                                // Clamp to [-1.0, 1.0] and convert to 16-bit
                                val clamped = floatSample.coerceIn(-1.0f, 1.0f)
                                val shortSample = (clamped * 32767).toInt().toShort()
                                outputData.add((shortSample.toInt() and 0xFF).toByte())
                                outputData.add(((shortSample.toInt() shr 8) and 0xFF).toByte())
                            }
                        } else {
                            // Regular 16-bit PCM - copy directly
                            val chunk = ByteArray(bufferInfo.size)
                            outputBuffer.get(chunk)
                            outputData.addAll(chunk.toList())
                        }
                        currentTimeUs = bufferInfo.presentationTimeUs
                    }

                    decoder.releaseOutputBuffer(outputBufferIndex, false)
                }
            }
        }

        // Get final format info
        val finalFormat = outputFormat ?: decoder.outputFormat
        val actualSampleRate = finalFormat.getIntegerOrDefault(MediaFormat.KEY_SAMPLE_RATE, 44100)
        val actualChannels = finalFormat.getIntegerOrDefault(MediaFormat.KEY_CHANNEL_COUNT, 2)

        return DecodeResult(
            pcmData = outputData.toByteArray(),
            sampleRate = actualSampleRate,
            channels = actualChannels,
            wasFloatPcm = isFloatOutput
        )
    }

    private data class DecodeResult(
        val pcmData: ByteArray,
        val sampleRate: Int,
        val channels: Int,
        val wasFloatPcm: Boolean
    )

    private fun MediaFormat.getIntegerOrDefault(key: String, default: Int): Int {
        return try {
            getInteger(key)
        } catch (e: Exception) {
            default
        }
    }

    private fun resampleAudio(
        pcmData: ByteArray,
        sourceSampleRate: Int,
        sourceChannels: Int,
        targetSampleRate: Int,
        targetChannels: Int
    ): ByteArray {
        // Convert bytes to 16-bit samples
        val buffer = ByteBuffer.wrap(pcmData).order(ByteOrder.LITTLE_ENDIAN)
        val sourceSamples = ShortArray(pcmData.size / 2)
        buffer.asShortBuffer().get(sourceSamples)

        // Convert to mono if needed
        val monoSamples = if (sourceChannels > 1 && targetChannels == 1) {
            ShortArray(sourceSamples.size / sourceChannels) { i ->
                var sum = 0
                for (c in 0 until sourceChannels) {
                    sum += sourceSamples[i * sourceChannels + c].toInt()
                }
                (sum / sourceChannels).toShort()
            }
        } else {
            sourceSamples
        }

        // Resample if needed - use high-quality sinc resampling with anti-aliasing
        val resampledSamples = if (sourceSampleRate != targetSampleRate) {
            sincResample(monoSamples, sourceSampleRate, targetSampleRate)
        } else {
            monoSamples
        }

        // Convert back to bytes
        val outputBuffer = ByteBuffer.allocate(resampledSamples.size * 2)
            .order(ByteOrder.LITTLE_ENDIAN)
        resampledSamples.forEach { outputBuffer.putShort(it) }
        return outputBuffer.array()
    }

    /**
     * High-quality audio resampling using Lanczos interpolation with anti-aliasing.
     *
     * Linear interpolation causes severe aliasing artifacts when downsampling (e.g., 44100Hz -> 16000Hz).
     * This implementation uses a windowed-sinc (Lanczos-3) filter which:
     * 1. Applies a low-pass anti-aliasing filter at the Nyquist frequency of the target rate
     * 2. Uses sinc interpolation with Lanczos windowing for smooth reconstruction
     */
    private fun sincResample(
        samples: ShortArray,
        sourceSampleRate: Int,
        targetSampleRate: Int
    ): ShortArray {
        if (sourceSampleRate == targetSampleRate) return samples

        val ratio = sourceSampleRate.toDouble() / targetSampleRate
        val outputLength = (samples.size / ratio).toInt()
        val output = ShortArray(outputLength)

        // Lanczos-3 kernel size (3 lobes on each side)
        val kernelSize = 3

        // Cutoff frequency ratio for anti-aliasing when downsampling
        // When downsampling, we need to low-pass filter at the target Nyquist frequency
        val cutoff = if (ratio > 1.0) 1.0 / ratio else 1.0

        for (i in 0 until outputLength) {
            val srcPos = i * ratio
            val srcCenter = srcPos.toInt()

            var sum = 0.0
            var weightSum = 0.0

            // Convolve with Lanczos kernel
            val windowStart = srcCenter - kernelSize + 1
            val windowEnd = srcCenter + kernelSize

            for (j in windowStart..windowEnd) {
                if (j < 0 || j >= samples.size) continue

                val x = srcPos - j
                val weight = lanczosWeight(x, kernelSize, cutoff)

                sum += samples[j].toDouble() * weight
                weightSum += weight
            }

            // Normalize and clamp to short range
            val result = if (weightSum > 0) sum / weightSum else 0.0
            output[i] = result.coerceIn(-32768.0, 32767.0).toInt().toShort()
        }

        return output
    }

    /**
     * Lanczos windowed-sinc interpolation weight.
     *
     * @param x Distance from the sample point
     * @param a Lanczos parameter (kernel size in lobes)
     * @param cutoff Cutoff frequency ratio for anti-aliasing (0.0 to 1.0)
     */
    private fun lanczosWeight(x: Double, a: Int, cutoff: Double): Double {
        if (x == 0.0) return 1.0
        if (kotlin.math.abs(x) >= a) return 0.0

        val scaledX = x * cutoff
        val piX = kotlin.math.PI * scaledX
        val piXOverA = kotlin.math.PI * x / a

        // sinc(x) * sinc(x/a) with cutoff scaling
        val sinc = kotlin.math.sin(piX) / piX
        val lanczosWindow = kotlin.math.sin(piXOverA) / piXOverA

        return sinc * lanczosWindow * cutoff
    }

    private fun writeWavHeader(
        file: File,
        sampleRate: Int,
        channels: Int,
        bitsPerSample: Int,
        dataSize: Int
    ) {
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8

        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(0)

            // RIFF header
            raf.writeBytes("RIFF")
            raf.writeIntLE(36 + dataSize) // File size - 8
            raf.writeBytes("WAVE")

            // fmt subchunk
            raf.writeBytes("fmt ")
            raf.writeIntLE(16) // Subchunk1 size (16 for PCM)
            raf.writeShortLE(1) // Audio format (1 = PCM)
            raf.writeShortLE(channels)
            raf.writeIntLE(sampleRate)
            raf.writeIntLE(byteRate)
            raf.writeShortLE(blockAlign)
            raf.writeShortLE(bitsPerSample)

            // data subchunk
            raf.writeBytes("data")
            raf.writeIntLE(dataSize)
        }
    }

    private fun RandomAccessFile.writeIntLE(value: Int) {
        write(value and 0xFF)
        write((value shr 8) and 0xFF)
        write((value shr 16) and 0xFF)
        write((value shr 24) and 0xFF)
    }

    private fun RandomAccessFile.writeShortLE(value: Int) {
        write(value and 0xFF)
        write((value shr 8) and 0xFF)
    }

    private fun MediaFormat.getLongOrDefault(key: String, default: Long): Long {
        return try {
            getLong(key)
        } catch (e: Exception) {
            default
        }
    }
}
