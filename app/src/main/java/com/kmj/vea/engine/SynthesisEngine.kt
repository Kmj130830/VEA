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
 * The ARM64 JNI bridge is now built into the app. It currently implements
 * only a recovered Korean-input canonicalization primitive; no Yamaha G2P
 * phoneme engine or VOCALOID singing renderer is available yet.
 */
class VocaloidAiEngineAdapter : SynthesisEngine {
    override fun status(): EngineStatus {
        val bridgeMessage = if (NativeG2pBridge.isLoaded) {
            "Android ARM64 JNI bridge loaded (ABI 0x%08X). Korean input canonicalization is available, " +
                "but Yamaha G2PA phoneme conversion and the VOCALOID7 singing renderer are not ported yet."
        } else {
            "Android ARM64 JNI bridge is not loaded. The G2P phoneme engine and VOCALOID7 singing renderer " +
                "are not ported yet."
        }

        val message = if (NativeG2pBridge.isLoaded) {
            bridgeMessage.format(NativeG2pBridge.abiVersion)
        } else {
            bridgeMessage
        }

        return EngineStatus(
            engineName = "VOCALOID AI (V6 / V7 target)",
            isAvailable = false,
            message = message
        )
    }

    override suspend fun render(
        project: VeaProject,
        voicebank: VoicebankDescriptor,
        output: OutputStream
    ) {
        throw UnsupportedOperationException(
            "On-device VOCALOID AI synthesis is unavailable until the native G2P and singing renderer are implemented and verified."
        )
    }
}
