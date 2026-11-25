package com.kmj.vea.engine

/**
 * Table-driven Hangul syllable -> phone-string conversion recovered from
 * G2PAManager_KOR.dll FUN_180007f80.
 *
 * This implements the static syllable/initial/vowel/final lookup and the
 * context rules visible in that function. It intentionally does not claim
 * to replace the upstream FUN_180007390 / FUN_180008730 preprocessing stages
 * that produce the per-code-unit flags and transformed lyric string.
 */
object KoreanHangulPhoneMapper {
    private val initialPhone = arrayOf(
        "", "", "g", "g'", "n", "d", "d'", "l",
        "m", "b", "b'", "s", "s'", "N", "c", "c'",
        "ch", "k", "t", "p", "h", "", "", "", "", "",
        "sh", "N", "r", "sh'", "", ""
    )

    private val vowelPhone = arrayOf(
        "", "", "", "a", "e", "ja", "je", "7",
        "", "", "e", "j7", "je", "o", "oa", "ue",
        "", "", "ue", "jo", "u", "u7", "ue", "ui",
        "", "", "ju", "M", "Mi", "i", "", ""
    )

    private val finalPhone = arrayOf(
        "", "", "gp", "gp", "gp", "np", "np", "np",
        "dp", "rp", "gp", "mp", "mp", "dp", "dp", "mp",
        "rp", "mp", "", "bp", "bp", "dp", "dp", "Np",
        "dp", "dp", "gp", "dp", "bp", "dp", "", ""
    )

    // Standard Hangul Unicode decomposition indices mapped to the vendor's
    // internal 0..31 consonant/vowel table indices.
    private val initialId = IntArray(19) { it + 2 }
    private val vowelId = intArrayOf(
        3, 4, 5, 6, 7, 10, 11, 12, 13, 14, 15,
        18, 19, 20, 21, 22, 23, 26, 27, 28, 29
    )
    private val finalId = intArrayOf(
        1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14,
        15, 16, 17, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29
    )

    private const val HANGUL_BASE = 0xAC00
    private const val HANGUL_LAST = 0xD7A3
    private const val VOWEL_TRANSFORM_MASK = 0x24881860

    /**
     * Returns the 16-bit syllable key stored in the recovered Korean DLL
     * record table. Null means the UTF-16 code unit is not a precomposed
     * Hangul syllable in U+AC00..U+D7A3.
     */
    fun internalSyllableCode(character: Char): Int? {
        val codePoint = character.code
        if (codePoint !in HANGUL_BASE..HANGUL_LAST) return null
        val offset = codePoint - HANGUL_BASE
        val initial = initialId[offset / 588]
        val vowel = vowelId[(offset % 588) / 28]
        val final = finalId[offset % 28]
        return 0x8000 or (initial shl 10) or (vowel shl 5) or final
    }

    private val initialOffsetById = IntArray(32) { -1 }.also { inverse ->
        initialId.forEachIndexed { offset, id -> inverse[id] = offset }
    }
    private val vowelOffsetById = IntArray(32) { -1 }.also { inverse ->
        vowelId.forEachIndexed { offset, id -> inverse[id] = offset }
    }
    private val finalOffsetById = IntArray(32) { -1 }.also { inverse ->
        finalId.forEachIndexed { offset, id -> inverse[id] = offset }
    }

    /**
     * Reverse of the vendor's FUN_180008660 code-to-Hangul lookup when the
     * lookup key is nonzero. Invalid/reserved feature IDs return null.
     *
     * The native table stores the key as 0x8000 | (initial << 10) |
     * (medial << 5) | final. The three axes use sparse vendor IDs, not the
     * raw Unicode decomposition indices.
     */
    fun syllableFromInternalCode(code: Int): Char? {
        if (code !in 0..0xffff || (code and 0x8000) == 0) return null

        val initialIdValue = (code ushr 10) and 0x1f
        val vowelIdValue = (code ushr 5) and 0x1f
        val finalIdValue = code and 0x1f
        val initialOffset = initialOffsetById[initialIdValue]
        val vowelOffset = vowelOffsetById[vowelIdValue]
        val finalOffset = finalOffsetById[finalIdValue]
        if (initialOffset < 0 || vowelOffset < 0 || finalOffset < 0) return null

        val codePoint = HANGUL_BASE + initialOffset * 588 +
            vowelOffset * 28 + finalOffset
        if (codePoint !in HANGUL_BASE..HANGUL_LAST) return null
        return codePoint.toChar()
    }

