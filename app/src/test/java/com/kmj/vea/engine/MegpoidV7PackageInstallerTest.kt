package com.kmj.vea.engine

import org.junit.Assert.assertThrows
import org.junit.Test

class MegpoidV7PackageInstallerTest {
    @Test
    fun acceptsMatchingVtbrHeaderAndPayloadLength() {
        val header = makeHeader("VTBR", payloadSize = 37L)
        validateV7ContainerHeader(header, 136L + 37L, "VTBR")
    }

    @Test
    fun rejectsWrongContainerKind() {
        val header = makeHeader("VPIT", payloadSize = 4L)
        assertThrows(IllegalArgumentException::class.java) {
            validateV7ContainerHeader(header, 140L, "VTBR")
        }
    }

    @Test
    fun rejectsPayloadLengthMismatch() {
        val header = makeHeader("VTMG", payloadSize = 9L)
        assertThrows(IllegalArgumentException::class.java) {
            validateV7ContainerHeader(header, 136L + 10L, "VTMG")
        }
    }

    @Test
    fun rejectsShortHeader() {
        assertThrows(IllegalArgumentException::class.java) {
            validateV7ContainerHeader(ByteArray(135), 135L, "VHNV")
        }
    }

    @Test
    fun treatsPayloadLengthAsUnsignedLittleEndian() {
        val payloadSize = 0x8000_0001L
        val header = makeHeader("VPIT", payloadSize)
        validateV7ContainerHeader(header, 136L + payloadSize, "VPIT")
    }

    private fun makeHeader(magic: String, payloadSize: Long): ByteArray {
        val header = ByteArray(136)
        val magicBytes = magic.toByteArray(Charsets.US_ASCII)
        magicBytes.copyInto(header, destinationOffset = 0)
        for (index in 0 until 4) {
            header[4 + index] = ((payloadSize ushr (index * 8)) and 0xffL).toByte()
        }
        return header
    }
}
