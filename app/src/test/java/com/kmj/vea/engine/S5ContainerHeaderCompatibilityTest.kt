package com.kmj.vea.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class S5ContainerHeaderCompatibilityTest {
    @Test
    fun retainsTheOriginalSixteenBytePrefixContract() {
        val prefix = ByteArray(16)
        "VTBR".toByteArray(Charsets.US_ASCII).copyInto(prefix)
        writeUInt32Le(prefix, 4, 164L) // 300-byte file: declared span is file size - 136
        writeUInt32Le(prefix, 8, 16L)
        writeUInt32Le(prefix, 12, 0x12345678L)

        val parsed = NativeS5TensorBridge.parseContainerHeader(prefix, 300L)

        requireNotNull(parsed)
        assertEquals("VTBR", parsed.kind)
        assertEquals(16L, parsed.formatWord)
        assertEquals(156L, parsed.payloadSize)
        assertEquals(156L, parsed.decodedPayloadSize)
    }

    @Test
    fun rejectsUnknownMagicWithoutLoadingTheNativeLibrary() {
        val prefix = ByteArray(16)
        "NOPE".toByteArray(Charsets.US_ASCII).copyInto(prefix)

        assertNull(NativeS5TensorBridge.parseContainerHeader(prefix, 300L))
    }

    private fun writeUInt32Le(bytes: ByteArray, offset: Int, value: Long) {
        for (index in 0 until 4) {
            bytes[offset + index] = ((value ushr (index * 8)) and 0xffL).toByte()
        }
    }
}
