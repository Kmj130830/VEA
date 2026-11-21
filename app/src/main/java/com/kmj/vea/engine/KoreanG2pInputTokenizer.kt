package com.kmj.vea.engine

/**
 * The two UTF-16 string fields produced by G2PAManager_KOR.dll
 * FUN_1800034e0 before dispatching an input token to its internal module.
 * The native helper stores the first 30 UTF-16 code units in [prefix30] and
 * the remaining code units in [remainder]. The boundary is a code-unit
 * boundary, so a surrogate pair may be split between these two fields.
 */
data class KoreanG2pTokenFields(
    val prefix30: String,
    val remainder: String
) {
    val combined: String get() = prefix30 + remainder
}

/**
 * Token boundary rules recovered from G2PAManager_KOR.dll FUN_180003310.
 *
 * This is deliberately separate from VsmLyricTokenizer: the Korean G2P
 * parser treats U+0020, U+3000, U+0009 and U+000A as delimiters. It does not
 * split on carriage return, non-breaking space, or the wider Unicode
 * whitespace category. Empty runs are discarded.
 */
object KoreanG2pInputTokenizer {
    /**
     * Applies the Korean manager's confirmed U+30FC/U+2212 normalization
     * before splitting into tokens using its exact native delimiter set.
     */
    fun normalizeAndTokenize(lyric: String): List<String> =
        tokenize(NativeG2pBridge.normalizeKoreanInput(lyric))

    /** Splits already-normalized Korean G2P input without changing contents. */
    fun tokenize(lyric: String): List<String> {
        if (lyric.isEmpty()) return emptyList()

        val tokens = ArrayList<String>()
        var tokenStart = -1
        for (index in lyric.indices) {
            if (isNativeDelimiter(lyric[index])) {
                if (tokenStart >= 0) {
                    tokens += lyric.substring(tokenStart, index)
                    tokenStart = -1
                }
            } else if (tokenStart < 0) {
                tokenStart = index
            }
        }
        if (tokenStart >= 0) tokens += lyric.substring(tokenStart)
        return tokens
    }

    /**
     * Builds the two-field input record used by the Korean internal module.
     * Kotlin String indexing is UTF-16 code-unit based, matching the native
     * wchar_t buffer slicing at offset 0x1E.
     */
    fun splitTokenForNativeModule(token: String): KoreanG2pTokenFields {
        val prefixLength = minOf(token.length, NATIVE_PREFIX_CODE_UNITS)
        return KoreanG2pTokenFields(
            prefix30 = token.substring(0, prefixLength),
            remainder = token.substring(prefixLength)
        )
    }

    /** Normalizes, tokenizes, then converts each token to the native two-field record. */
    fun prepareNativeTokenFields(lyric: String): List<KoreanG2pTokenFields> =
        normalizeAndTokenize(lyric).map(::splitTokenForNativeModule)

    private const val NATIVE_PREFIX_CODE_UNITS = 30
    private fun isNativeDelimiter(character: Char): Boolean =
        character == '\u0020' || character == '\u3000' ||
            character == '\u0009' || character == '\u000A'
}
