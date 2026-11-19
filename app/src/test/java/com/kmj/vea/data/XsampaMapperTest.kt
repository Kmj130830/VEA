package com.kmj.vea.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class XsampaMapperTest {
    @Test
    fun mapsJapaneseRomaji() {
        assertEquals("ら", XsampaMapper.romanToHiragana("ra"))
        assertEquals("4 a", XsampaMapper.convert("ra", "jpn"))
        assertEquals("し", XsampaMapper.romanToHiragana("shi"))
        assertEquals("S i", XsampaMapper.convert("shi", "jpn"))
        assertEquals("こんにちわ", XsampaMapper.romanToHiragana("konnichiwa"))
        assertEquals("がっこう", XsampaMapper.romanToHiragana("gakkou"))
    }

    @Test
    fun composesKoreanCompatibilityJamoBeforeG2p() {
        assertEquals("음", XsampaMapper.composeKoreanJamo("ㅇㅡㅁ"))
        assertEquals("M m", XsampaMapper.convert("음", "kor"))
        assertEquals("M m", XsampaMapper.convert("ㅇㅡㅁ", "kor"))
    }

    @Test
    fun keepsKoreanSyllableOnsetVowelAndCodaTogether() {
        assertEquals("k wa", XsampaMapper.convert("과", "kor"))
        assertEquals("h a n", XsampaMapper.convert("한", "kor"))
    }

    @Test
    fun mapsEnglishMeToVoicebankFriendlyDefault() {
        assertEquals("m i:", XsampaMapper.convert("me", "eng"))
        assertEquals(listOf("m i:", "m e"), XsampaMapper.pronunciationSuggestions("me", "eng"))
    }

    @Test
    fun offersMultiplePronunciationsForAmbiguousEnglishWords() {
        assertEquals(2, XsampaMapper.pronunciationSuggestions("read", "eng").size)
        assertEquals(2, XsampaMapper.pronunciationSuggestions("live", "eng").size)
        assertEquals(2, XsampaMapper.pronunciationSuggestions("wind", "eng").size)
        assertEquals(2, XsampaMapper.pronunciationSuggestions("lead", "eng").size)
        assertEquals(2, XsampaMapper.pronunciationSuggestions("use", "eng").size)
    }

    @Test
    fun combinesAlternativesAcrossAWholePhrase() {
        val choices = XsampaMapper.pronunciationSuggestions("read me", "eng")
        assertEquals(4, choices.size)
        assertTrue(choices.all { it.contains(" | ") })
    }

    @Test
    fun fallsBackToGraphemeToPhonemeRulesForOtherWords() {
        val hello = XsampaMapper.convert("hello", "eng")
        assertTrue(hello.contains("h"))
        assertTrue(hello.contains("oU"))
        assertTrue(XsampaMapper.pronunciationSuggestions("computer", "eng").isNotEmpty())
    }
    @Test
    fun keepsKoreanSyllableAndWordBoundaries() {
        assertEquals("h a n . k M l", XsampaMapper.convert("한글", "kor"))
        assertEquals("h a n . k M l | k a", XsampaMapper.convert("한글 가", "kor"))
        assertEquals("h a n . k M l | k a", XsampaMapper.convert("한글   가", "kor"))
        assertEquals("h a n . k M l", XsampaMapper.convert("ㅎㅏㄴㄱㅡㄹ", "kor"))
    }

    @Test
    fun showsPhonemeSuggestionsForJapaneseAndKorean() {
        assertEquals(listOf("S i"), XsampaMapper.pronunciationSuggestions("shi", "jpn"))
        assertEquals(
            listOf("h a n . k M l"),
            XsampaMapper.pronunciationSuggestions("한글", "kor")
        )
    }

    @Test
    fun doesNotTreatChineseCharactersAsXsampa() {
        assertEquals("", XsampaMapper.convert("你好", "zho"))
        assertTrue(XsampaMapper.pronunciationSuggestions("你好", "zho").isEmpty())
    }

}
