package com.kmj.vea.engine

/**
 * A validated AI Megpoid V7 model set assembled from the singer-bank archive
 * and the shared VOCALOID7 model archive.
 *
 * The member hashes are checked against the hashes recorded by the supplied
 * AI Megpoid V7 manifest/catalog. A successful result means the five model
 * members were read, decoded and parsed into object trees; it does not mean
 * that neural inference or audio rendering is available.
 */
data class S5VoicebankModelPackage(
    override val voicebankId: String,
    val componentId: String,
    val models: S5ModelArchive
) : VoicebankModelPackage {
    override val engineFamily: String get() = "VOCALOID7"

    val singerModel: S5ModelArchiveMember
        get() = models.singerModel

    val modelMemberCount: Int
        get() = models.members.size

    /**
     * Lazily indexes the fixed S5API model-block tensor references contained
     * in AI_Megpoid.vtb2. Values remain encoded until explicitly requested.
     */
    val modelBlockTensorIndex: S5MegpoidBlockTensorIndex by lazy(LazyThreadSafetyMode.PUBLICATION) {
        S5MegpoidBlockTensorIndex.build(singerModel.tree)
    }

    /**
     * The exact nested VTB2 object selected by the high-level S5API
     * render-state initializer before it constructs residual-block records.
     * This is a model-data view, not an executable renderer.
     */
    val residualRuntimeConfigTree: S5ModelObjectTree by lazy(
        LazyThreadSafetyMode.PUBLICATION
    ) {
        singerModel.tree.subtreeAtPath(
            S5MegpoidRuntimeModelPath.MODEL_CONTEXT_KEY,
            S5MegpoidRuntimeModelPath.RESIDUAL_RUNTIME_CONFIG_KEY
        ) ?: throw IllegalStateException(
            "AI_Megpoid.vtb2 is missing the S5API residual runtime configuration path."
        )
    }

    /**
     * The sibling runtime-options object selected by FUN_1800ced10 from the
     * same native-selected configuration node. Its hashed members populate
     * scalar/flag fields in the native runtime descriptor.
     */
    val runtimeOptionsTree: S5ModelObjectTree by lazy(
        LazyThreadSafetyMode.PUBLICATION
    ) {
        residualRuntimeConfigTree.subtreeAtPath(
            S5MegpoidRuntimeModelPath.RUNTIME_OPTIONS_KEY
        ) ?: throw IllegalStateException(
            "AI_Megpoid.vtb2 is missing the S5API runtime-options object."
        )
    }

    /** Scalar render-context options read from the exact native-selected VTB2 object. */
    val runtimeOptionsDescriptor: S5MegpoidRuntimeOptionsDescriptor by lazy(
        LazyThreadSafetyMode.PUBLICATION
    ) {
        S5MegpoidRuntimeOptionsDescriptor.from(runtimeOptionsTree)
    }

    /**
     * Exact VTB2 tensors used by the pre-residual dense and global-normalization operators.
     */
    val preResidualTensorIndex: S5MegpoidPreResidualTensorIndex by lazy(
        LazyThreadSafetyMode.PUBLICATION
    ) {
        S5MegpoidPreResidualTensorIndex.build(residualRuntimeConfigTree)
    }

    /**
     * Optional carrier Dense tensors. AI Megpoid V7's selected runtime
     * options enable carrier fusion, so its exact two model tensor records
     * are indexed from the selected residual configuration subtree.
     *
     * The references retain native metadata/data pairs; they are not yet
     * reinterpreted as a conventional Dense matrix.
     */
    val carrierFusionTensorIndex: S5MegpoidCarrierFusionTensorIndex? by lazy(
        LazyThreadSafetyMode.PUBLICATION
    ) {
        if (runtimeOptionsDescriptor.carrierFusionEnabled) {
            S5MegpoidCarrierFusionTensorIndex.build(residualRuntimeConfigTree)
        } else {
            null
        }
    }

    fun requireCarrierFusionTensorIndex(): S5MegpoidCarrierFusionTensorIndex =
        carrierFusionTensorIndex ?: throw IllegalStateException(
            "Carrier fusion is disabled in the selected AI Megpoid runtime options."
        )

    /**
     * Native-decoded active carrier parameters are shared across render chunks.
     * The source matrix and control vectors are model constants, not per-frame
     * values; cache them after the first validated decode.
     */
    val activeCarrierFusionParameters: S5MegpoidCarrierFusionActiveParameters by lazy(
        LazyThreadSafetyMode.PUBLICATION
    ) {
        buildCarrierFusionActiveParameters(NativeS5TypedTensorDecoder)
    }

    /** Builds the optional carrier Dense layer using the recovered 2-output matrix contract. */
    fun prepareCarrierFusionFallbackDenseProjection(
        tensorDecoder: S5TypedTensorDecoder = NativeS5TypedTensorDecoder
    ): S5MegpoidDenseProjection =
        requireCarrierFusionTensorIndex().buildFallbackDenseProjection(tensorDecoder)

    /**
     * Compiles the active carrier source matrix and control vectors from
     * VTB2. The +0xD60 Dense descriptor supplies W/b; the shared [32]-element
     * normalization scale/bias supply the two conditioning vectors.
     */
    fun prepareCarrierFusionActiveParameters(
        tensorDecoder: S5TypedTensorDecoder = NativeS5TypedTensorDecoder
    ): S5MegpoidCarrierFusionActiveParameters =
        if (tensorDecoder === NativeS5TypedTensorDecoder) {
            activeCarrierFusionParameters
        } else {
            buildCarrierFusionActiveParameters(tensorDecoder)
        }

    private fun buildCarrierFusionActiveParameters(
        tensorDecoder: S5TypedTensorDecoder
    ): S5MegpoidCarrierFusionActiveParameters {
        val carrierTensors = requireCarrierFusionTensorIndex()
        val outputHeadTensors = postResidualFallbackTensorIndex
        return S5MegpoidCarrierFusionActiveParameters.build(
            carrierTensors = carrierTensors,
            normalizationScale = outputHeadTensors.normalizationScale.decodeFloatArray(tensorDecoder),
            normalizationBias = outputHeadTensors.normalizationBias.decodeFloatArray(tensorDecoder),
            tensorDecoder = tensorDecoder
        )
    }

    /**
     * Creates carrier Dense and circular-spectrum history buffers from
     * render-context counts supplied by the active render session.
     *
     * carrierPositions maps to the native count read through root +0x18.
     * The two history contributions map to root words +0x181 / +0x182.
     * Spectrum bins are taken from the selected model runtime options.
     */
    fun createCarrierFusionWorkspace(
        carrierPositions: Int,
        historySlotsFromRoot181: Int,
        historySlotsFromRoot182: Int
    ): S5MegpoidCarrierFusionWorkspace {
        require(runtimeOptionsDescriptor.carrierFusionEnabled) {
            "Carrier fusion is disabled by the selected AI Megpoid runtime options."
        }
        // The active carrier-fusion path uses a runtime-generated 2xK
        // source matrix at root +0x15E. The VTB2 +0xD60 Dense projection
        // is only the native fallback when the dynamic source path is absent.
        val inputFeatures = requireCarrierFusionTensorIndex().resolveDenseDimensions().inputFeatures
        val geometry = S5MegpoidCarrierFusionBufferGeometry(
            inputFeatures = inputFeatures,
            carrierPositions = carrierPositions,
            spectrumBins = runtimeOptionsDescriptor.buildSpectrumOutputShape().complexBinCount,
            historySlotsFromRoot181 = historySlotsFromRoot181,
            historySlotsFromRoot182 = historySlotsFromRoot182
        )
        return S5MegpoidCarrierFusionWorkspace(geometry)
    }

    /**
     * Model-backed non-multi-part fallback output head at render-root +0xC4/+0xCE.
     * The tensors are present even when the four-part output head is selected.
     */
    val postResidualFallbackTensorIndex: S5MegpoidPostResidualFallbackTensorIndex by lazy(
        LazyThreadSafetyMode.PUBLICATION
    ) {
        S5MegpoidPostResidualFallbackTensorIndex.build(residualRuntimeConfigTree)
    }

    /** Builds the fallback head only when the native D00/D01 branch selects it. */
    fun preparePostResidualFallbackStage(
        tensorDecoder: S5TypedTensorDecoder = NativeS5TypedTensorDecoder
    ): S5MegpoidPostResidualFallbackStage {
        check(!runtimeOptionsDescriptor.usesMultiPartOutputHead) {
            "The selected AI Megpoid options use the multi-part output head, not the fallback Dense head."
        }
        return postResidualFallbackTensorIndex.buildStage(tensorDecoder)
    }

    /** Exact sequence-indexed Temporal/Banked factory tables populated by FUN_1800ced10. */
    val sequenceOperatorTensorIndex: S5MegpoidSequenceOperatorTensorIndex by lazy(
        LazyThreadSafetyMode.PUBLICATION
    ) {
        S5MegpoidSequenceOperatorTensorIndex.build(residualRuntimeConfigTree)
    }

    /**
     * Decoded AI Megpoid V7 Alternate transition tensor parameters. This is
     * parameter preparation only; FUN_1800c72d0 has not yet been ported.
     */
    val preparedAlternateTransitionParameters: List<S5MegpoidPreparedAlternateTransition> by lazy(
        LazyThreadSafetyMode.PUBLICATION
    ) {
        sequenceOperatorTensorIndex.decodeMegpoidV7AlternateTransitionSet()
    }

    fun prepareAlternateTransitionParameters(
        tensorDecoder: S5TypedTensorDecoder = NativeS5TypedTensorDecoder
    ): List<S5MegpoidPreparedAlternateTransition> =
        if (tensorDecoder === NativeS5TypedTensorDecoder) {
            preparedAlternateTransitionParameters
        } else {
            sequenceOperatorTensorIndex.decodeMegpoidV7AlternateTransitionSet(tensorDecoder)
        }

    /**
     * Lazily indexes the eight FUN_1800ced10 model-property families for
     * the four-by-two block grid, scoped to the exact subtree selected by
     * the high-level native initializer.
     */
    val runtimeBlockTensorIndex: S5MegpoidRuntimeBlockTensorIndex by lazy(
        LazyThreadSafetyMode.PUBLICATION
    ) {
        S5MegpoidRuntimeBlockTensorIndex.build(
            residualRuntimeConfigTree,
            S5MegpoidRuntimeBlockFactoryKeys.ALL
        )
    }

    /**
     * Decodes and assembles the eight runtime residual-block parameter sets
     * (4 rows x 2 columns) from AI_Megpoid.vtb2.
     *
     * This prepares reusable block weights only. Audio chunk length is not
     * part of the stored model parameters; it is validated when a block is
     * executed with a concrete history window. S5API's stateful history/ring
     * operator FUN_1800c67e0 and the full singing renderer remain separate.
     */
    fun prepareRuntimeBlocks(
        tensorDecoder: S5TypedTensorDecoder = NativeS5TypedTensorDecoder
    ): List<S5MegpoidCompiledRuntimeBlock> = buildList {
        for (row in 0 until S5MegpoidRuntimeBlockFactoryKeys.BLOCK_ROWS) {
            for (column in 0 until S5MegpoidRuntimeBlockFactoryKeys.BLOCK_COLUMNS) {
                add(
                    S5MegpoidRuntimeBlockAssembler.compile(
                        index = runtimeBlockTensorIndex,
                        row = row,
                        column = column,
                        tensorDecoder = tensorDecoder
                    )
                )
            }
        }
    }

    /**
     * Creates a stateful executor from the eight compiled AI_Megpoid.vtb2
     * residual blocks. Keep the returned instance across sequential chunks;
     * call resetHistory() when starting a new utterance.
     */
    fun createResidualBlockExecutor(
        tensorDecoder: S5TypedTensorDecoder = NativeS5TypedTensorDecoder,
        firstInputWorkspace: S5MegpoidWorkspaceSlot = S5MegpoidWorkspaceSlot.FIRST
    ): S5MegpoidResidualBlockExecutor =
        S5MegpoidResidualBlockExecutor.fromCompiledBlocks(
            blocks = prepareRuntimeBlocks(tensorDecoder),
            firstInputWorkspace = firstInputWorkspace
        )

    fun prepareTemporalProjection(
        sequenceIndex: Int,
        inputFeatures: Int,
        outputFeatures: Int,
        kernelSize: Int,
        biasEnabled: Boolean,
        tensorDecoder: S5TypedTensorDecoder = NativeS5TypedTensorDecoder
    ): S5MegpoidTemporalStridedConvolution =
        sequenceOperatorTensorIndex.buildTemporalProjection(
            sequenceIndex, inputFeatures, outputFeatures, kernelSize, biasEnabled, tensorDecoder
        )

    fun prepareBankedLinearProjection(
        sequenceIndex: Int,
        inputFeatures: Int,
        outputFeatures: Int,
        banks: Int,
        biasEnabled: Boolean,
        tensorDecoder: S5TypedTensorDecoder = NativeS5TypedTensorDecoder
    ): S5MegpoidBankedLinearProjection =
        sequenceOperatorTensorIndex.buildBankedLinearProjection(
            sequenceIndex, inputFeatures, outputFeatures, banks, biasEnabled, tensorDecoder
        )

    fun alternateTransitionTensors(
        sequenceIndex: Int
    ): S5MegpoidAlternateTransitionTensorPair? =
        sequenceOperatorTensorIndex.alternateTransitionPairOrNull(sequenceIndex)

    /** Builds a concrete Alternate upsampling/projection operator from one indexed model pair. */
    fun prepareAlternateTemporalProjection(
        sequenceIndex: Int,
        channels: Int = 32,
        tensorDecoder: S5TypedTensorDecoder = NativeS5TypedTensorDecoder
    ): S5MegpoidAlternateTemporalProjection =
        sequenceOperatorTensorIndex.buildMegpoidV7AlternateTemporalProjection(
            index = sequenceIndex,
            channels = channels,
            tensorDecoder = tensorDecoder
        )

    /**
     * Builds Dense -> three forward Temporal transitions -> native reverse
     * residual path: Residual[3], then Banked[0]/Residual[2],
     * Banked[1]/Residual[1], Banked[2]/Residual[0].
     * Feature dimensions, kernel sizes, bank counts and bias flags come from
     * the active render descriptor.
     */
    fun createSequenceTransitionPipeline(
        denseInputFeatures: Int,
        denseOutputFeatures: Int,
        temporalOutputFeatures: IntArray,
        temporalKernelSizes: IntArray,
        temporalBiasEnabled: BooleanArray,
        bankCounts: IntArray,
        bankedBiasEnabled: BooleanArray,
        tensorDecoder: S5TypedTensorDecoder = NativeS5TypedTensorDecoder,
        firstInputWorkspace: S5MegpoidWorkspaceSlot = S5MegpoidWorkspaceSlot.FIRST
    ): S5MegpoidSequenceTransitionPipeline {
        runtimeOptionsDescriptor.requireMegpoidV7BlockGrid()
        val transitionCount = runtimeOptionsDescriptor.temporalTransitionCount
        require(
            temporalOutputFeatures.size == transitionCount &&
                temporalKernelSizes.size == transitionCount &&
                temporalBiasEnabled.size == transitionCount &&
                bankCounts.size == transitionCount &&
                bankedBiasEnabled.size == transitionCount
        ) {
            "Runtime options require " + transitionCount +
                " Temporal and Banked descriptor entries for this model."
        }

        val dense = preparePreResidualDenseNormalizationStage(
            denseInputFeatures, denseOutputFeatures, tensorDecoder
        )
        var temporalInputFeatures = denseOutputFeatures
        val temporalStages = buildList {
            for (index in 0 until transitionCount) {
                val outputFeatures = temporalOutputFeatures[index]
                add(
                    prepareTemporalProjection(
                        sequenceIndex = index,
                        inputFeatures = temporalInputFeatures,
                        outputFeatures = outputFeatures,
                        kernelSize = temporalKernelSizes[index],
                        biasEnabled = temporalBiasEnabled[index],
                        tensorDecoder = tensorDecoder
                    )
                )
                temporalInputFeatures = outputFeatures
            }
        }

        val residualExecutor = createResidualBlockExecutor(
            tensorDecoder = tensorDecoder,
            firstInputWorkspace = firstInputWorkspace
        )
        val residualChannels = residualExecutor.channels
        val bankedStages = buildList {
            for (index in 0 until transitionCount) {
                val inputFeatures = residualChannels
                add(
                    prepareBankedLinearProjection(
                        sequenceIndex = index,
                        inputFeatures = inputFeatures,
                        outputFeatures = residualChannels,
                        banks = bankCounts[index],
                        biasEnabled = bankedBiasEnabled[index],
                        tensorDecoder = tensorDecoder
                    )
                )
            }
        }

        return S5MegpoidSequenceTransitionPipeline.fromModelStages(
            denseStage = dense,
            temporalStages = temporalStages,
            bankedStages = bankedStages,
            residualExecutor = residualExecutor
        )
    }

    fun prepareSequenceProjectionStage(
        sequenceIndex: Int,
        denseInputFeatures: Int,
        denseOutputFeatures: Int,
        temporalOutputFeatures: Int,
        temporalKernelSize: Int,
        temporalBiasEnabled: Boolean,
        tensorDecoder: S5TypedTensorDecoder = NativeS5TypedTensorDecoder
    ): S5MegpoidSequenceProjectionStage {
        val dense = preparePreResidualDenseNormalizationStage(
            denseInputFeatures, denseOutputFeatures, tensorDecoder
        )
        val temporal = prepareTemporalProjection(
            sequenceIndex, denseOutputFeatures, temporalOutputFeatures,
            temporalKernelSize, temporalBiasEnabled, tensorDecoder
        )
        return S5MegpoidSequenceProjectionStage(dense, temporal)
    }

    /**
     * Creates the model-backed dense projection + global normalization stage.
     * Dimensions must come from the active render-state descriptor.
     */
    fun preparePreResidualDenseNormalizationStage(
        inputFeatures: Int,
        outputFeatures: Int,
        tensorDecoder: S5TypedTensorDecoder = NativeS5TypedTensorDecoder
    ): S5MegpoidDenseNormalizationStage =
        preResidualTensorIndex.buildDenseNormalizationStage(
            inputFeatures = inputFeatures,
            outputFeatures = outputFeatures,
            tensorDecoder = tensorDecoder
        )

    /** Builds Dense + normalization after deriving both dimensions from the selected model tensors. */
    fun preparePreResidualDenseNormalizationStageFromModel(
        tensorDecoder: S5TypedTensorDecoder = NativeS5TypedTensorDecoder
    ): S5MegpoidDenseNormalizationStage =
        preResidualTensorIndex.buildDenseNormalizationStageFromModel(tensorDecoder)

    fun preparePreResidualDenseNormalizationStageForInputFeatures(
        inputFeatures: Int,
        tensorDecoder: S5TypedTensorDecoder = NativeS5TypedTensorDecoder
    ): S5MegpoidDenseNormalizationStage =
        preResidualTensorIndex.buildDenseNormalizationStageForInputFeatures(
            inputFeatures = inputFeatures,
            tensorDecoder = tensorDecoder
        )

    /**
     * Builds the sequence-major residual-block invocation plan recovered
     * from FUN_1800c7ad0. AI Megpoid V7 has four sequence slots, three
     * Temporal/Banked transitions, and a four-by-two residual grid.
     * The residual sweep is row 3 first, then Banked[0]/row 2,
     * Banked[1]/row 1, and Banked[2]/row 0.
     *
     * createSequenceTransitionPipeline() implements that native feedback order.
     */
    fun buildResidualBlockSchedule(sequenceCount: Int): S5MegpoidResidualBlockSchedule =
        S5MegpoidResidualBlockSchedule.megpoidV7(sequenceCount)

    /**
     * Lazily indexes the nine named neural layers in the shared VOCALOID7.vtmg.
     * This resolves actual model tensors but does not yet execute their network.
     */
    val namedLayerTensorIndex: S5MegpoidNamedLayerTensorIndex by lazy(
        LazyThreadSafetyMode.PUBLICATION
    ) {
        val sharedModel = models.members["VOCALOID7.vtmg"]
            ?: throw IllegalStateException("Shared VOCALOID7.vtmg model member is missing.")
        S5MegpoidNamedLayerTensorIndex.build(sharedModel.tree)
    }

    /**
     * Decodes and packs all nine shared named-layer weight/bias pairs into
     * the native BlockMatrix representation for a later inference stage.
     */
    fun prepareNamedLayerParameters(
        tensorDecoder: S5TypedTensorDecoder = NativeS5TypedTensorDecoder,
        matrixPacker: (FloatArray, Int, Int, Int) -> FloatArray =
            NativeS5TensorBridge::packMatrixBank,
        biasPacker: (FloatArray, Int, Int) -> FloatArray =
            NativeS5TensorBridge::packRowMajor
    ): List<S5MegpoidPackedNamedLayer> =
        namedLayerTensorIndex.packAllLayers(tensorDecoder, matrixPacker, biasPacker)


    /**
     * Creates a persistent native descriptor table for all nine named layers.
     * The caller owns the returned handle and must close it when the voicebank
     * is unloaded. This prepares BlockMatrix storage; it does not run inference.
     */
    fun prepareNativeNamedLayerParameterSet(
        tensorDecoder: S5TypedTensorDecoder = NativeS5TypedTensorDecoder
    ): S5MegpoidNativeParameterSet =
        namedLayerTensorIndex.prepareNativeParameterSet(tensorDecoder)
}

