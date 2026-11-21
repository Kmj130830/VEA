package com.kmj.vea.engine

/**
 * Exact Hangul syllable decomposition and raw alias tables recovered from
 * Yamaha G2PAManager_KOR.dll. Context-sensitive sound changes are deliberately
 * not performed here; they are separate native passes.
 */
internal data class S5KoreanG2pSyllable(
    val onsetIndex: Int,
    val vowelIndex: Int,
    val codaIndex: Int
)

internal object S5KoreanG2pSyllableTable {
    private val nativeVowelIndices = intArrayOf(
        3, 4, 5, 6, 7, 10, 11, 12, 13, 14, 15,
        18, 19, 20, 21, 22, 23, 26, 27, 28, 29
    )

    private val onsetAliases = arrayOf(
        null, null, "g", "g'", "n", "d", "d'", "l",
        "m", "b", "b'", "s", "s'", "N", "c", "c'",
        "ch", "k", "t", "p", "h", null, null, null,
        null, null, "sh", "N", "r", "sh'", null, null
    )

    private val vowelAliases = arrayOf(
        null, null, null, "a", "e", "ja", "je", "7",
        null, null, "e", "j7", "je", "o", "oa", "ue",
        null, null, "ue", "jo", "u", "u7", "ue", "ui",
        null, null, "ju", "M", "Mi", "i", null, null
    )

    private val codaAliases = arrayOf(
        null, null, "gp", "gp", "gp", "np", "np", "np",
        "dp", "rp", "gp", "mp", "mp", "dp", "dp", "mp",
        "rp", "mp", null, "bp", "bp", "dp", "dp", "Np",
        "dp", "dp", "gp", "dp", "bp", "dp", null, null
    )

    fun decompose(codePoint: Int): S5KoreanG2pSyllable? {
        if (codePoint !in 0xAC00..0xD7A3) return null
        val syllableOffset = codePoint - 0xAC00
        val initial = syllableOffset / (21 * 28)
        val medial = (syllableOffset % (21 * 28)) / 28
        val final = syllableOffset % 28
        return S5KoreanG2pSyllable(
            onsetIndex = initial + 2,
            vowelIndex = nativeVowelIndices[medial],
            codaIndex = final + 1
        )
    }

    fun onsetAlias(index: Int): String? = onsetAliases.getOrNull(index)
    fun vowelAlias(index: Int): String? = vowelAliases.getOrNull(index)
    fun codaAlias(index: Int): String? = codaAliases.getOrNull(index)
}
