package dev.knuth.earshot

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * Computes mel spectrograms from raw audio for Whisper model input.
 *
 * Whisper expects 80-bin log-mel spectrograms computed with specific parameters:
 * - 25ms window (400 samples at 16kHz)
 * - 10ms hop (160 samples at 16kHz)
 * - 80 mel frequency bins
 * - Log scaling with floor of 1e-10
 *
 * Input: FloatArray of 16kHz mono PCM audio normalized to [-1.0, 1.0]
 * Output: Array<FloatArray> of shape [time_steps, 80] or transposed for ONNX
 */
class MelSpectrogramProcessor(
    private val sampleRate: Int = SAMPLE_RATE,
    private val nFft: Int = N_FFT,
    private val hopLength: Int = HOP_LENGTH,
    private val nMels: Int = N_MELS,
    private val fMin: Float = F_MIN,
    private val fMax: Float = F_MAX
) {
    companion object {
        const val SAMPLE_RATE = 16000
        const val N_FFT = 400        // 25ms window at 16kHz
        const val HOP_LENGTH = 160   // 10ms hop at 16kHz
        const val N_MELS = 80        // Mel frequency bins
        const val F_MIN = 0f         // Min frequency for mel filterbank
        const val F_MAX = 8000f      // Max frequency (Nyquist for 16kHz)

        private const val LOG_FLOOR = 1e-10f

        // Whisper normalization constants
        private const val LOG_SPEC_MAX = 0f    // Max for clamping
        private const val LOG_SPEC_OFFSET = 4f // Offset for normalization
        private const val LOG_SPEC_SCALE = 4f  // Scale factor
    }

    // Precomputed Hann window
    private val window: FloatArray = FloatArray(nFft) { i ->
        (0.5f * (1 - cos(2 * PI * i / nFft))).toFloat()
    }

    // Precomputed mel filterbank matrix [nMels, nFft/2 + 1]
    private val melFilterbank: Array<FloatArray> = createMelFilterbank()

    // FFT helper - precompute twiddle factors for the FFT size
    // We need to pad to next power of 2 for Cooley-Tukey
    private val fftSize: Int = nextPowerOf2(nFft)
    private val twiddleReal: FloatArray
    private val twiddleImag: FloatArray

    init {
        // Precompute twiddle factors: e^(-2πi*k/N) = cos(2πk/N) - i*sin(2πk/N)
        twiddleReal = FloatArray(fftSize / 2) { k ->
            cos(2.0 * PI * k / fftSize).toFloat()
        }
        twiddleImag = FloatArray(fftSize / 2) { k ->
            -sin(2.0 * PI * k / fftSize).toFloat()
        }
    }

    /**
     * Compute mel spectrogram from audio samples.
     *
     * @param audio 16kHz mono PCM audio, normalized to [-1.0, 1.0]
     * @return mel spectrogram of shape [time_steps, nMels]
     */
    fun compute(audio: FloatArray): Array<FloatArray> {
        // Pad audio to ensure we get complete frames
        val paddedAudio = padAudio(audio)

        // Number of frames
        val numFrames = (paddedAudio.size - nFft) / hopLength + 1

        // Output mel spectrogram
        val melSpec = Array(numFrames) { FloatArray(nMels) }

        // Buffers for FFT (reuse across frames)
        val frameReal = FloatArray(fftSize)
        val frameImag = FloatArray(fftSize)
        val powerSpectrum = FloatArray(nFft / 2 + 1)

        for (frameIdx in 0 until numFrames) {
            val start = frameIdx * hopLength

            // Apply window and copy to FFT buffer
            for (i in 0 until nFft) {
                frameReal[i] = paddedAudio[start + i] * window[i]
            }
            // Zero-pad to fftSize if needed
            for (i in nFft until fftSize) {
                frameReal[i] = 0f
            }
            // Clear imaginary part
            for (i in 0 until fftSize) {
                frameImag[i] = 0f
            }

            // Compute FFT in-place
            fft(frameReal, frameImag)

            // Compute power spectrum (magnitude squared)
            for (i in 0 until nFft / 2 + 1) {
                val re = frameReal[i]
                val im = frameImag[i]
                powerSpectrum[i] = re * re + im * im
            }

            // Apply mel filterbank and log scaling
            for (melIdx in 0 until nMels) {
                var melEnergy = 0f
                for (freqIdx in 0 until nFft / 2 + 1) {
                    melEnergy += melFilterbank[melIdx][freqIdx] * powerSpectrum[freqIdx]
                }
                // Log10 scaling with floor (raw log-mel, not normalized)
                melSpec[frameIdx][melIdx] = log10(max(melEnergy, LOG_FLOOR))
            }
        }

        return melSpec
    }

    /**
     * Compute mel spectrogram with Whisper-style normalization.
     * Normalized to approximately [0, 1] range.
     *
     * @param audio 16kHz mono PCM audio, normalized to [-1.0, 1.0]
     * @return normalized mel spectrogram of shape [time_steps, nMels]
     */
    fun computeNormalized(audio: FloatArray): Array<FloatArray> {
        val melSpec = compute(audio)

        // Find max value across all frames
        var maxVal = Float.NEGATIVE_INFINITY
        for (frame in melSpec) {
            for (value in frame) {
                if (value > maxVal) maxVal = value
            }
        }

        // Whisper normalization: clamp((log_spec - max + 4.0) / 4.0, 0, 1)
        for (frame in melSpec) {
            for (i in frame.indices) {
                val normalized = (frame[i] - maxVal + LOG_SPEC_OFFSET) / LOG_SPEC_SCALE
                frame[i] = max(0f, min(1f, normalized))
            }
        }

        return melSpec
    }

    /**
     * Compute mel spectrogram in ONNX-ready format.
     * Returns a flattened FloatArray in [n_mels, time_steps] order (transposed).
     *
     * @param audio 16kHz mono PCM audio, normalized to [-1.0, 1.0]
     * @param normalize whether to apply Whisper normalization
     * @return flattened mel spectrogram of shape [n_mels * time_steps]
     */
    fun computeForOnnx(audio: FloatArray, normalize: Boolean = true): FloatArray {
        val melSpec = if (normalize) computeNormalized(audio) else compute(audio)
        val numFrames = melSpec.size

        // Transpose from [time, mels] to [mels, time] and flatten
        val output = FloatArray(nMels * numFrames)
        for (melIdx in 0 until nMels) {
            for (frameIdx in 0 until numFrames) {
                output[melIdx * numFrames + frameIdx] = melSpec[frameIdx][melIdx]
            }
        }

        return output
    }

    /**
     * Get the number of frames that will be produced for the given audio length.
     */
    fun getNumFrames(audioLength: Int): Int {
        val paddedLength = max(audioLength, nFft)
        return (paddedLength - nFft) / hopLength + 1
    }

    /**
     * Pad audio to ensure complete frames.
     * Whisper uses reflection padding.
     */
    private fun padAudio(audio: FloatArray): FloatArray {
        // Pad to ensure at least one complete frame
        val minLength = nFft
        if (audio.size >= minLength) {
            return audio
        }

        // Reflection pad
        val padded = FloatArray(minLength)
        audio.copyInto(padded)

        // Reflect remaining samples
        var idx = audio.size
        var reflectIdx = audio.size - 2
        var direction = -1

        while (idx < minLength) {
            if (reflectIdx < 0) {
                reflectIdx = 0
                direction = 1
            } else if (reflectIdx >= audio.size) {
                reflectIdx = audio.size - 1
                direction = -1
            }
            padded[idx] = audio[reflectIdx]
            reflectIdx += direction
            idx++
        }

        return padded
    }

    /**
     * Create mel filterbank matrix.
     * Returns [nMels, nFft/2 + 1] matrix.
     */
    private fun createMelFilterbank(): Array<FloatArray> {
        val numFreqs = nFft / 2 + 1
        val filterbank = Array(nMels) { FloatArray(numFreqs) }

        // Convert Hz to Mel scale
        val melMin = hzToMel(fMin)
        val melMax = hzToMel(fMax)

        // Create nMels + 2 equally spaced points in mel scale
        val melPoints = FloatArray(nMels + 2) { i ->
            melMin + (melMax - melMin) * i / (nMels + 1)
        }

        // Convert back to Hz
        val hzPoints = melPoints.map { melToHz(it) }

        // Convert Hz to FFT bin indices
        val binPoints = hzPoints.map { hz ->
            floor((nFft + 1) * hz / sampleRate).toInt()
        }

        // Create triangular filters
        for (m in 0 until nMels) {
            val startBin = binPoints[m]
            val peakBin = binPoints[m + 1]
            val endBin = binPoints[m + 2]

            // Rising slope
            for (k in startBin until peakBin) {
                if (k >= 0 && k < numFreqs && peakBin > startBin) {
                    filterbank[m][k] = (k - startBin).toFloat() / (peakBin - startBin)
                }
            }

            // Falling slope
            for (k in peakBin until endBin) {
                if (k >= 0 && k < numFreqs && endBin > peakBin) {
                    filterbank[m][k] = (endBin - k).toFloat() / (endBin - peakBin)
                }
            }
        }

        return filterbank
    }

    /**
     * Convert frequency from Hz to Mel scale.
     * Uses the O'Shaughnessy formula: mel = 2595 * log10(1 + hz/700)
     */
    private fun hzToMel(hz: Float): Float {
        return 2595f * log10(1f + hz / 700f)
    }

    /**
     * Convert frequency from Mel scale to Hz.
     */
    private fun melToHz(mel: Float): Float {
        return 700f * (10f.pow(mel / 2595f) - 1f)
    }

    /**
     * In-place Cooley-Tukey FFT.
     * @param real Real part of input/output
     * @param imag Imaginary part of input/output
     */
    private fun fft(real: FloatArray, imag: FloatArray) {
        val n = fftSize

        // Bit-reversal permutation
        var j = 0
        for (i in 0 until n - 1) {
            if (i < j) {
                // Swap
                var temp = real[i]
                real[i] = real[j]
                real[j] = temp
                temp = imag[i]
                imag[i] = imag[j]
                imag[j] = temp
            }
            var k = n / 2
            while (k <= j) {
                j -= k
                k /= 2
            }
            j += k
        }

        // Cooley-Tukey iterative FFT
        var step = 1
        while (step < n) {
            val halfStep = step
            step *= 2
            val twiddleStep = n / step

            for (group in 0 until n step step) {
                for (pair in 0 until halfStep) {
                    val twiddleIdx = pair * twiddleStep
                    val wr = twiddleReal[twiddleIdx]
                    val wi = twiddleImag[twiddleIdx]

                    val i = group + pair
                    val k = i + halfStep

                    // Butterfly
                    val tempReal = wr * real[k] - wi * imag[k]
                    val tempImag = wr * imag[k] + wi * real[k]

                    real[k] = real[i] - tempReal
                    imag[k] = imag[i] - tempImag
                    real[i] = real[i] + tempReal
                    imag[i] = imag[i] + tempImag
                }
            }
        }
    }

    /**
     * Find next power of 2 >= n.
     */
    private fun nextPowerOf2(n: Int): Int {
        var v = n - 1
        v = v or (v shr 1)
        v = v or (v shr 2)
        v = v or (v shr 4)
        v = v or (v shr 8)
        v = v or (v shr 16)
        return v + 1
    }
}
