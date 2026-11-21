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
}
