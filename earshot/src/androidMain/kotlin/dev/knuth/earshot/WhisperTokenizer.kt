package dev.knuth.earshot

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.InputStreamReader

/**
 * Android implementation of WhisperTokenizer.
 * Decodes Whisper BPE tokens to text using vocabulary from tokens.txt.
 *
 * Token file format (one token per line, line number = token ID):
 * - Lines 0-50256: Regular BPE vocabulary tokens
 * - Lines 50257+: Special tokens (EOT, SOT, language, task, timestamps)
 *
 * BPE encoding notes:
 * - "Ġ" prefix represents a leading space (Unicode: U+0120)
 * - Byte-level BPE means some tokens are byte representations
 */
actual class WhisperTokenizer {
    companion object {
        private const val TAG = "WhisperTokenizer"

        // Unicode byte-to-char mapping used by Whisper's BPE
        // Whisper uses a modified byte-level BPE where bytes 0-255 are mapped to visible Unicode chars
        private val BYTE_DECODER: Map<Char, Byte> by lazy { buildByteDecoder() }

        private fun buildByteDecoder(): Map<Char, Byte> {
            val decoder = mutableMapOf<Char, Byte>()

            // Printable ASCII characters map to themselves
            // '!' (33) to '~' (126)
            for (b in 33..126) {
                decoder[b.toChar()] = b.toByte()
            }
            // '¡' (161) to '¬' (172)
            for (b in 161..172) {
                decoder[b.toChar()] = b.toByte()
            }
            // '®' (174) to 'ÿ' (255)
            for (b in 174..255) {
                decoder[b.toChar()] = b.toByte()
            }

            // Non-printable bytes are mapped to higher Unicode codepoints
            // Starting at U+0100 (256)
            var n = 256
            for (b in 0..255) {
                val byte = b.toByte()
                if (!decoder.values.contains(byte)) {
                    decoder[n.toChar()] = byte
                    n++
                }
            }

            return decoder
        }
    }

    // Vocabulary: token ID -> token string
    private var vocabulary: Array<String>? = null
    private var vocabSize: Int = 0

    actual suspend fun loadVocabulary(tokensPath: String): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val file = File(tokensPath)
                if (!file.exists()) {
                    Log.e(TAG, "Tokens file not found: $tokensPath")
                    return@withContext false
                }

                val tokens = mutableListOf<String>()

                BufferedReader(InputStreamReader(FileInputStream(file), Charsets.UTF_8)).use { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        tokens.add(line!!)
                    }
                }

                vocabulary = tokens.toTypedArray()
                vocabSize = tokens.size

                Log.d(TAG, "Loaded vocabulary with $vocabSize tokens from $tokensPath")
                true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load vocabulary", e)
                vocabulary = null
                vocabSize = 0
                false
            }
        }

    actual fun isReady(): Boolean = vocabulary != null

    actual fun vocabularySize(): Int = vocabSize

    actual fun decodeToken(tokenId: Int, includeSpecial: Boolean): String? {
        val vocab = vocabulary ?: return null

        // Check bounds
        if (tokenId < 0 || tokenId >= vocab.size) {
            return null
        }

        // Handle special tokens
        if (WhisperTokens.isSpecialToken(tokenId)) {
            return if (includeSpecial) {
                when (tokenId) {
                    WhisperTokens.EOT -> "<|endoftext|>"
                    WhisperTokens.SOT -> "<|startoftranscript|>"
                    WhisperTokens.EN -> "<|en|>"
                    WhisperTokens.TRANSCRIBE -> "<|transcribe|>"
                    WhisperTokens.TRANSLATE -> "<|translate|>"
                    WhisperTokens.NO_TIMESTAMPS -> "<|notimestamps|>"
                    else -> {
                        val ts = WhisperTokens.getTimestamp(tokenId)
                        if (ts != null) "<|%.2f|>".format(ts) else "<|special|>"
                    }
                }
            } else {
                null
            }
        }

        // Regular token - decode BPE
        return decodeBpeToken(vocab[tokenId])
    }

    actual fun decode(tokens: List<Int>, skipSpecialTokens: Boolean): String {
        val vocab = vocabulary ?: return ""

        val result = StringBuilder()

        for (tokenId in tokens) {
            if (tokenId < 0 || tokenId >= vocab.size) continue

            if (skipSpecialTokens && WhisperTokens.isSpecialToken(tokenId)) {
                continue
            }

            val tokenStr = vocab[tokenId]
            val decoded = decodeBpeToken(tokenStr)
            result.append(decoded)
        }

        return result.toString().trim()
    }

    actual fun decodeWithTimestamps(tokens: List<Int>): List<TimestampedSegment> {
        val vocab = vocabulary ?: return emptyList()
        val segments = mutableListOf<TimestampedSegment>()

        var currentText = StringBuilder()
        var currentStart: Float? = null
        var lastTimestamp: Float? = null

        for (tokenId in tokens) {
            if (tokenId < 0 || tokenId >= vocab.size) continue

            // Skip non-timestamp special tokens
            if (WhisperTokens.isSpecialToken(tokenId) && !WhisperTokens.isTimestampToken(tokenId)) {
                continue
            }

            // Handle timestamp tokens
            val timestamp = WhisperTokens.getTimestamp(tokenId)
            if (timestamp != null) {
                if (currentStart == null) {
                    // Start of a new segment
                    currentStart = timestamp
                } else {
                    // End of current segment
                    val text = currentText.toString().trim()
                    if (text.isNotEmpty()) {
                        segments.add(
                            TimestampedSegment(
                                text = text,
                                startTime = currentStart,
                                endTime = timestamp
                            )
                        )
                    }
                    currentText = StringBuilder()
                    currentStart = timestamp
                }
                lastTimestamp = timestamp
                continue
            }

            // Regular token
            val decoded = decodeBpeToken(vocab[tokenId])
            currentText.append(decoded)
        }

        // Handle remaining text
        val finalText = currentText.toString().trim()
        if (finalText.isNotEmpty()) {
            segments.add(
                TimestampedSegment(
                    text = finalText,
                    startTime = currentStart ?: lastTimestamp,
                    endTime = null
                )
            )
        }

        return segments
    }

    actual fun release() {
        vocabulary = null
        vocabSize = 0
    }

    /**
     * Decode a single BPE token string to text.
     * Handles the Ġ prefix and byte-level encoding.
     */
    private fun decodeBpeToken(token: String): String {
        if (token.isEmpty()) return ""

        // Convert token chars through byte decoder
        val bytes = ByteArray(token.length)
        var byteIdx = 0
        var valid = true

        for (char in token) {
            val byte = BYTE_DECODER[char]
            if (byte != null) {
                bytes[byteIdx++] = byte
            } else {
                // Character not in byte decoder - use direct mapping
                // This handles the Ġ (U+0120) prefix which maps to space (0x20)
                if (char == 'Ġ') {
                    bytes[byteIdx++] = ' '.code.toByte()
                } else if (char.code < 256) {
                    bytes[byteIdx++] = char.code.toByte()
                } else {
                    // Unknown character - likely a special symbol, skip
                    valid = false
                    break
                }
            }
        }

        return if (valid && byteIdx > 0) {
            String(bytes, 0, byteIdx, Charsets.UTF_8)
        } else {
            // Fallback: try direct interpretation
            token.replace('Ġ', ' ')
        }
    }
}
