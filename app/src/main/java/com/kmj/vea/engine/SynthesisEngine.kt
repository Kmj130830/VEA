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

    /**
     * Loads and validates the shared nine-layer neural base graph from
     * VOCALOID7.vtmg. This is model-weight preparation only; the actual
     * forward operators and acoustic decoder still need execution ports.
     */
    fun loadSharedVtmgBaseGraph(voicebank: VoicebankDescriptor): S5VtmgBaseGraphWeights {
        require(voicebank.id == "ai_megpoid_v7" && voicebank.engineFamily == "VOCALOID7") {
            "The shared VTMG graph loader currently targets AI Megpoid V7."
        }
        val modelDirectory = File(URI(voicebank.sourceUri))
        return S5VtmgBaseGraphLoader.load(File(modelDirectory, "VOCALOID7.vtmg"))
    }

    /**
     * Loads the shared VTMG tensors and converts every layer to the padded
     * matrix-bank descriptors used by S5API FUN_180064670.
     */
    fun prepareSharedVtmgBaseGraph(
        voicebank: VoicebankDescriptor
    ): S5VtmgPackedBaseGraphWeights {
        val rawGraph = loadSharedVtmgBaseGraph(voicebank)
        return S5VtmgBaseGraphPacker.pack(rawGraph)
    }
    /**
     * Converts all nine shared VTMG layers to the matrix layout consumed by
     * S5API FUN_1800c6390. The returned map retains the source layer names;
     * renderer-stage assignment is intentionally left to the later binding
     * step after the render-state factory is fully recovered.
     */
    fun prepareSharedVtmgProjectionPlans(
        voicebank: VoicebankDescriptor
    ): Map<String, S5VtmgBankedProjectionPlan> =
        prepareSharedVtmgBaseGraph(voicebank).toBankedProjectionPlans()

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
            setOf(
                S5ModelFeatureFlagReader.RECURRENT_BRANCH_KEY,
                S5ModelFeatureFlagReader.PAIRED_OBJECT_KEY
            )
        )
        val modelFeatures = S5ModelFeatureFlagReader.decode(featureScan)

        // VTMG supplies the shared S5 base graph. It is independent of the
        // optional RNN feature branch stored in the bank's VTB2 object.
        val projectionPlans = prepareSharedVtmgProjectionPlans(voicebank)
        require(projectionPlans.keys == S5VtmgGraphTensorCatalog.layers.map { it.layerName }.toSet()) {
            "The shared VTMG projection-plan set is incomplete."
        }

        val recurrentStatus = if (modelFeatures.recurrentBranch) {
            "The optional VTB2 RNN branch is enabled but its model-object mapping and execution are not ported."
        } else {
            "The optional VTB2 RNN branch is disabled for this Megpoid model."
        }
        val pairedStatus = if (modelFeatures.pairedOptionalObjects) {
            "Paired optional VTB2 objects are enabled but not yet integrated."
        } else {
            "Paired optional VTB2 objects are disabled."
        }

        throw UnsupportedOperationException(
            "Loaded the shared VOCALOID7.vtmg base graph and converted its " +
                "${projectionPlans.size} named layers to c6390-compatible banked projection plans " +
                "(pre1/pre2, conv1/cond1, conv2/cond2, conv3/cond3, linear). " +
                "These plans are not yet assigned to renderer stages. " +
                "$recurrentStatus $pairedStatus The remaining stage binding, base-graph forward operators, " +
                "voicebank-specific VTB2 conditioning, Yamaha G2P, and acoustic waveform decoder are not " +
                "complete; no WAV was generated."
        )
    }
}
