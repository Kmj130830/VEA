package com.kmj.vea.engine

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.zip.ZipInputStream

data class S5ModelArchiveMember(
    val entryName: String,
    val magic: String,
    val formatWord: Long,
    val encodedSizeBytes: Int,
    val decodedJsonSizeBytes: Int,
    val tree: S5ModelObjectTree
)

data class S5ModelArchive(
    val members: Map<String, S5ModelArchiveMember>
) {
    val singerModel: S5ModelArchiveMember
        get() = members[AI_MEGPOID_MODEL]
            ?: throw IllegalStateException("AI Megpoid model member is missing.")

    companion object {
        const val AI_MEGPOID_MODEL = "AI_Megpoid.vtb2"
    }
}

/**
 * Reads supported AI Megpoid V7 model members from a ZIP archive.
 *
 * The input stream is consumed and closed. Unknown ZIP members are skipped,
 * but still counted against decompression limits. Only known model files are
 * retained in memory and decoded into JSON object trees.
 */
class S5ModelArchiveReader(
    private val payloadDecoder: S5ModelPayloadDecoder = NativeS5ModelPayloadDecoder,
    private val treeLimits: S5ModelObjectTree.Limits = S5ModelObjectTree.Limits(),
    private val limits: Limits = Limits()
) {
    data class Limits(
        val maxEntries: Int = 128,
        val maxEntryBytes: Long = 128L * 1024L * 1024L,
        val maxTotalUncompressedBytes: Long = 256L * 1024L * 1024L
    ) {
        init {
            require(maxEntries > 0)
            require(maxEntryBytes > 0L)
            require(maxTotalUncompressedBytes >= maxEntryBytes)
        }
    }

    private data class Header(
        val magic: String,
        val formatWord: Long
    )

    fun read(input: InputStream): S5ModelArchive {
        val loaded = LinkedHashMap<String, S5ModelArchiveMember>()
        var entryCount = 0
        var totalUncompressedBytes = 0L

        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entryCount++
                require(entryCount <= limits.maxEntries) {
                    "Model archive exceeds the \${limits.maxEntries}-entry limit."
                }

                val basename = entry.name.replace('\\', '/').substringAfterLast('/')
                val expectedMagic = EXPECTED_MEMBERS[basename]
                if (entry.isDirectory) {
                    zip.closeEntry()
                    continue
                }

                if (expectedMagic != null) {
                    require(!loaded.containsKey(basename)) {
                        "Model archive contains duplicate member '$basename'."
                    }
                }

                if (entry.size > limits.maxEntryBytes) {
                    throw IllegalArgumentException("ZIP member '\${entry.name}' exceeds the per-entry size limit.")
                }

                val captured = if (expectedMagic != null) ByteArrayOutputStream() else null
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var entryBytes = 0L

                while (true) {
                    val count = zip.read(buffer)
                    if (count < 0) break
                    if (count == 0) continue

                    entryBytes += count.toLong()
                    totalUncompressedBytes += count.toLong()
                    require(entryBytes <= limits.maxEntryBytes) {
                        "ZIP member '\${entry.name}' exceeds the per-entry size limit."
                    }
                    require(totalUncompressedBytes <= limits.maxTotalUncompressedBytes) {
                        "Model archive exceeds the total uncompressed-size limit."
                    }
                    captured?.write(buffer, 0, count)
                }
                zip.closeEntry()

                if (expectedMagic != null) {
                    val encoded = captured!!.toByteArray()
                    val header = parseHeader(basename, expectedMagic, encoded)
                    val decoded = payloadDecoder.decode(encoded)
                    val tree = S5ModelObjectTree.parseUtf8(decoded, treeLimits)
                    loaded[basename] = S5ModelArchiveMember(
                        entryName = entry.name,
                        magic = header.magic,
                        formatWord = header.formatWord,
                        encodedSizeBytes = encoded.size,
                        decodedJsonSizeBytes = decoded.size,
                        tree = tree
                    )
                }
            }
        }

        require(loaded.containsKey(S5ModelArchive.AI_MEGPOID_MODEL)) {
            "Archive does not contain \${S5ModelArchive.AI_MEGPOID_MODEL}."
        }
        return S5ModelArchive(loaded.toMap())
    }

    private fun parseHeader(
        memberName: String,
        expectedMagic: String,
        bytes: ByteArray
    ): Header {
        require(bytes.size >= HEADER_SIZE) {
            "Model member '$memberName' is shorter than the $HEADER_SIZE-byte header."
        }

        val magic = String(bytes, 0, 4, StandardCharsets.US_ASCII)
        require(magic == expectedMagic) {
            "Model member '$memberName' has unexpected magic '$magic' (expected '$expectedMagic')."
        }

        val declaredPayloadSize = readUInt32Le(bytes, 4)
        val actualPayloadSize = bytes.size.toLong() - HEADER_SIZE.toLong()
        require(declaredPayloadSize == actualPayloadSize) {
            "Model member '$memberName' has an inconsistent payload length."
        }

        return Header(
            magic = magic,
            formatWord = readUInt32Le(bytes, 8)
        )
    }

    private fun readUInt32Le(bytes: ByteArray, offset: Int): Long =
        (bytes[offset].toLong() and 0xffL) or
            ((bytes[offset + 1].toLong() and 0xffL) shl 8) or
            ((bytes[offset + 2].toLong() and 0xffL) shl 16) or
            ((bytes[offset + 3].toLong() and 0xffL) shl 24)

    private companion object {
        const val HEADER_SIZE = 136
        val EXPECTED_MEMBERS = mapOf(
            "AI_Megpoid.vtb2" to "VTBR",
            "AI_Megpoid.vpit" to "VPIT",
            "COMMON7.vpit" to "VPIT",
            "VOCALOID7.vhnv" to "VHNV",
            "VOCALOID7.vtmg" to "VTMG"
        )
    }
}
