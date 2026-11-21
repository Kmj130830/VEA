package com.kmj.vea.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class KoreanG2pLexicalNormalizerTest {
    @Test
    fun containsTheThirtyTwoRecoveredKoreanPronunciationRules() {
        assertEquals(32, KoreanG2pLexicalNormalizer.rulesForTesting().size)
    }

    @Test
    fun rewritesKoreanConsonantNameInflections() {
        assertEquals("디그시 디그슬 디그세", KoreanG2pLexicalNormalizer.rewriteKnownPronunciations("디귿이 디귿을 디귿에"))
        assertEquals("지으시 치으슬 키으게 티으세 피으블 히으시", KoreanG2pLexicalNormalizer.rewriteKnownPronunciations("지읒이 치읓을 키읔에 티읕에 피읖을 히읗이"))
    }

    @Test
    fun rewritesListedLexicalAssimilationExceptions() {
        assertEquals(
            "의견난 임진난 생산냥 결딴녁 공꿘녁 동원녕 상견녜 횡단노 이원논 이붠뇨 구근뉴",
            KoreanG2pLexicalNormalizer.rewriteKnownPronunciations(
                "의견란 임진란 생산량 결단력 공권력 동원령 상견례 횡단로 이원론 입원료 구근류"
            )
        )
    }

    @Test
    fun replacesAllRepeatedOccurrencesAndLeavesOtherTextUntouched() {
        assertEquals(
            "디그시/디그시 가나다 123",
            KoreanG2pLexicalNormalizer.rewriteKnownPronunciations("디귿이/디귿이 가나다 123")
        )
    }

    @Test
    fun preservesUnmatchedWhitespaceAndPunctuation() {
        val input = "  의견란\t임진란\n!"
        assertEquals("  의견난\t임진난\n!", KoreanG2pLexicalNormalizer.rewriteKnownPronunciations(input))
    }
}
