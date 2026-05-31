package dev.knuth.earshot

/**
 * Whisper special token IDs.
 * These are standardized across Whisper models.
 */
object WhisperTokens {
    const val EOT = 50257         // End of transcript
    const val SOT = 50258         // Start of transcript
    const val EN = 50259          // English language token
    const val TRANSCRIBE = 50359  // Transcribe task token
    const val TRANSLATE = 50360   // Translate task token
    const val NO_TIMESTAMPS = 50361

    // Timestamp tokens: 50364 + (time_in_seconds * 2)
    const val TIMESTAMP_BEGIN = 50364

    /**
     * Check if a token is a special token (not part of regular vocabulary).
     */
    fun isSpecialToken(tokenId: Int): Boolean = tokenId >= EOT

    /**
     * Check if a token is a timestamp token.
     */
    fun isTimestampToken(tokenId: Int): Boolean = tokenId >= TIMESTAMP_BEGIN

    /**
     * Get timestamp in seconds from a timestamp token.
     * Returns null if not a timestamp token.
     */
    fun getTimestamp(tokenId: Int): Float? {
        return if (tokenId >= TIMESTAMP_BEGIN) {
            (tokenId - TIMESTAMP_BEGIN) * 0.02f  // 20ms precision
        } else {
            null
        }
    }
}

/**
 * Platform-specific Whisper BPE tokenizer.
 * Handles decoding of token IDs to text using the vocabulary from tokens.txt.
 *
 * The tokenizer:
 * - Loads vocabulary from tokens.txt (one token per line, line number = token ID)
 * - Decodes BPE tokens, handling the Ġ prefix (represents leading space)
 * - Filters out special tokens during decoding
 */
expect class WhisperTokenizer {
    /**
     * Load vocabulary from a tokens.txt file.
     *
     * @param tokensPath Path to the tokens.txt file
     * @return true if loading succeeded
     */
    suspend fun loadVocabulary(tokensPath: String): Boolean

    /**
     * Check if vocabulary is loaded and ready.
     */
    fun isReady(): Boolean

    /**
     * Get the vocabulary size (number of tokens).
     */
    fun vocabularySize(): Int

    /**
     * Decode a single token ID to its string representation.
     * Returns null for special tokens or invalid IDs.
     *
     * @param tokenId The token ID to decode
     * @param includeSpecial If true, include special token markers in output
     * @return The decoded string, or null if invalid/special
     */
    fun decodeToken(tokenId: Int, includeSpecial: Boolean = false): String?

    /**
     * Decode a sequence of token IDs to text.
     * Special tokens are filtered out by default.
     *
     * @param tokens List of token IDs
     * @param skipSpecialTokens If true, skip special tokens in output
     * @return The decoded text
     */
    fun decode(tokens: List<Int>, skipSpecialTokens: Boolean = true): String

    /**
     * Decode tokens and return segments with timestamps.
     * Useful for subtitles or word-level timing.
     *
     * @param tokens List of token IDs (may include timestamp tokens)
     * @return List of text segments with optional timestamps
     */
    fun decodeWithTimestamps(tokens: List<Int>): List<TimestampedSegment>

    /**
     * Release resources.
     */
    fun release()
}

/**
 * A text segment with optional timestamp information.
 */
data class TimestampedSegment(
    val text: String,
    val startTime: Float? = null,  // seconds
    val endTime: Float? = null     // seconds
)
