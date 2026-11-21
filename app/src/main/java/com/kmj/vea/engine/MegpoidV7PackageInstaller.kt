package com.kmj.vea.engine

import android.content.Context
import android.net.Uri
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.LinkedHashMap
import java.util.UUID
import java.util.zip.ZipInputStream

private const val V7_CONTAINER_PREFIX_SIZE = 16
private const val V7_CONTAINER_CATALOG_OVERHEAD = 136
private const val MAX_V7_ARCHIVE_ENTRY_BYTES = 512L * 1024L * 1024L
private const val MAX_V7_ARCHIVE_EXPANDED_BYTES = 1024L * 1024L * 1024L

/**
 * Verifies the 16-byte S5API prefix used by the supplied VOCALOID7 model files.
 *
 * The vendor transform reads the seed at +0x0c and computes its body end as
 * declaredSpan(+0x04) + 8, clamped to the file size. For the five checked-in
 * Megpoid/shared model assets, declaredSpan == fileSize - 136: 16 prefix bytes
 * plus a 128-byte untouched trailer.
 */
internal fun validateV7ContainerPrefix(
    prefix: ByteArray,
    totalFileSize: Long,
    expectedMagic: String
) {
    require(prefix.size == V7_CONTAINER_PREFIX_SIZE) {
        "An S5API container prefix must be 16 bytes."
    }
    require(expectedMagic.length == 4) {
        "The expected container magic must contain four ASCII characters."
    }
    require(totalFileSize >= V7_CONTAINER_CATALOG_OVERHEAD.toLong()) {
        "The model container is shorter than the known prefix/trailer envelope."
    }

    val actualMagic = String(prefix, 0, 4, Charsets.US_ASCII)
    require(actualMagic == expectedMagic) {
        "Unexpected model container type: expected $expectedMagic, found $actualMagic."
    }

    val declaredSpan =
        (prefix[4].toLong() and 0xffL) or
            ((prefix[5].toLong() and 0xffL) shl 8) or
            ((prefix[6].toLong() and 0xffL) shl 16) or
            ((prefix[7].toLong() and 0xffL) shl 24)
    val expectedSpan = totalFileSize - V7_CONTAINER_CATALOG_OVERHEAD
    require(expectedSpan <= 0xffff_ffffL && declaredSpan == expectedSpan) {
        "The model container declared span does not match its known file envelope."
    }
}

/**
 * Imports the exact AI Megpoid V7 model set referenced by the checked-in catalog.
 *
 * Source archives are read through Android's content resolver. Only five required
 * model files are written; unrelated archive members are streamed and discarded.
 * Every imported file must match its recorded SHA-256 and S5API container prefix.
 *
 * This installs and validates model resources. It does not decode proprietary
 * model payloads, create an inference graph, or make the VOCALOID renderer available.
 */
object MegpoidV7PackageInstaller {
    private const val BANK_ID = "ai_megpoid_v7"
    private const val COMPONENT_ID = "BEAF64G3DYXLRECA"
    private const val DISPLAY_NAME = "AI Megpoid"
    private const val ENGINE_FAMILY = "VOCALOID7"

    private data class ModelSpec(
        val fileName: String,
        val sha256: String,
        val magic: String
    )

    private val bankArchiveSpecs = listOf(
        ModelSpec(
            "AI_Megpoid.vtb2",
            "f09cad94a1c56c3ea5e96e77ae13887edbcb9cd52f0cd4167731f8d539c42f6a",
            "VTBR"
        ),
        ModelSpec(
            "AI_Megpoid.vpit",
            "40affbf4f76331655866821c2f6b589cc02cb0b0964c21bf48c92f283c0e846f",
            "VPIT"
        )
    )

    private val sharedModelArchiveSpecs = listOf(
        ModelSpec(
            "COMMON7.vpit",
            "2611bf3c7079ef97851f4103f7d88b9d900a1cbf7e4cffe61ae8d904c2273751",
            "VPIT"
        ),
        ModelSpec(
            "VOCALOID7.vhnv",
            "d8de8bfbf6ef4c9829431e09a29e4b105524d22faaada9001552cd89cb61d570",
            "VHNV"
        ),
        ModelSpec(
            "VOCALOID7.vtmg",
            "3384cc85fa5ccea1c3203b4ae7aee687a059ec67a7d3a9ebecb8c57170d2b36d",
            "VTMG"
        )
    )

