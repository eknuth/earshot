package dev.eknuth.earshot

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.AVFoundation.AVAssetReader
import platform.AVFoundation.AVAssetReaderStatusCompleted
import platform.AVFoundation.AVAssetReaderTrackOutput
import platform.AVFoundation.AVAssetTrack
import platform.AVFoundation.AVMediaTypeAudio
import platform.AVFoundation.AVURLAsset
import platform.AVFAudio.AVFormatIDKey
import platform.AVFAudio.AVLinearPCMBitDepthKey
import platform.AVFAudio.AVLinearPCMIsBigEndianKey
import platform.AVFAudio.AVLinearPCMIsFloatKey
import platform.AVFAudio.AVLinearPCMIsNonInterleaved
import platform.AVFAudio.AVNumberOfChannelsKey
import platform.AVFAudio.AVSampleRateKey
import platform.AVFoundation.tracksWithMediaType
import platform.CoreAudioTypes.kAudioFormatLinearPCM
import platform.CoreMedia.CMBlockBufferGetDataLength
import platform.CoreMedia.CMBlockBufferGetDataPointer
import platform.CoreMedia.CMSampleBufferGetDataBuffer
import platform.CoreMedia.CMSampleBufferInvalidate
import platform.CoreMedia.CMSampleBufferIsValid
import platform.Foundation.NSData
import platform.Foundation.NSDate
import platform.Foundation.NSError
import platform.Foundation.NSFileManager
import platform.Foundation.NSURL
import platform.Foundation.dataWithBytes
import platform.Foundation.timeIntervalSince1970
import platform.Foundation.writeToFile
import platform.posix.memcpy
import platform.posix.size_tVar

