package com.kmj.vea.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
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
    fun exposesRecoveredInternalSyllableKeys() {
        assertEquals(0x8861, KoreanHangulPhoneMapper.internalSyllableCode('가'))
        assertEquals(0x8862, KoreanHangulPhoneMapper.internalSyllableCode('각'))
        assertEquals(null, KoreanHangulPhoneMapper.internalSyllableCode('A'))
    }

    @Test
    fun internalKeyFieldsCoverEveryPrecomposedHangulSyllable() {
        for (codePoint in 0xAC00..0xD7A3) {
            val key = KoreanHangulPhoneMapper.internalSyllableCode(codePoint.toChar())
            assertNotNull(key)
            assertEquals(0x8000, key!! and 0x8000)
            assertTrue(((key ushr 10) and 0x1f) in 2..20)
            assertTrue(((key ushr 5) and 0x1f) in setOf(3, 4, 5, 6, 7, 10, 11, 12, 13, 14, 15, 18, 19, 20, 21, 22, 23, 26, 27, 28, 29))
            assertTrue((key and 0x1f) in (1..17).toSet() + (19..29).toSet())
        }
    }
    @Test
    fun reversesEveryVendorInternalCodeToItsOriginalHangulSyllable() {
        for (codePoint in 0xAC00..0xD7A3) {
            val source = codePoint.toChar()
            val code = KoreanHangulPhoneMapper.internalSyllableCode(source)
            assertNotNull("missing vendor code for U+${codePoint.toString(16)}", code)
            assertEquals(
                "round-trip failed for U+${codePoint.toString(16)}",
                source,
                KoreanHangulPhoneMapper.syllableFromInternalCode(code!!)
            )
            assertEquals(
                source,
                KoreanHangulPhoneMapper.lookupHangulSyllable(code)
            )
        }
    }

    @Test
    fun returnsNullForReservedOrMalformedInternalSyllableCodes() {
        listOf(
            0,
            0x8000,
            0xFFFF,
            0x8800,
            0x8860,
            0x9001,
            -1,
            0x1_0000
        ).forEach { code ->
            assertEquals(null, KoreanHangulPhoneMapper.syllableFromInternalCode(code))
        }
        assertEquals('\u0000', KoreanHangulPhoneMapper.lookupHangulSyllable(0x8000))
    }

    @Test
    fun mirrorsNativeCharacterToCodeAndCodeToCharacterLookup() {
        assertEquals(0x8861, KoreanHangulPhoneMapper.lookupInternalSyllable('가'))
        assertEquals('가', KoreanHangulPhoneMapper.lookupHangulSyllable(0x8861))
        assertEquals(0, KoreanHangulPhoneMapper.lookupInternalSyllable('A'))
        assertEquals('\u0000', KoreanHangulPhoneMapper.lookupHangulSyllable(0x1234))
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
            listOf("g a", "r a"),
            KoreanHangulPhoneMapper.phonemizeSyllables("가 ! 라")
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
        assertEquals(listOf("gp"), KoreanHangulPhoneMapper.phonemizeSyllables("악", intArrayOf(2)))
    }

    @Test
    fun rejectsMisalignedSourceFlagArray() {
        assertThrows(IllegalArgumentException::class.java) {
            KoreanHangulPhoneMapper.phonemizeSyllables("가나다", intArrayOf(1))
        }
    }
}
