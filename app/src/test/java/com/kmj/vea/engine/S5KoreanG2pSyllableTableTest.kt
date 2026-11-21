package com.kmj.vea.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class S5KoreanG2pSyllableTableTest {
    @Test
    fun decomposesHangulIntoNativePackedIndices() {
        assertEquals(S5KoreanG2pSyllable(2, 3, 1), S5KoreanG2pSyllableTable.decompose('가'.code))
        assertEquals(S5KoreanG2pSyllable(2, 3, 2), S5KoreanG2pSyllableTable.decompose('각'.code))
        assertEquals(S5KoreanG2pSyllable(20, 3, 5), S5KoreanG2pSyllableTable.decompose('한'.code))
        assertEquals(S5KoreanG2pSyllable(2, 27, 9), S5KoreanG2pSyllableTable.decompose('글'.code))
        assertEquals(19, S5KoreanG2pSyllableTable.decompose('갑'.code)?.codaIndex)
        assertEquals(20, S5KoreanG2pSyllableTable.decompose('값'.code)?.codaIndex)
        assertEquals(23, S5KoreanG2pSyllableTable.decompose('강'.code)?.codaIndex)
        assertEquals(29, S5KoreanG2pSyllableTable.decompose('힣'.code)?.codaIndex)
    }

    @Test
    fun packsTheNativeHighFlagAndNonContiguousCodaIndices() {
        assertEquals(0x8861, S5KoreanG2pSyllableTable.pack(S5KoreanG2pSyllable(2, 3, 1)))
        assertEquals(0x8873, S5KoreanG2pSyllableTable.pack(S5KoreanG2pSyllable(2, 3, 19)))
        assertEquals(0x8877, S5KoreanG2pSyllableTable.pack(S5KoreanG2pSyllable(2, 3, 23)))
    }

    @Test
    fun exposesTheRecoveredYamahaAliasesWithoutApplyingSoundChanges() {
        val syllable = S5KoreanG2pSyllableTable.decompose('글'.code)!!
        assertEquals("g", S5KoreanG2pSyllableTable.onsetAlias(syllable.onsetIndex))
        assertEquals("M", S5KoreanG2pSyllableTable.vowelAlias(syllable.vowelIndex))
        assertEquals("rp", S5KoreanG2pSyllableTable.codaAlias(syllable.codaIndex))
    }

    @Test
    fun rejectsNonHangulSyllables() {
        assertNull(S5KoreanG2pSyllableTable.decompose('A'.code))
        assertNull(S5KoreanG2pSyllableTable.decompose('ㄱ'.code))
    }

    @Test
    fun roundTripsEveryModernHangulSyllableThroughNativeIndices() {
        for (codePoint in 0xAC00..0xD7A3) {
            val decomposition = S5KoreanG2pSyllableTable.decompose(codePoint)
            assertEquals(codePoint, S5KoreanG2pSyllableTable.compose(decomposition!!))
        }
    }
}
