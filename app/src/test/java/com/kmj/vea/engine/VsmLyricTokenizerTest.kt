package com.kmj.vea.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class VsmLyricTokenizerTest {
    @Test
    fun splitsOnlyOnAsciiSpaceAndPreservesTokenOrder() {
        assertEquals(
            listOf("hello", "世界", "ka", "きゃ"),
            VsmLyricTokenizer.tokenize("hello 世界 ka きゃ")
        )
    }

    @Test
    fun ignoresEmptyTokensFromLeadingTrailingAndRepeatedSpaces() {
        assertEquals(
            listOf("a", "b", "c"),
            VsmLyricTokenizer.tokenize("  a   b c   ")
        )
    }

    @Test
    fun tabsAndOtherWhitespaceAreNotDelimiters() {
        assertEquals(
            listOf("a\tb", "c\nd", "e\u00a0f"),
            VsmLyricTokenizer.tokenize("a\tb c\nd e\u00a0f")
        )
    }

    @Test
    fun emptyOrAllSpaceInputProducesNoTokens() {
        assertEquals(emptyList<String>(), VsmLyricTokenizer.tokenize(""))
        assertEquals(emptyList<String>(), VsmLyricTokenizer.tokenize("     "))
    }
}