/**
 * iOS implementation of AudioExtractor using AVFoundation.
 *
 * Pipeline: AVURLAsset -> AVAssetReader + AVAssetReaderTrackOutput configured for
 * Linear PCM (16-bit signed, target sample rate / channels). Sample buffers are read,
 * PCM bytes accumulated, then written as a canonical 16-bit PCM WAV file.
 *
 * AVFoundation is a system framework, available in the simulator and on device,
 * so no Swift bridge or third-party dependency is required.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
actual class AudioExtractor {

    actual suspend fun extract(
        videoPath: String,
        outputPath: String,
        targetSampleRate: Int,
        targetChannels: Int,
        maxDurationSeconds: Int?
    ): AudioExtractionResult {
        val startTime = NSDate().timeIntervalSince1970

        try {
            val fileManager = NSFileManager.defaultManager
            if (!fileManager.fileExistsAtPath(videoPath)) {
                return AudioExtractionResult.Error("Input file does not exist: $videoPath")
            }

            val fileUrl = NSURL.fileURLWithPath(videoPath)
            val asset = AVURLAsset(uRL = fileUrl, options = null)

            val audioTracks = asset.tracksWithMediaType(AVMediaTypeAudio)
            val audioTrack = audioTracks.firstOrNull() as? AVAssetTrack
                ?: return AudioExtractionResult.Error("No audio track found in: $videoPath")

            // Configure reader to output 16-bit signed integer Linear PCM.
            val outputSettings: Map<Any?, Any?> = mapOf(
                AVFormatIDKey to kAudioFormatLinearPCM,
                AVSampleRateKey to targetSampleRate.toDouble(),
                AVNumberOfChannelsKey to targetChannels,
                AVLinearPCMBitDepthKey to AudioFormat.BITS_PER_SAMPLE,
                AVLinearPCMIsFloatKey to false,
                AVLinearPCMIsBigEndianKey to false,
                AVLinearPCMIsNonInterleaved to false
            )

            val reader: AVAssetReader = memScoped {
                val errorPtr = alloc<ObjCObjectVar<NSError?>>()
                val r = AVAssetReader(asset = asset, error = errorPtr.ptr)
                    ?: return AudioExtractionResult.Error(
                        "Failed to create AVAssetReader: ${errorPtr.value?.localizedDescription}"
                    )
                r
            }

            val trackOutput = AVAssetReaderTrackOutput(
                track = audioTrack,
                outputSettings = outputSettings
            )

            if (!reader.canAddOutput(trackOutput)) {
                return AudioExtractionResult.Error("Cannot add track output to reader")
            }
            reader.addOutput(trackOutput)

            if (!reader.startReading()) {
                return AudioExtractionResult.Error(
                    "Failed to start reading: ${reader.error?.localizedDescription}"
                )
            }

            val bytesPerSample = AudioFormat.BITS_PER_SAMPLE / 8
            val frameSize = bytesPerSample * targetChannels
            val maxBytes: Long? = maxDurationSeconds?.let {
                it.toLong() * targetSampleRate.toLong() * frameSize.toLong()
            }

            val pcmChunks = ArrayList<ByteArray>()
            var totalBytes = 0L
            var capped = false

            while (true) {
                val sampleBuffer = trackOutput.copyNextSampleBuffer() ?: break
                try {
                    if (!CMSampleBufferIsValid(sampleBuffer)) continue
                    val blockBuffer = CMSampleBufferGetDataBuffer(sampleBuffer) ?: continue
                    val length = CMBlockBufferGetDataLength(blockBuffer)
                    if (length == 0UL) continue

                    val chunk: ByteArray? = memScoped {
                        val lengthOut = alloc<size_tVar>()
                        val dataPointerVar = alloc<CPointerVar<ByteVar>>()
                        val status = CMBlockBufferGetDataPointer(
                            blockBuffer,
                            0u,
                            lengthOut.ptr,
                            null,
                            dataPointerVar.ptr
                        )
                        if (status != 0) {
                            null
                        } else {
                            val basePtr = dataPointerVar.value
                            val avail = lengthOut.value.toLong()
                            if (basePtr == null || avail <= 0L) {
                                null
                            } else {
                                basePtr.readBytes(avail.toInt())
                            }
                        }
                    }

                    if (chunk != null) {
                        var take = chunk.size
                        if (maxBytes != null && totalBytes + take > maxBytes) {
                            take = (maxBytes - totalBytes).toInt()
                            capped = true
                        }
                        if (take == chunk.size) {
                            pcmChunks.add(chunk)
                        } else if (take > 0) {
                            pcmChunks.add(chunk.copyOfRange(0, take))
                        }
                        totalBytes += take
                    }
                } finally {
                    if (CMSampleBufferIsValid(sampleBuffer)) {
                        CMSampleBufferInvalidate(sampleBuffer)
                    }
                }

                if (capped) break
            }

            // Stop the reader if it is still going (we may have broken out early).
            if (reader.status != AVAssetReaderStatusCompleted) {
                reader.cancelReading()
            }

            if (totalBytes == 0L) {
                return AudioExtractionResult.Error("No PCM data extracted from audio track")
            }

            val pcmBytes = concat(pcmChunks, totalBytes.toInt())
            val wavBytes = buildWav(
                pcmData = pcmBytes,
                sampleRate = targetSampleRate,
                channels = targetChannels,
                bitsPerSample = AudioFormat.BITS_PER_SAMPLE
            )

            val wrote = writeBytesToFile(wavBytes, outputPath)
            if (!wrote) {
                return AudioExtractionResult.Error("Failed to write WAV file to: $outputPath")
            }

            val framesWritten = pcmBytes.size.toLong() / frameSize.toLong()
            val durationMs = if (targetSampleRate > 0) {
                framesWritten * 1000L / targetSampleRate.toLong()
            } else 0L
            val extractionTimeMs =
                ((NSDate().timeIntervalSince1970 - startTime) * 1000.0).toLong()

            return AudioExtractionResult.Success(
                audioPath = outputPath,
                sampleRate = targetSampleRate,
                channels = targetChannels,
                durationMs = durationMs,
                extractionTimeMs = extractionTimeMs
            )
        } catch (e: Throwable) {
            return AudioExtractionResult.Error(
                message = "Audio extraction failed: ${e.message}",
                cause = e
            )
        }
    }

    actual fun isAvailable(): Boolean = true

    private fun concat(chunks: List<ByteArray>, total: Int): ByteArray {
        val out = ByteArray(total)
        var offset = 0
        for (c in chunks) {
            c.copyInto(out, offset)
            offset += c.size
        }
        return out
    }

    /**
     * Build a canonical 16-bit PCM WAV byte array (RIFF header + data chunk).
     */
    private fun buildWav(
        pcmData: ByteArray,
        sampleRate: Int,
        channels: Int,
        bitsPerSample: Int
    ): ByteArray {
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val dataSize = pcmData.size
        val header = ByteArray(44)

        fun putString(offset: Int, s: String) {
            for (i in s.indices) header[offset + i] = s[i].code.toByte()
        }

        fun putIntLE(offset: Int, value: Int) {
            header[offset] = (value and 0xFF).toByte()
            header[offset + 1] = ((value shr 8) and 0xFF).toByte()
            header[offset + 2] = ((value shr 16) and 0xFF).toByte()
            header[offset + 3] = ((value shr 24) and 0xFF).toByte()
        }

        fun putShortLE(offset: Int, value: Int) {
            header[offset] = (value and 0xFF).toByte()
            header[offset + 1] = ((value shr 8) and 0xFF).toByte()
        }

        // RIFF chunk descriptor
        putString(0, "RIFF")
        putIntLE(4, 36 + dataSize)
        putString(8, "WAVE")

        // fmt subchunk
        putString(12, "fmt ")
        putIntLE(16, 16)           // Subchunk1 size (16 for PCM)
        putShortLE(20, 1)          // Audio format (1 = PCM)
        putShortLE(22, channels)
        putIntLE(24, sampleRate)
        putIntLE(28, byteRate)
        putShortLE(32, blockAlign)
        putShortLE(34, bitsPerSample)

        // data subchunk
        putString(36, "data")
        putIntLE(40, dataSize)

        return header + pcmData
    }

    /**
     * Write a ByteArray to a file path using NSData.
     */
    private fun writeBytesToFile(bytes: ByteArray, path: String): Boolean {
        if (bytes.isEmpty()) return false
        return bytes.usePinned { pinned ->
            val data = NSData.dataWithBytes(
                bytes = pinned.addressOf(0),
                length = bytes.size.convert()
            )
            data.writeToFile(path, atomically = true)
        }
    }
}
