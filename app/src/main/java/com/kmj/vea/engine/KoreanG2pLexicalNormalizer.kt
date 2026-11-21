package com.kmj.vea.engine

/**
 * Literal lexical pronunciation rewrites recovered from
 * G2PAManager_KOR.dll FUN_180007210. These replacements run in table order
 * before the later Korean syllable-normalization and phone-mapping helpers.
 */
object KoreanG2pLexicalNormalizer {
    private val rewrites = listOf(
        "디귿이" to "디그시",
        "디귿을" to "디그슬",
        "디귿에" to "디그세",
        "지읒이" to "지으시",
        "지읒을" to "지으슬",
        "지읒에" to "지으세",
        "치읓이" to "치으시",
        "치읓을" to "치으슬",
        "치읓에" to "치으세",
        "키읔이" to "키으기",
        "키읔을" to "키으글",
        "키읔에" to "키으게",
        "티읕이" to "티으시",
        "티읕을" to "티으슬",
        "티읕에" to "티으세",
        "피읖이" to "피으비",
        "피읖을" to "피으블",
        "피읖에" to "피으베",
        "히읗이" to "히으시",
        "히읗을" to "히으슬",
        "히읗에" to "히으세",
        "의견란" to "의견난",
        "임진란" to "임진난",
        "생산량" to "생산냥",
        "결단력" to "결딴녁",
        "공권력" to "공꿘녁",
        "동원령" to "동원녕",
        "상견례" to "상견녜",
        "횡단로" to "횡단노",
        "이원론" to "이원논",
        "입원료" to "이붠뇨",
        "구근류" to "구근뉴"
    )

    /** Applies the 32 native literal replacements in their recovered order. */
    fun rewriteKnownPronunciations(text: String): String {
        var result = text
        for ((source, replacement) in rewrites) {
            result = result.replace(source, replacement)
        }
        return result
    }

    /** Exposes an immutable snapshot for diagnostics and table-count tests. */
    fun rulesForTesting(): List<Pair<String, String>> = rewrites.toList()
}
