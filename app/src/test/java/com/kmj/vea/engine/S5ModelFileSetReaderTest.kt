package com.kmj.vea.engine

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class S5ModelFileSetReaderTest {
    @Test
    fun readsAnInstalledModelFileWithoutCreatingAnIntermediateZip() {
        val directory = createTempDir(prefix = "vea-s5-model-files-")
        try {
            val modelFile = File(directory, "AI_Megpoid.vtb2")
            modelFile.writeBytes(containerEnvelope("VTBR"))

            val reader = S5ModelArchiveReader(
                payloadDecoder = S5ModelPayloadDecoder {
                    """{"model":{"ok":true}}""".toByteArray(Charsets.UTF_8)
                }
            )
            val archive = reader.readFiles(mapOf(modelFile.name to modelFile))

            assertEquals(setOf("AI_Megpoid.vtb2"), archive.members.keys)
            assertEquals("VTBR", archive.singerModel.magic)
            assertTrue(archive.singerModel.tree.findByLookupKey("model") is S5ModelValue.ObjectValue)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun rejectsUnknownModelFileNames() {
        val directory = createTempDir(prefix = "vea-s5-model-files-")
        try {
            val unknown = File(directory, "unknown.bin")
            unknown.writeBytes(ByteArray(136))
            val reader = S5ModelArchiveReader(
                payloadDecoder = S5ModelPayloadDecoder { "{}".toByteArray(Charsets.UTF_8) }
            )

            org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
                reader.readFiles(mapOf(unknown.name to unknown), requireSingerModelMember = false)
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun containerEnvelope(magic: String): ByteArray =
        ByteArray(136).apply {
            magic.toByteArray(Charsets.US_ASCII).copyInto(this, destinationOffset = 0)
            // Declared payload span is fileSize - 136, which is zero here.
            this[8] = 16
        }
}
