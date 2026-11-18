package com.kmj.vea.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StandardMidiExporterTest {
    @Test
    fun writesStandardMidiHeaderAndDivision() {
        val bytes = StandardMidiExporter.toByteArray(
            VeaProject(
                bpm = 120,
                tracks = listOf(
                    VeaTrack(
                        name = "Main",
                        clips = listOf(
                            VeaClip(
                                name = "Clip",
                                startBar = 1,
                                lengthBars = 1,
                                notes = listOf(VeaNote(startTick = 0, lengthTick = 480, pitch = 60, lyric = "あ"))
                            )
                        )
                    )
                )
            )
        )
        assertEquals("MThd", String(bytes.copyOfRange(0, 4), Charsets.US_ASCII))
        assertEquals(0x00, bytes[8].toInt() and 0xFF)
        assertEquals(0x01, bytes[9].toInt() and 0xFF) // format 1
        assertEquals(0x01, bytes[12].toInt() and 0xFF)
        assertEquals(0xE0, bytes[13].toInt() and 0xFF) // 480 ticks per quarter note
        assertTrue(bytes.indexOf(0x90.toByte()) >= 0)
    }

    @Test
    fun writesUtf8LyricsAndNoteEvents() {
        val bytes = StandardMidiExporter.toByteArray(
            VeaProject(
                tracks = listOf(
                    VeaTrack(
                        name = "AI Megpoid",
                        clips = listOf(
                            VeaClip(
                                name = "Main",
                                startBar = 1,
                                lengthBars = 1,
                                notes = listOf(VeaNote(startTick = 0, lengthTick = 480, pitch = 60, lyric = "音"))
                            )
                        )
                    )
                )
            )
        )
        val utf8 = "音".toByteArray(Charsets.UTF_8)
        val lyricMeta = byteArrayOf(0xFF.toByte(), 0x05, 0x03) + utf8
        assertTrue(indexOf(bytes, lyricMeta) >= 0)
        assertTrue(indexOf(bytes, byteArrayOf(0x90.toByte(), 60, 100)) >= 0)
        assertTrue(indexOf(bytes, byteArrayOf(0x80.toByte(), 60, 0)) >= 0)
    }

    @Test
    fun convertsJapaneseRomajiToHiraganaForVocaloidG2p() {
        val bytes = StandardMidiExporter.toByteArray(
            VeaProject(
                tracks = listOf(
                    VeaTrack(
                        name = "Japanese Vocal",
                        language = "jpn",
                        clips = listOf(
                            VeaClip(
                                name = "Main",
                                startBar = 1,
                                lengthBars = 1,
                                notes = listOf(VeaNote(startTick = 0, lengthTick = 480, pitch = 60, lyric = "konnichiwa"))
                            )
                        )
                    )
                )
            )
        )
        val lyricMeta = byteArrayOf(0xFF.toByte(), 0x05, 0x0F) +
            "こんにちわ".toByteArray(Charsets.UTF_8)
        assertTrue(indexOf(bytes, lyricMeta) >= 0)
    }

    @Test
    fun composesKoreanCompatibilityJamoForMIDIExport() {
        val bytes = StandardMidiExporter.toByteArray(
            VeaProject(
                tracks = listOf(
                    VeaTrack(
                        name = "Korean Preview",
                        language = "kor",
                        clips = listOf(
                            VeaClip(
                                name = "Main",
                                startBar = 1,
                                lengthBars = 1,
                                notes = listOf(VeaNote(startTick = 0, lengthTick = 480, pitch = 60, lyric = "ㅇㅡㅁ"))
                            )
                        )
                    )
                )
            )
        )
        val lyricBytes = "음".toByteArray(Charsets.UTF_8)
        val lyricMeta = byteArrayOf(0xFF.toByte(), 0x05, lyricBytes.size.toByte()) + lyricBytes
        assertTrue(indexOf(bytes, lyricMeta) >= 0)
    }

    @Test
    fun appliesClipBarOffsetAndTimeSignature() {
        val bytes = StandardMidiExporter.toByteArray(
            VeaProject(
                numerator = 3,
                denominator = 4,
                tracks = listOf(
                    VeaTrack(
                        name = "Vocal",
                        clips = listOf(
                            VeaClip(
                                name = "Late",
                                startBar = 2,
                                lengthBars = 1,
                                notes = listOf(VeaNote(startTick = 0, lengthTick = 120, pitch = 67, lyric = "ra"))
                            )
                        )
                    )
                )
            )
        )
        // 3/4: bar 2 starts at 1,440 ticks; VLQ 0x8B 0x20.
        // Japanese romaji is normalized to hiragana before the V7 hand-off.
        val lyric = "ら".toByteArray(Charsets.UTF_8)
        val expected = byteArrayOf(0x8B.toByte(), 0x20, 0xFF.toByte(), 0x05, lyric.size.toByte()) + lyric
        assertTrue(indexOf(bytes, expected) >= 0)
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
        if (needle.isEmpty() || needle.size > haystack.size) return -1
        for (start in 0..(haystack.size - needle.size)) {
            var match = true
            for (i in needle.indices) {
                if (haystack[start + i] != needle[i]) {
                    match = false
                    break
                }
            }
            if (match) return start
        }
        return -1
    }
}
