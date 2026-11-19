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
 * Integration boundary for local, on-device VOCALOID AI rendering.
 *
 * The supplied Windows PE DLLs are not loaded by Android. A Wine/Box64
 * compatibility backend is under investigation; no renderer is advertised
 * as available until an actual on-device V7 render is verified.
 */
class VocaloidAiEngineAdapter : SynthesisEngine {
    override fun status(): EngineStatus = EngineStatus(
        engineName = "VOCALOID AI (V6 / V7 target)",
        isAvailable = false,
        message = "On-device VOCALOID rendering is not integrated yet. Local Windows-runtime compatibility and the V7 render entry point are under investigation."
    )

    override suspend fun render(
        project: VeaProject,
        voicebank: VoicebankDescriptor,
        output: OutputStream
    ) {
        throw UnsupportedOperationException(
            "On-device VOCALOID AI synthesis is not available until a local backend is verified."
        )
    }
}