    /**
     * Implements the two directions of FUN_180008660 used by the Korean
     * manager: a zero key encodes a Hangul character; a nonzero key decodes
     * an internal syllable code. This helper has no side effects.
     */
    fun lookupInternalSyllable(character: Char): Int =
        internalSyllableCode(character) ?: 0

    fun lookupHangulSyllable(code: Int): Char =
        syllableFromInternalCode(code) ?: '\u0000'
    /**
     * Converts each supported precomposed Hangul syllable into one vendor
     * phone string. ASCII spaces are skipped. A hyphen is emitted only when
     * its corresponding source UTF-16 code-unit flag is 1, matching the
     * flag-gated path in FUN_180007f80.
     *
     * [sourceFlags] is optional, indexed by UTF-16 code-unit offset. When
     * omitted, all flags are zero. The upstream G2P flag generator has not
     * yet been ported, so callers must not treat this default as complete
     * punctuation/lexical processing.
     */
    fun phonemizeSyllables(
        text: String,
        sourceFlags: IntArray? = null
    ): List<String> {
        require(sourceFlags == null || sourceFlags.size == text.length) {
            "Korean G2P source flags must have one entry per UTF-16 code unit."
        }

        val result = ArrayList<String>()
        var previousWasSpace = false
        var previousHadFinal = false
        var previousWasOpenSyllable = false

        for (index in text.indices) {
            val character = text[index]
            if (character == ' ') {
                previousWasSpace = true
                continue
            }

            if (character == '-') {
                if (sourceFlags?.get(index) == 1) result += "-"
                previousWasSpace = false
                previousHadFinal = false
                previousWasOpenSyllable = false
                continue
            }

            val codePoint = character.code
            if (codePoint !in HANGUL_BASE..HANGUL_LAST) {
                // FUN_180007f80 ignores an unmapped character without changing
                // the cross-syllable context flags.
                continue
            }

            val syllableOffset = codePoint - HANGUL_BASE
            val initialOffset = syllableOffset / 588
            val vowelOffset = (syllableOffset % 588) / 28
            val finalOffset = syllableOffset % 28
            var initial = initialId[initialOffset]
            var vowel = vowelId[vowelOffset]
            val final = finalId[finalOffset]
            val hasFinal = finalPhone[final].isNotEmpty()

            if (previousWasSpace && initial == 13 && previousHadFinal) {
                initial = 27
            }
            previousWasSpace = false

            if (initial == 11 && vowel in 0..31 && isVowelTransformTarget(vowel)) {
                initial = 26
            } else if (initial == 12 && vowel in 0..31 && isVowelTransformTarget(vowel)) {
                initial = 29
            }
            if (vowel == 28 && hasFinal) vowel = 29
            if (previousWasOpenSyllable && initial == 7) initial = 28

            val tokenFlag = sourceFlags?.get(index) ?: 0
            val phoneString = when {
                initial == 13 && tokenFlag == 1 -> "-"
                initial == 13 && tokenFlag == 2 -> if (hasFinal) finalPhone[final] else "-"
                else -> {
                    val components = ArrayList<String>(3)
                    if (initial != 13) {
                        val onset = initialPhone[initial]
                        if (onset.isNotEmpty()) components += onset
                    }
                    val nucleus = vowelPhone[vowel]
                    if (nucleus.isNotEmpty()) components += nucleus
                    if (hasFinal) components += finalPhone[final]
                    components.joinToString(" ")
                }
            }
            if (phoneString.isNotEmpty()) result += phoneString

            previousHadFinal = hasFinal
            previousWasOpenSyllable = vowel > 0 && !hasFinal
        }
        return result
    }

    private fun isVowelTransformTarget(vowelIndex: Int): Boolean =
        ((VOWEL_TRANSFORM_MASK ushr vowelIndex) and 1) != 0
}
