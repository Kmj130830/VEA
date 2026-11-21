package com.kmj.vea.engine

import org.junit.Assert.assertEquals
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
    fun preservesRepeatedPunctuationAndInternalWhitespaceOutsideDelimiterSet() {
        assertEquals(
            listOf("가!!!", "나\rb"),
            KoreanG2pInputTokenizer.tokenize("가!!! 나\rb")
        )
    }
}
