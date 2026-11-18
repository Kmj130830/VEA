package com.kmj.vea.data

import org.junit.Assert.assertEquals
import org.junit.Test

class XsampaMapperTest {
    @Test
    fun mapsSingleRomajiMora() {
        assertEquals("ら", XsampaMapper.romanToHiragana("ra"))
        assertEquals("4 a", XsampaMapper.convert("ra", "jpn"))
    }

    @Test
    fun mapsDigraphRomaji() {
        assertEquals("し", XsampaMapper.romanToHiragana("shi"))
        assertEquals("S i", XsampaMapper.convert("shi", "jpn"))
    }

    @Test
    fun mapsDoubleNInKonnichiwa() {
        assertEquals("こんにちわ", XsampaMapper.romanToHiragana("konnichiwa"))
        assertEquals("k o N J i tS i w a", XsampaMapper.convert("konnichiwa", "jpn"))
    }

    @Test
    fun mapsSokuonInGakkou() {
        assertEquals("がっこう", XsampaMapper.romanToHiragana("gakkou"))
        assertEquals("g a k k o M", XsampaMapper.convert("gakkou", "jpn"))
    }

    @Test
    fun preservesOriginalLyricScriptOutsideMapper() {
        val lyric = "ra"
        val phoneme = XsampaMapper.convert(lyric, "jpn")
        assertEquals("ra", lyric)
        assertEquals("4 a", phoneme)
    }
}
