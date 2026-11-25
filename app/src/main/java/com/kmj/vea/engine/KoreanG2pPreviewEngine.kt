package com.kmj.vea.engine

/**
 * One lexical token processed by the recovered, partial Korean G2P path.
 *
 * [syllablePhoneGroups] is the direct output of FUN_180007f80: one string
 * per Hangul syllable. [phoneLabels] splits those groups into individual
 * labels suitable for inspection, not yet guaranteed to match the active
 * S5API language/category map.
 */
data class KoreanG2pPreviewToken(
    val originalToken: String,
    val lexicalForm: String,
    val syllablePhoneGroups: List<String>,
    val phoneLabels: List<String>,
    val ignoredCharacters: List<Char>,
    val containsUnresolvedHyphen: Boolean
)

/**
 * Explicit result type for the partial Korean phone mapper.
 *
 * This cannot be used as proof of a complete Yamaha G2P conversion:
 * FUN_180007390 and FUN_180008730, which build the per-code-unit source
 * flags and transformed input string, remain unported. The final syllable
 * mapper therefore runs with its zero-flag behavior.
 */
data class KoreanG2pPreviewResult(
    val sourceText: String,
    val normalizedText: String,
    val tokens: List<KoreanG2pPreviewToken>,
    val isComplete: Boolean = false
) {
    val phoneLabels: List<String>
        get() = tokens.flatMap { it.phoneLabels }

    val phonemeText: String
        get() = phoneLabels.joinToString(" ")

    val hasIgnoredCharacters: Boolean
        get() = tokens.any { it.ignoredCharacters.isNotEmpty() }

    val hasUnresolvedHyphens: Boolean
        get() = tokens.any { it.containsUnresolvedHyphen }

    val missingStages: List<String>
        get() = MISSING_STAGES

    companion object {
        private val MISSING_STAGES = listOf(
            "FUN_180007390: Korean source-character flag generation",
            "FUN_180008730: intermediate lyric/string transformation"
        )
    }
}

/**
 * Preview-only Korean G2P path using the three recovered steps currently
 * represented in Kotlin:
 *
 * 1. Korean-manager punctuation normalization and token boundaries.
 * 2. The 32 literal lexical pronunciation rewrites from FUN_180007210.
 * 3. Hangul syllable-to-phone table and context rules from FUN_180007f80.
 *
 * It intentionally does not generate missing source flags, infer timing,
 * or declare its output to be a production Yamaha phonemizer.
 */
object KoreanG2pPreviewEngine {
    fun preview(lyric: String): KoreanG2pPreviewResult {
        val normalized = NativeG2pBridge.normalizeKoreanInput(lyric)
        val sourceTokens = KoreanG2pInputTokenizer.tokenize(normalized)
        val previewTokens = sourceTokens.map { token ->
            val lexical = KoreanG2pLexicalNormalizer.rewriteKnownPronunciations(token)
            val groups = KoreanHangulPhoneMapper.phonemizeSyllables(lexical)
            val labels = groups.flatMap { group ->
                group.trim().split(Regex("\\s+")).filter(String::isNotEmpty)
            }
            val ignored = lexical
                .filterNot { character ->
                    character.code in HANGUL_FIRST..HANGUL_LAST || character == '-'
                }
                .distinct()
            KoreanG2pPreviewToken(
                originalToken = token,
                lexicalForm = lexical,
                syllablePhoneGroups = groups,
                phoneLabels = labels,
                ignoredCharacters = ignored,
                containsUnresolvedHyphen = '-' in lexical
            )
        }
        return KoreanG2pPreviewResult(
            sourceText = lyric,
            normalizedText = normalized,
            tokens = previewTokens,
            isComplete = false
        )
    }

    private const val HANGUL_FIRST = 0xAC00
    private const val HANGUL_LAST = 0xD7A3
}