class S5VoicebankModelPackageAssembler(
    private val expectedSha256: Map<String, String> = AI_MEGPOID_V7_MODEL_SHA256
) {
    fun assemble(
        singerBankArchive: S5ModelArchive,
        sharedModelArchive: S5ModelArchive
    ): S5VoicebankModelPackage {
        val requiredBankMembers = setOf(
            "AI_Megpoid.vtb2",
            "AI_Megpoid.vpit"
        )
        val requiredSharedMembers = setOf(
            "COMMON7.vpit",
            "VOCALOID7.vhnv",
            "VOCALOID7.vtmg"
        )

        require(singerBankArchive.members.keys.containsAll(requiredBankMembers)) {
            "The singer archive must contain AI_Megpoid.vtb2 and AI_Megpoid.vpit."
        }
        require(sharedModelArchive.members.keys.containsAll(requiredSharedMembers)) {
            "The shared archive must contain COMMON7.vpit, VOCALOID7.vhnv and VOCALOID7.vtmg."
        }

        val combined = singerBankArchive.mergedWith(sharedModelArchive)
        require(combined.members.keys.containsAll(expectedSha256.keys)) {
            "The combined model set is missing one or more required AI Megpoid V7 members."
        }

        expectedSha256.forEach { (fileName, expectedHash) ->
            val actualHash = combined.members[fileName]?.sha256
                ?: throw IllegalArgumentException("Model member '$fileName' is missing.")
            require(actualHash.equals(expectedHash, ignoreCase = true)) {
                "Model member '$fileName' does not match the AI Megpoid V7 manifest hash."
            }
        }

        return S5VoicebankModelPackage(
            voicebankId = AI_MEGPOID_V7_ID,
            componentId = AI_MEGPOID_V7_COMPONENT_ID,
            models = combined
        )
    }

    companion object {
        const val AI_MEGPOID_V7_ID = "ai_megpoid_v7"
        const val AI_MEGPOID_V7_COMPONENT_ID = "BEAF64G3DYXLRECA"

        val AI_MEGPOID_V7_MODEL_SHA256: Map<String, String> = mapOf(
            "AI_Megpoid.vtb2" to "f09cad94a1c56c3ea5e96e77ae13887edbcb9cd52f0cd4167731f8d539c42f6a",
            "AI_Megpoid.vpit" to "40affbf4f76331655866821c2f6b589cc02cb0b0964c21bf48c92f283c0e846f",
            "VOCALOID7.vtmg" to "3384cc85fa5ccea1c3203b4ae7aee687a059ec67a7d3a9ebecb8c57170d2b36d",
            "COMMON7.vpit" to "2611bf3c7079ef97851f4103f7d88b9d900a1cbf7e4cffe61ae8d904c2273751",
            "VOCALOID7.vhnv" to "d8de8bfbf6ef4c9829431e09a29e4b105524d22faaada9001552cd89cb61d570"
        )
    }
}
