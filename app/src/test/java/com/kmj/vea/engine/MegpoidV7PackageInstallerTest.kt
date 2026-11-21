package com.kmj.vea.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import com.kmj.vea.data.VeaTrack

class MegpoidV7PackageInstallerTest {
    @Test
    fun acceptsMatchingVtbrPrefixAndPayloadLength() {
        val prefix = makePrefix("VTBR", payloadSize = 37L)
        validateV7ContainerPrefix(prefix, 136L + 37L, "VTBR")
    }

    @Test
    fun rejectsWrongContainerKind() {
        val prefix = makePrefix("VPIT", payloadSize = 4L)
        assertThrows(IllegalArgumentException::class.java) {
            validateV7ContainerPrefix(prefix, 140L, "VTBR")
        }
    }

    @Test
    fun rejectsPayloadLengthMismatch() {
        val prefix = makePrefix("VTMG", payloadSize = 9L)
        assertThrows(IllegalArgumentException::class.java) {
            validateV7ContainerPrefix(prefix, 136L + 10L, "VTMG")
        }
    }

    @Test
    fun rejectsShortPrefix() {
        assertThrows(IllegalArgumentException::class.java) {
            validateV7ContainerPrefix(ByteArray(15), 136L, "VHNV")
        }
    }

    @Test
    fun treatsPayloadLengthAsUnsignedLittleEndian() {
        val payloadSize = 0x8000_0001L
        val prefix = makePrefix("VPIT", payloadSize)
        validateV7ContainerPrefix(prefix, 136L + payloadSize, "VPIT")
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

    private fun makePrefix(magic: String, payloadSize: Long): ByteArray {
        val prefix = ByteArray(16)
        val magicBytes = magic.toByteArray(Charsets.US_ASCII)
        magicBytes.copyInto(prefix, destinationOffset = 0)
        for (index in 0 until 4) {
            prefix[4 + index] = ((payloadSize ushr (index * 8)) and 0xffL).toByte()
        }
        return prefix
    }
}
