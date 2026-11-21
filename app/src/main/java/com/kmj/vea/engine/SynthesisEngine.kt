package com.kmj.vea.engine

import com.kmj.vea.data.VeaProject
import com.kmj.vea.data.VeaTrack
import java.io.File
import java.io.OutputStream
import java.net.URI

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
    private companion object {
        // FUN_18004e1e0 checks presence of this hashed model-object key.
        const val RNN_FEATURE_FLAG_KEY = "9f174dc2b1ab89f96f4577e17d5e568a"
        // FUN_18004e3b0 checks presence of the paired optional-object key.
        const val PAIRED_OBJECT_FEATURE_FLAG_KEY = "69d61aca6f87b9618d4c65b9560cb5be"
    }

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

    /**
     * Converts the selected track's editable notes into the stable acoustic
     * renderer request format. This is also the entry point used by future
     * voicebank adapters; it does not synthesize audio by itself.
     */
    fun prepareRenderPlans(
        project: VeaProject,
        voicebank: VoicebankDescriptor,
        sampleRate: Int = VocalRenderPlanBuilder.DEFAULT_SAMPLE_RATE
    ): List<VocalRenderPlan> {
        val tracks = project.tracks.filter { track ->
            track.kind != "AUDIO" &&
                track.singerId == voicebank.id &&
                track.singerSourceUri == voicebank.sourceUri
        }
        require(tracks.isNotEmpty()) {
            "The project has no vocal track bound to ${voicebank.displayName}."
        }
        return tracks.map { track ->
            VocalRenderPlanBuilder.build(project, track, sampleRate)
        }
    }

    /**
     * Scans the installed Megpoid VTB2 model without materializing a giant
     * JSONObject tree or decoding unrelated tensors. The scanner reports the
     * opaque JSON key namespace and typed tensor-string count; parameter-to-
     * object mapping remains an explicit unresolved stage.
     */
    fun scanModelResources(
        voicebank: VoicebankDescriptor,
        targetTensorKeys: Set<String> = emptySet()
    ): List<S5ModelResourceScan> {
        require(voicebank.id == "ai_megpoid_v7" && voicebank.engineFamily == "VOCALOID7") {
            "This model scanner currently targets the bundled AI Megpoid V7 voicebank."
        }
        val directory = File(URI(voicebank.sourceUri))
        return S5ModelContainerScanner.scanMegpoidPackage(directory, targetTensorKeys)
    }

    override suspend fun render(
        project: VeaProject,
        voicebank: VoicebankDescriptor,
        output: OutputStream
    ) {
        val plans = prepareRenderPlans(project, voicebank)
        require(plans.any { !it.isEmpty }) { "There are no notes to synthesize." }

        require(voicebank.id == "ai_megpoid_v7" && voicebank.engineFamily == "VOCALOID7") {
            "This acoustic preflight currently targets the built-in AI Megpoid V7 voicebank."
        }
        val modelDirectory = File(URI(voicebank.sourceUri))
        val vtb2File = File(modelDirectory, "AI_Megpoid.vtb2")
        val featureScan = S5ModelContainerScanner.scanVtb2ForObjectKeys(
            vtb2File,
            setOf(RNN_FEATURE_FLAG_KEY, PAIRED_OBJECT_FEATURE_FLAG_KEY)
        )
        val rnnEnabled = RNN_FEATURE_FLAG_KEY in featureScan.matchedObjectKeys
        val pairedObjectEnabled =
            PAIRED_OBJECT_FEATURE_FLAG_KEY in featureScan.matchedObjectKeys

        if (!rnnEnabled) {
            throw UnsupportedOperationException(
                "AI Megpoid V7's VTB2 was decoded and checked. S5API's optional RNN branch is not enabled " +
                    "in this model object (the feature key is absent); paired optional model objects are " +
                    (if (pairedObjectEnabled) "enabled" else "also absent") +
                    ". VEA must now port the active pre/conv/cond/linear graph and its acoustic decoder; " +
                    "no WAV was generated."
            )
        }

        // The static callsite establishes one bidirectional layer and
        // bias lookups. Feature dimensions are intentionally not guessed
        // here because they do not participate in S5 name hashing.
        val recurrentCandidateKeys = S5RecurrentParameterLoader.lookupKeys(
            layerCount = 1,
            bidirectional = true,
            includeBias = true
        )
        val resourceScans = scanModelResources(voicebank, recurrentCandidateKeys)
        val typedTensorCount = resourceScans.sumOf { it.result.typedTensorStringCount }
        require(typedTensorCount > 0L) {
            "The bundled Megpoid package contains no typed tensor strings."
        }
        val matchedTensorStrings = resourceScans.flatMap { scan ->
            scan.result.matchedTensorStrings.values.flatten()
        }
        val decodedCandidateTensors = if (matchedTensorStrings.isEmpty()) {
            emptyList()
        } else {
            S5ModelTensorDecoder.decodeMatches(
                matches = matchedTensorStrings,
                decodeFloatArray = NativeS5TensorBridge::decodeTypedFloatArray,
                decodeInt32Array = NativeS5TensorBridge::decodeTypedInt32Array
            )
        }
        val recurrentMatches = matchedTensorStrings.size
        val resourceSummary = resourceScans.joinToString { scan ->
            "${scan.fileName}: ${scan.result.objectCount} objects / " +
                "${scan.result.typedTensorStringCount} typed tensors"
        }
        throw UnsupportedOperationException(
            "The five bundled Megpoid/shared model containers were decoded and streamed ($resourceSummary; " +
                "$recurrentMatches candidate recurrent objects matched, " +
                "${decodedCandidateTensors.size} selected tensor fields decoded). The neural graph's exact parameter " +
                "mapping and acoustic execution are not complete; no WAV was generated."
        )
    }
}
