package com.kmj.vea.engine

import com.kmj.vea.data.VeaProject
import java.io.OutputStream

data class VoicebankDescriptor(
    val id: String,
    val displayName: String,
    val engineFamily: String,
    val sourceUri: String,
    val languages: List<String> = emptyList()
)

data class EngineStatus(
    val engineName: String,
    val isAvailable: Boolean,
    val message: String
)

interface SynthesisEngine {
    fun status(): EngineStatus

    suspend fun render(
        project: VeaProject,
        voicebank: VoicebankDescriptor,
        output: OutputStream
    )
}

/**
 * Integration boundary for a future, legally authorized VOCALOID AI backend.
 *
 * This class deliberately does not inspect proprietary voicebank internals,
 * invoke desktop binaries, bypass activation, or fabricate audio output.
 */
class VocaloidAiEngineAdapter : SynthesisEngine {
    override fun status(): EngineStatus = EngineStatus(
        engineName = "VOCALOID AI (V6 / V7 target)",
        isAvailable = false,
        message = "No licensed Android synthesis runtime or SDK is connected."
    )

    override suspend fun render(
        project: VeaProject,
        voicebank: VoicebankDescriptor,
        output: OutputStream
    ) {
        throw UnsupportedOperationException(
            "VOCALOID AI synthesis is not available until an authorized Android backend is integrated."
        )
    }
}
