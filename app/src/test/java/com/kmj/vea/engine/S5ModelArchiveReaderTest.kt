package com.kmj.vea.engine

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class S5ModelArchiveReaderTest {
    private val json = """{"model":{"enabled":true,"count":3}}"""

    @Test
    fun readsMegpoidModelMemberAndBuildsJsonTree() {
        var decoderCalls = 0
        val reader = S5ModelArchiveReader(
            payloadDecoder = S5ModelPayloadDecoder { encoded ->
                decoderCalls++
                assertEquals("VTBR", String(encoded, 0, 4, StandardCharsets.US_ASCII))
                json.toByteArray(StandardCharsets.UTF_8)
            }
        )
        val archive = reader.read(
            ByteArrayInputStream(
                makeZip("AI_Megpoid.vtb2" to modelMember("VTBR"))
            )
        )

        assertEquals(1, decoderCalls)
        assertEquals(1, archive.members.size)
        assertEquals("AI_Megpoid.vtb2", archive.singerModel.entryName)
        assertEquals("VTBR", archive.singerModel.magic)
        assertEquals(16L, archive.singerModel.formatWord)
        assertEquals(json.toByteArray(StandardCharsets.UTF_8).size,
            archive.singerModel.decodedJsonSizeBytes)

        val model = archive.singerModel.tree.findByLookupKey("model")!!.asObject()
        assertTrue((model.fields.getValue("enabled") as S5ModelValue.BooleanValue).value)
        assertEquals(3.0, (model.fields.getValue("count") as S5ModelValue.NumberValue).value, 0.0)
    }

    @Test
    fun readsMultipleKnownMembersAndIgnoresArtworkAndManifest() {
        val reader = S5ModelArchiveReader(
            payloadDecoder = S5ModelPayloadDecoder { json.toByteArray(StandardCharsets.UTF_8) }
        )
        val archive = reader.read(
            ByteArrayInputStream(
                makeZip(
                    "AI_Megpoid.vtb2" to modelMember("VTBR"),
                    "AI_Megpoid.vpit" to modelMember("VPIT"),
                    "VOCALOID7.vhnv" to modelMember("VHNV"),
                    "artwork/icon.png" to byteArrayOf(1, 2, 3),
                    "BEAF64G3DYXLRECA.vmanifest" to byteArrayOf(4, 5)
                )
            )
        )

        assertEquals(setOf("AI_Megpoid.vtb2", "AI_Megpoid.vpit", "VOCALOID7.vhnv"),
            archive.members.keys)
        assertEquals("VPIT", archive.members.getValue("AI_Megpoid.vpit").magic)
        assertEquals("VHNV", archive.members.getValue("VOCALOID7.vhnv").magic)
    }

    @Test
    fun rejectsMissingCoreModel() {
        val reader = S5ModelArchiveReader(
            payloadDecoder = S5ModelPayloadDecoder { json.toByteArray(StandardCharsets.UTF_8) }
        )
        assertThrows(IllegalArgumentException::class.java) {
            reader.read(ByteArrayInputStream(makeZip("COMMON7.vpit" to modelMember("VPIT"))))
        }
    }

    @Test
    fun rejectsUnexpectedMagicAndInconsistentLength() {
        val reader = S5ModelArchiveReader(
            payloadDecoder = S5ModelPayloadDecoder { json.toByteArray(StandardCharsets.UTF_8) }
        )
        assertThrows(IllegalArgumentException::class.java) {
            reader.read(ByteArrayInputStream(makeZip("AI_Megpoid.vtb2" to modelMember("VPIT"))))
        }

        val badLength = modelMember("VTBR").also { it[4] = 0 }
        assertThrows(IllegalArgumentException::class.java) {
            reader.read(ByteArrayInputStream(makeZip("AI_Megpoid.vtb2" to badLength)))
        }
    }

    @Test
    fun rejectsDuplicateRecognizedBasenames() {
        val reader = S5ModelArchiveReader(
            payloadDecoder = S5ModelPayloadDecoder { json.toByteArray(StandardCharsets.UTF_8) }
        )
        assertThrows(IllegalArgumentException::class.java) {
            reader.read(
                ByteArrayInputStream(
                    makeZip(
                        "first/AI_Megpoid.vtb2" to modelMember("VTBR"),
                        "second/AI_Megpoid.vtb2" to modelMember("VTBR")
                    )
                )
            )
        }
    }

    @Test
    fun enforcesEntryAndTotalInflatedByteLimits() {
        val reader = S5ModelArchiveReader(
            payloadDecoder = S5ModelPayloadDecoder { json.toByteArray(StandardCharsets.UTF_8) },
            limits = S5ModelArchiveReader.Limits(
                maxEntries = 4,
                maxEntryBytes = 64,
                maxTotalUncompressedBytes = 128
            )
        )
        assertThrows(IllegalArgumentException::class.java) {
            reader.read(ByteArrayInputStream(makeZip("AI_Megpoid.vtb2" to modelMember("VTBR"))))
        }
    }

    private fun modelMember(magic: String): ByteArray {
        val raw = ByteArray(136 + 24)
        val buffer = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(magic.toByteArray(StandardCharsets.US_ASCII))
        buffer.putInt(24)
        buffer.putInt(16)
        buffer.putInt(0x12345678)
        return raw
    }

    private fun makeZip(vararg entries: Pair<String, ByteArray>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }
}