    data class InstalledPackage internal constructor(
        val directory: File,
        val modelFiles: Map<String, File>,
        val componentId: String
    ) {
        fun toVoicebankDescriptor(): VoicebankDescriptor = VoicebankDescriptor(
            id = BANK_ID,
            displayName = DISPLAY_NAME,
            engineFamily = ENGINE_FAMILY,
            sourceUri = directory.toURI().toString(),
            languages = listOf("Japanese", "English", "Chinese")
        )
    }

    /**
     * Installs from the user's AI Megpoid bank ZIP and shared VOCALOID7 model ZIP.
     *
     * Call from an I/O dispatcher. Existing installation is replaced only after
     * both archives have passed all integrity and header checks.
     */
    @Throws(IOException::class, IllegalArgumentException::class)
    fun install(
        context: Context,
        megpoidBankArchive: Uri,
        sharedModelArchive: Uri
    ): InstalledPackage {
        val appContext = context.applicationContext
        val parent = File(appContext.filesDir, "voicebanks")
        if (!parent.exists() && !parent.mkdirs()) {
            throw IOException("Cannot create the private voicebank directory.")
        }

        val staging = File(parent, ".$BANK_ID.stage-${UUID.randomUUID()}")
        if (!staging.mkdir()) throw IOException("Cannot create a temporary voicebank directory.")

        try {
            extractVerifiedMembers(
                appContext,
                megpoidBankArchive,
                bankArchiveSpecs,
                staging
            )
            extractVerifiedMembers(
                appContext,
                sharedModelArchive,
                sharedModelArchiveSpecs,
                staging
            )

            val allSpecs = bankArchiveSpecs + sharedModelArchiveSpecs
            val active = File(parent, BANK_ID)
            replaceDirectoryAtomically(staging, active)
            return packageAt(active, allSpecs)
        } catch (error: Exception) {
            staging.deleteRecursively()
            throw error
        }
    }

    /**
     * Installs the five model resources bundled into the APK by the Gradle
     * prepareMegpoidModelAssets task. No archive picker or network is required.
     *
     * Call from an I/O dispatcher. The source assets are verified before the
     * active installation is replaced.
     */
    @Throws(IOException::class)
    fun installBundled(context: Context): InstalledPackage {
        val appContext = context.applicationContext
        val parent = File(appContext.filesDir, "voicebanks")
        if (!parent.exists() && !parent.mkdirs()) {
            throw IOException("Cannot create the private voicebank directory.")
        }

        val staging = File(parent, ".$BANK_ID.bundled-stage-${UUID.randomUUID()}")
        if (!staging.mkdir()) throw IOException("Cannot create a temporary voicebank directory.")

        try {
            val allSpecs = bankArchiveSpecs + sharedModelArchiveSpecs
            for (spec in allSpecs) {
                val assetPath = "megpoid-models/${spec.fileName}"
                val outputFile = File(staging, spec.fileName)
                appContext.assets.open(assetPath).use { input ->
                    FileOutputStream(outputFile, false).use { output ->
                        input.copyTo(output, 64 * 1024)
                        output.fd.sync()
                    }
                }
                verifyExistingFile(outputFile, spec)
            }

            val active = File(parent, BANK_ID)
            replaceDirectoryAtomically(staging, active)
            return packageAt(active, allSpecs)
        } catch (error: Exception) {
            staging.deleteRecursively()
            throw error
        }
    }

    /**
     * Returns the existing installation only when all five files still match
     * the catalog hashes and their container prefixes.
     */
    fun findInstalled(context: Context): InstalledPackage? {
        val directory = File(context.applicationContext.filesDir, "voicebanks/$BANK_ID")
        if (!directory.isDirectory) return null

        return try {
            packageAt(directory, bankArchiveSpecs + sharedModelArchiveSpecs)
        } catch (_: Exception) {
            null
        }
    }

    private fun packageAt(directory: File, specs: List<ModelSpec>): InstalledPackage {
        val files = LinkedHashMap<String, File>()
        for (spec in specs) {
            val file = File(directory, spec.fileName)
            verifyExistingFile(file, spec)
            files[spec.fileName] = file
        }
        return InstalledPackage(directory, files.toMap(), COMPONENT_ID)
    }

