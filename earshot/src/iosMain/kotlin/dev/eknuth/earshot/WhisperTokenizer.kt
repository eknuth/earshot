package dev.eknuth.earshot

/**
 * iOS implementation of WhisperTokenizer.
 *
 * Intentionally left as a no-op stub (EDW-975): on iOS, WhisperKit performs
 * tokenization internally, so the shared module never drives tokenization. The
 * Swift [NativeTranscriptionProvider] returns already-decoded transcript text.
 */
actual class WhisperTokenizer {

    actual suspend fun loadVocabulary(tokensPath: String): Boolean = false

    actual fun isReady(): Boolean = false

    actual fun vocabularySize(): Int = 0

    actual fun decodeToken(tokenId: Int, includeSpecial: Boolean): String? = null

    actual fun decode(tokens: List<Int>, skipSpecialTokens: Boolean): String = ""

    actual fun decodeWithTimestamps(tokens: List<Int>): List<TimestampedSegment> = emptyList()

    actual fun release() {}
}
