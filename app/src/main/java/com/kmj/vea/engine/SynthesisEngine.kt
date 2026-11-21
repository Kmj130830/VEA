package com.kmj.vea.engine

import com.kmj.vea.data.VeaProject
import com.kmj.vea.data.VeaTrack
import java.io.OutputStream

data class VoicebankDescriptor(
    val id: String,
    val displayName: String,
    val engineFamily: String,
    val sourceUri: String,
    val languages: List<String> = emptyList()
)

/**
 * Reconstructs the persisted voicebank binding for a track.
 *
 * The returned descriptor only identifies the installed package. Renderer
 * availability must still be checked by the selected SynthesisEngine.
 */
fun VeaTrack.voicebankDescriptorOrNull(): VoicebankDescriptor? {
    if (singerId.isBlank() || singerSourceUri.isBlank()) return null

    val languages = when (singerId) {
        "ai_megpoid_v7" -> listOf("Japanese", "English", "Chinese")
        else -> emptyList()
    }
    val engineFamily = when (singerId) {
        "ai_megpoid_v7" -> "VOCALOID7"
        else -> "unknown"
    }

    return VoicebankDescriptor(
        id = singerId,
        displayName = singerName,
        engineFamily = engineFamily,
        sourceUri = singerSourceUri,
        languages = languages
    )
}

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
        val vsmMessage = if (NativeVsmCallbackBridge.isLoaded) {
            "VSM callback bridge loaded (ABI 0x%08X, %d Hz transport). ".format(
                NativeVsmCallbackBridge.abiVersion,
                NativeVsmCallbackBridge.defaultSampleRate
            )
        } else {
            "VSM callback bridge is not loaded. "
        }

        val tensorMessage = if (NativeS5TensorBridge.isLoaded) {
            "S5 tensor layout bridge loaded (ABI 0x%08X). ".format(
                NativeS5TensorBridge.abiVersion
            )
        } else {
            "S5 tensor layout bridge is not loaded. "
        }

        val bridgeMessage = if (NativeG2pBridge.isLoaded) {
            "Android ARM64 JNI bridge loaded (ABI 0x%08X). Korean input canonicalization is available, " +
                "but Yamaha G2PA phoneme conversion and the VOCALOID7 singing renderer are not ported yet."
        } else {
            "Android ARM64 JNI bridge is not loaded. The G2P phoneme engine and VOCALOID7 singing renderer " +
                "are not ported yet."
        }

        val message = vsmMessage + tensorMessage + if (NativeG2pBridge.isLoaded) {
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