    private fun extractVerifiedMembers(
        context: Context,
        archiveUri: Uri,
        specs: List<ModelSpec>,
        destination: File
    ) {
        val input = context.contentResolver.openInputStream(archiveUri)
            ?: throw IOException("Cannot open the selected archive: $archiveUri")
        val expectedByName = specs.associateBy { it.fileName }
        val found = mutableSetOf<String>()
        val buffer = ByteArray(64 * 1024)
        var expandedArchiveBytes = 0L

        ZipInputStream(BufferedInputStream(input)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val normalizedName = entry.name.replace('\\', '/')
                val baseName = normalizedName.substringAfterLast('/')

                if (entry.isDirectory) {
                    zip.closeEntry()
                    continue
                }

                val spec = expectedByName[baseName]
                if (spec == null) {
                    var skippedBytes = 0L
                    while (true) {
                        val count = zip.read(buffer)
                        if (count < 0) break
                        skippedBytes += count
                        expandedArchiveBytes += count
                        enforceArchiveLimits(skippedBytes, expandedArchiveBytes)
                    }
                    zip.closeEntry()
                    continue
                }

                if (!found.add(baseName)) {
                    throw IOException("The archive contains more than one $baseName entry.")
                }

                val outputFile = File(destination, spec.fileName)
                val digest = MessageDigest.getInstance("SHA-256")
                val header = ByteArray(V7_CONTAINER_PREFIX_SIZE)
                var headerBytes = 0
                var fileBytes = 0L

                FileOutputStream(outputFile, false).use { output ->
                    while (true) {
                        val count = zip.read(buffer)
                        if (count < 0) break

                        fileBytes += count
                        expandedArchiveBytes += count
                        enforceArchiveLimits(fileBytes, expandedArchiveBytes)

                        if (headerBytes < header.size) {
                            val toCopy = minOf(count, header.size - headerBytes)
                            System.arraycopy(buffer, 0, header, headerBytes, toCopy)
                            headerBytes += toCopy
                        }

                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                    }
                    output.fd.sync()
                }
                zip.closeEntry()

                if (headerBytes != V7_CONTAINER_PREFIX_SIZE) {
                    throw IOException("$baseName is shorter than the 16-byte S5API prefix.")
                }
                try {
                    validateV7ContainerPrefix(header, fileBytes, spec.magic)
                } catch (error: IllegalArgumentException) {
                    throw IOException("Invalid $baseName container header.", error)
                }

                val actualHash = digest.digest().toHexString()
                if (!actualHash.equals(spec.sha256, ignoreCase = true)) {
                    throw IOException("SHA-256 mismatch for $baseName.")
                }
            }
        }

        val missing = specs.map { it.fileName }.filterNot { it in found }
        if (missing.isNotEmpty()) {
            throw IOException("The selected archive is missing: ${missing.joinToString()}.")
        }
    }

    private fun enforceArchiveLimits(entryBytes: Long, archiveBytes: Long) {
        if (entryBytes > MAX_V7_ARCHIVE_ENTRY_BYTES ||
            archiveBytes > MAX_V7_ARCHIVE_EXPANDED_BYTES
        ) {
            throw IOException("The archive exceeds the supported uncompressed size limit.")
        }
    }

    private fun verifyExistingFile(file: File, spec: ModelSpec) {
        if (!file.isFile || file.length() < V7_CONTAINER_PREFIX_SIZE) {
            throw IOException("Missing or truncated model file: ${spec.fileName}.")
        }

        val header = ByteArray(V7_CONTAINER_PREFIX_SIZE)
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).buffered().use { input ->
            var headerBytes = 0
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
                if (headerBytes < header.size) {
                    val toCopy = minOf(count, header.size - headerBytes)
                    System.arraycopy(buffer, 0, header, headerBytes, toCopy)
                    headerBytes += toCopy
                }
            }
        }

        validateV7ContainerPrefix(header, file.length(), spec.magic)
        if (!digest.digest().toHexString().equals(spec.sha256, ignoreCase = true)) {
            throw IOException("SHA-256 mismatch for installed ${spec.fileName}.")
        }
    }

    private fun replaceDirectoryAtomically(staging: File, active: File) {
        val backup = File(active.parentFile, ".$BANK_ID.backup")
        if (backup.exists() && !backup.deleteRecursively()) {
            throw IOException("Cannot remove the previous voicebank backup.")
        }

        val hadActive = active.exists()
        if (hadActive && !active.renameTo(backup)) {
            throw IOException("Cannot move the previous voicebank installation.")
        }

        if (!staging.renameTo(active)) {
            if (hadActive) backup.renameTo(active)
            throw IOException("Cannot activate the verified voicebank files.")
        }

        if (backup.exists()) backup.deleteRecursively()
    }

    private fun ByteArray.toHexString(): String = joinToString(separator = "") {
        (it.toInt() and 0xff).toString(16).padStart(2, '0')
    }
}
