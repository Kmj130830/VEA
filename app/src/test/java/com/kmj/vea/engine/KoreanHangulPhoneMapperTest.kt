package com.kmj.vea.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class KoreanHangulPhoneMapperTest {
    @Test
    fun mapsBasicHangulOnsetVowelAndCodaFromTheRecoveredTables() {
        assertEquals(listOf("g a"), KoreanHangulPhoneMapper.phonemizeSyllables("가"))
        assertEquals(listOf("g a gp"), KoreanHangulPhoneMapper.phonemizeSyllables("각"))
        assertEquals(listOf("a"), KoreanHangulPhoneMapper.phonemizeSyllables("아"))
        assertEquals(listOf("a Np"), KoreanHangulPhoneMapper.phonemizeSyllables("앙"))
    }

    @Test
    fun mapsAllStandardHangulVowelAndFinalIndices() {
        val text = buildString {
            for (codePoint in 0xAC00..0xD7A3) append(codePoint.toChar())
        }
        val outputs = KoreanHangulPhoneMapper.phonemizeSyllables(text)
        assertEquals(11172, outputs.size)
        assertEquals("g a", outputs.first())
    }

    @Test
    fun skipsUnsupportedCharactersAndAsciiSpaceLikeTheNativeStage() {
        assertEquals(
            listOf("g a", "g a gp"),
            KoreanHangulPhoneMapper.phonemizeSyllables("가 ! 각")
        )
    }

    @Test
    fun appliesTheRecoveredSibilantVowelRule() {
        assertEquals(listOf("sh i"), KoreanHangulPhoneMapper.phonemizeSyllables("시"))
        assertEquals(listOf("sh' i"), KoreanHangulPhoneMapper.phonemizeSyllables("씨"))
        assertEquals(listOf("sh ja"), KoreanHangulPhoneMapper.phonemizeSyllables("샤"))
    }

    @Test
    fun appliesOpenSyllableLiquidRuleAndSpaceFinalRule() {
        assertEquals(listOf("g a", "r a"), KoreanHangulPhoneMapper.phonemizeSyllables("가라"))
        assertEquals(listOf("g a gp", "N a"), KoreanHangulPhoneMapper.phonemizeSyllables("각 아"))
    }

    @Test
    fun handlesFlaggedHyphensAndNullOnsetFlagTwo() {
        assertEquals(listOf("-"), KoreanHangulPhoneMapper.phonemizeSyllables("-", intArrayOf(1)))
        assertEquals(listOf("gp"), KoreanHangulPhoneMapper.phonemizeSyllables("악", intArrayOf(0, 2)))
    }

    @Test
    fun rejectsMisalignedSourceFlagArray() {
        assertThrows(IllegalArgumentException::class.java) {
            KoreanHangulPhoneMapper.phonemizeSyllables("가나다", intArrayOf(1))
        }
    }
}
