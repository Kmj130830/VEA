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
 * Integration boundary for native, on-device VOCALOID AI rendering.
 *
 * The supplied Windows PE DLLs are not Android-loadable libraries. The current
 * path is authorized static analysis followed by an Android ARM64 native port.
 * G2P is being analyzed first; no native G2P or singing renderer is integrated yet.
 */
class VocaloidAiEngineAdapter : SynthesisEngine {
    override fun status(): EngineStatus = EngineStatus(
        engineName = "VOCALOID AI (V6 / V7 target)",
        isAvailable = false,
        message = "Native Android backend is not integrated yet. G2P DLL analysis is in progress; VOCALOID singing-render entry point and ARM64 implementation remain unverified."
    )

    override suspend fun render(
        project: VeaProject,
        voicebank: VoicebankDescriptor,
        output: OutputStream
    ) {
        throw UnsupportedOperationException(
            "On-device VOCALOID AI synthesis is unavailable until a native backend produces a verified render."
        )
    }
}
