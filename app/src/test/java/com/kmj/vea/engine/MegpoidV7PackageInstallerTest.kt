package com.kmj.vea.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import com.kmj.vea.data.VeaTrack

class MegpoidV7PackageInstallerTest {
    @Test
    fun acceptsMatchingSixteenBytePrefixAndOpaqueTrailerEnvelope() {
        val prefix = makePrefix("VTBR", declaredSpan = 37L + 8L)
        validateV7ContainerPrefix(prefix, totalFileSize = 144L + 37L, "VTBR")
    }

    @Test
    fun rejectsWrongContainerKind() {
        val prefix = makePrefix("VPIT", declaredSpan = 4L + 8L)
        assertThrows(IllegalArgumentException::class.java) {
            validateV7ContainerPrefix(prefix, 144L + 4L, "VTBR")
        }
    }

    @Test
    fun rejectsDeclaredSpanMismatch() {
        val prefix = makePrefix("VTMG", declaredSpan = 9L + 8L)
        assertThrows(IllegalArgumentException::class.java) {
            validateV7ContainerPrefix(prefix, 144L + 10L, "VTMG")
        }
    }

    @Test
    fun rejectsShortPrefix() {
        assertThrows(IllegalArgumentException::class.java) {
            validateV7ContainerPrefix(ByteArray(15), 159L, "VHNV")
        }
    }

    @Test
    fun treatsDeclaredSpanAsUnsignedLittleEndian() {
        val declaredSpan = 0x8000_0001L
        val prefix = makePrefix("VPIT", declaredSpan)
        validateV7ContainerPrefix(prefix, 136L + declaredSpan, "VPIT")
    }

    @Test
    fun restoresVoicebankDescriptorFromSavedTrackBinding() {
        val track = VeaTrack(
            name = "Vocal 1",
            singerId = "ai_megpoid_v7",
            singerName = "AI Megpoid",
            singerStyleId = "181b8942-ba8d-41a2-bbbb-4a30bd64e470",
            singerSourceUri = "file:///data/user/0/com.kmj.vea/files/voicebanks/ai_megpoid_v7/"
        )

        val descriptor = requireNotNull(track.voicebankDescriptorOrNull())
        assertEquals("ai_megpoid_v7", descriptor.id)
        assertEquals("AI Megpoid", descriptor.displayName)
        assertEquals("VOCALOID7", descriptor.engineFamily)
        assertEquals(track.singerSourceUri, descriptor.sourceUri)
        assertEquals(listOf("Japanese", "English", "Chinese"), descriptor.languages)
    }

    @Test
    fun doesNotCreateDescriptorWithoutInstalledModelPath() {
        val track = VeaTrack(
            name = "Vocal 1",
            singerId = "ai_megpoid_v7",
            singerName = "AI Megpoid"
        )
        assertNull(track.voicebankDescriptorOrNull())
    }

    private fun makePrefix(magic: String, declaredSpan: Long): ByteArray {
        val prefix = ByteArray(16)
        magic.toByteArray(Charsets.US_ASCII).copyInto(prefix, destinationOffset = 0)
        for (index in 0 until 4) {
            prefix[4 + index] = ((declaredSpan ushr (index * 8)) and 0xffL).toByte()
        }
        // The format word and transform seed are deliberately independent of
        // the envelope-size check; the payload decoder consumes the seed.
        prefix[8] = 16
        prefix[12] = 0x78
        prefix[13] = 0x56
        prefix[14] = 0x34
        prefix[15] = 0x12
        return prefix
    }
}
