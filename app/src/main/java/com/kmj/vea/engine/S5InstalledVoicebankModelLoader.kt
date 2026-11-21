package com.kmj.vea.engine

import java.io.File
import java.net.URI

/**
 * Reads the five individually-installed AI Megpoid/shared S5 model members.
 * The app installer has already copied these into private storage; this class
 * independently rechecks their container envelopes and manifest SHA-256s
 * before exposing parsed model trees to the renderer.
 */
object S5InstalledVoicebankModelLoader {
    private val singerMemberNames = setOf(
        "AI_Megpoid.vtb2",
        "AI_Megpoid.vpit"
    )
    private val sharedMemberNames = setOf(
        "COMMON7.vpit",
        "VOCALOID7.vhnv",
        "VOCALOID7.vtmg"
    )

    fun loadMegpoid(directory: File): S5VoicebankModelPackage {
        require(directory.isDirectory) {
            "The installed AI Megpoid model directory does not exist."
        }

        val allNames = singerMemberNames + sharedMemberNames
        val missing = allNames.filterNot { File(directory, it).isFile }
        require(missing.isEmpty()) {
            "The AI Megpoid installation is missing model files: ${missing.sorted().joinToString()}."
        }

        val reader = S5ModelArchiveReader()
        val singerArchive = reader.readFiles(
            singerMemberNames.associateWith { File(directory, it) },
            requireSingerModelMember = true
        )
        val sharedArchive = reader.readFiles(
            sharedMemberNames.associateWith { File(directory, it) },
            requireSingerModelMember = false
        )
        return S5VoicebankModelPackageAssembler().assemble(singerArchive, sharedArchive)
    }
}

/** First registered provider. Later voicebanks can supply their own loaders. */
class S5MegpoidV7ModelPackageLoader : VoicebankModelPackageLoader {
    override fun supports(voicebank: VoicebankDescriptor): Boolean =
        voicebank.id == S5VoicebankModelPackageAssembler.AI_MEGPOID_V7_ID &&
            voicebank.engineFamily == "VOCALOID7" &&
            voicebank.sourceUri.isNotBlank()

    override fun load(voicebank: VoicebankDescriptor): VoicebankModelPackage {
        require(supports(voicebank)) { "The selected voicebank is not AI Megpoid V7." }
        return S5InstalledVoicebankModelLoader.loadMegpoid(File(URI(voicebank.sourceUri)))
    }
}
