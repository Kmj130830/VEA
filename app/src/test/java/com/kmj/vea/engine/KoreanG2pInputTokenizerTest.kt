package com.kmj.vea.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KoreanG2pInputTokenizerTest {
    @Test
    fun splitsOnTheFourExactNativeKoreanG2pDelimiters() {
        assertEquals(
            listOf("가", "나", "다", "라", "마"),
            KoreanG2pInputTokenizer.tokenize("가 나\u3000다\t라\n마")
        )
    }

    @Test
    fun collapsesRunsAndIgnoresLeadingAndTrailingDelimiters() {
        assertEquals(
            listOf("first", "second"),
            KoreanG2pInputTokenizer.tokenize(" \t\u3000\nfirst\n \tsecond\u3000 ")
        )
    }

    @Test
    fun doesNotTreatCarriageReturnOrNonBreakingSpaceAsNativeDelimiters() {
        assertEquals(
            listOf("a\rb", "c\u00A0d"),
            KoreanG2pInputTokenizer.tokenize("a\rb c\u00A0d")
        )
    }

    @Test
    fun emptyOrDelimiterOnlyInputReturnsNoTokens() {
        assertEquals(emptyList<String>(), KoreanG2pInputTokenizer.tokenize(""))
        assertEquals(emptyList<String>(), KoreanG2pInputTokenizer.tokenize(" \t\u3000\n"))
    }

    @Test
    fun normalizesKoreanManagerLongMarksBeforeTokenization() {
        assertEquals(
            listOf("가-나", "다-라"),
            KoreanG2pInputTokenizer.normalizeAndTokenize("가ー나 다−라")
        )
    }

    @Test
    fun mirrorsNativeThirtyCodeUnitPrefixAndRemainderBoundary() {
        val exactlyThirty = KoreanG2pInputTokenizer.splitTokenForNativeModule("가".repeat(30))
        assertEquals(30, exactlyThirty.prefix30.length)
        assertEquals("", exactlyThirty.remainder)

        val thirtyOne = KoreanG2pInputTokenizer.splitTokenForNativeModule("나".repeat(31))
        assertEquals(30, thirtyOne.prefix30.length)
        assertEquals(1, thirtyOne.remainder.length)
        assertEquals("나".repeat(31), thirtyOne.combined)
    }

    @Test
    fun preservesLongTokensWithoutTruncatingOverflowField() {
        val token = "음".repeat(95)
        val actual = KoreanG2pInputTokenizer.splitTokenForNativeModule(token)
        assertEquals(30, actual.prefix30.length)
        assertEquals(65, actual.remainder.length)
        assertEquals(token, actual.combined)
    }

    @Test
    fun splitsAtThirtyUtf16UnitsEvenWhenThatBoundaryBisectsASurrogatePair() {
        val token = "가".repeat(29) + "😀" + "나"

        val actual = KoreanG2pInputTokenizer.splitTokenForNativeModule(token)

        assertEquals(30, actual.prefix30.length)
        assertEquals(2, actual.remainder.length)
        assertTrue(actual.prefix30.last().isHighSurrogate())
        assertTrue(actual.remainder.first().isLowSurrogate())
        assertEquals(token, actual.combined)
    }

    @Test
    fun buildsNativeTokenFieldsAfterNormalizationAndTokenization() {
        val actual = KoreanG2pInputTokenizer.prepareNativeTokenFields("가ー나 다−라")
        assertEquals(listOf("가-나", "다-라"), actual.map { it.combined })
        assertTrue(actual.all { it.prefix30.isNotEmpty() && it.remainder.isEmpty() })
    }
    @Test
    fun preservesRepeatedPunctuationAndInternalWhitespaceOutsideDelimiterSet() {
        assertEquals(
            listOf("가!!!", "나\rb"),
            KoreanG2pInputTokenizer.tokenize("가!!! 나\rb")
        )
    }
}
