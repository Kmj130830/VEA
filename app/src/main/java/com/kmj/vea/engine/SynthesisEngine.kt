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
class VocaloidAiEngineAdapter(
    private val packageRegistry: VoicebankModelPackageRegistry =
        VoicebankModelPackageRegistry.withMegpoidV7()
) : SynthesisEngine {
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
     * Loads the package through the registry. Additional voicebank families can
     * be added by registering another VoicebankModelPackageLoader; the S5
     * Megpoid implementation does not become a hard-coded editor-wide format.
     */
    fun loadVoicebankModelPackage(voicebank: VoicebankDescriptor): VoicebankModelPackage =
        packageRegistry.load(voicebank)

    /**
     * Prepares the real Megpoid model weights and the portable note event
     * schedule. This creates reusable inference state only; it does not claim
     * to produce acoustic frames or PCM audio.
     */
    fun prepareMegpoidRuntime(
        project: VeaProject,
        voicebank: VoicebankDescriptor
    ): PreparedMegpoidRuntime {
        val plans = prepareRenderPlans(project, voicebank)
        require(plans.any { !it.isEmpty }) { "There are no notes to synthesize." }

        val modelPackage = loadVoicebankModelPackage(voicebank) as? S5VoicebankModelPackage
            ?: throw IllegalStateException("The selected voicebank loader did not return an S5 package.")

        val tracks = project.tracks.filter { track ->
            track.kind != "AUDIO" &&
                track.singerId == voicebank.id &&
                track.singerSourceUri == voicebank.sourceUri
        }
        val schedules = tracks.map { track ->
            VsmMidiInputScheduler.schedule(project, track)
        }

        val namedLayers = modelPackage.prepareNamedLayerParameters()
        val residualBlocks = modelPackage.prepareRuntimeBlocks()
        require(namedLayers.size == S5MegpoidNamedLayer.entries.size) {
            "Megpoid shared neural base graph did not provide all nine named layers."
        }
        require(residualBlocks.size == S5MegpoidRuntimeBlockFactoryKeys.BLOCK_ROWS *
            S5MegpoidRuntimeBlockFactoryKeys.BLOCK_COLUMNS) {
            "Megpoid runtime did not provide all eight residual blocks."
        }

        return PreparedMegpoidRuntime(
            modelPackage = modelPackage,
            namedLayerParameters = namedLayers,
            residualBlocks = residualBlocks,
            runtimeOptions = modelPackage.runtimeOptionsDescriptor,
            inputSchedules = schedules
        )
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

    override suspend fun render(
        project: VeaProject,
        voicebank: VoicebankDescriptor,
        output: OutputStream
    ) {
        val prepared = prepareMegpoidRuntime(project, voicebank)
        val scheduledNotes = prepared.scheduledNoteCount
        val blocks = prepared.residualBlocks.size
        val layers = prepared.namedLayerParameters.size

        throw UnsupportedOperationException(
            "AI Megpoid V7 preparation succeeded: five verified model members, " +
                "$layers shared neural layers, $blocks compiled residual blocks, and " +
                "$scheduledNotes note events scheduled on the 44.1 kHz / 256-sample input grid. " +
                "The Android reference output stage now includes recovered descriptor geometry, " +
                "frame/output window construction, inverse real FFT, and stateful overlap-add. " +
                "It is not yet connected to a complete note/phoneme feature-conditioning path or " +
                "the full neural forward pass that produces model spectra. The native FFT plan " +
                "parity, sample-rate conversion, and verified PCM/WAV export boundary also remain " +
                "incomplete. No audio was written."
        )
    }
}
