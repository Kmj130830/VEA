package com.kmj.vea.engine

/**
 * Fully checked, reusable model-side state prepared for an AI Megpoid render.
 *
 * The feature tensor passed into S5MegpoidSequenceTransitionPipeline and the
 * final spectral-to-PCM path are deliberately separate: this object is not
 * itself a completed singing renderer.
 */
/**
 * Checked input for the model-backed pre-residual Dense stage.
 * Phone rows are transposed from [position][feature] to [feature][position].
 */
data class PreparedMegpoidPhoneInput(
    val matrix: S5ApiDefaultPhoneFeatureMatrix,
    val denseDimensions: S5MegpoidDenseProjectionDimensions,
    val denseNormalizationStage: S5MegpoidDenseNormalizationStage
) {
    fun runDense(
        gemm: S5MegpoidGemmKernel = NativeS5MegpoidGemmKernel,
        statistics: S5MegpoidStatisticsKernel = NativeS5MegpoidStatisticsKernel
    ): FloatArray {
        require(matrix.rowCount > 0) { "The phone feature matrix is empty." }
        return denseNormalizationStage.forward(
            input = matrix.toFeatureMajor(),
            positions = matrix.rowCount,
            gemm = gemm,
            statistics = statistics
        )
    }
}

/**
 * Timed note-to-phone alignment together with the ready-to-run pre-residual
 * Dense input. Fallback labels remain visible so unsupported phone symbols
 * can be corrected instead of silently accepted as valid model categories.
 */
data class PreparedMegpoidExplicitPhoneInput(
    val alignment: VsmNotePhoneAlignmentResult,
    val input: PreparedMegpoidPhoneInput
)

data class PreparedMegpoidRuntime(
    val modelPackage: S5VoicebankModelPackage,
    val namedLayerParameters: List<S5MegpoidPackedNamedLayer>,
    val residualBlocks: List<S5MegpoidCompiledRuntimeBlock>,
    val runtimeOptions: S5MegpoidRuntimeOptionsDescriptor,
    val inputSchedules: List<VsmRenderSchedule>
) {
    /**
     * Output-transform inputs populated from the same selected VTB2
     * runtime-options object and the verified S5API blank-root defaults.
     * This prepares the final inverse/overlap stage; it does not generate
     * the model's complex spectrum or render PCM by itself.
     */
    val outputTransformInputs: S5MegpoidOutputTransformInputs
        get() = runtimeOptions.buildOutputTransformInputsFromNativeMegpoidDefaults()

    /** Decoded Alternate tensor parameters available in AI Megpoid V7's selected VTB2 subtree. */
    fun prepareAlternateTransitionParameters(
        tensorDecoder: S5TypedTensorDecoder = NativeS5TypedTensorDecoder
    ): List<S5MegpoidPreparedAlternateTransition> =
        modelPackage.prepareAlternateTransitionParameters(tensorDecoder)

    /** Builds one reverse-transition operator from the exact model tensors. */
    fun prepareAlternateTemporalProjection(
        sequenceIndex: Int,
        channels: Int = 32,
        tensorDecoder: S5TypedTensorDecoder = NativeS5TypedTensorDecoder
    ): S5MegpoidAlternateTemporalProjection =
        modelPackage.prepareAlternateTemporalProjection(
            sequenceIndex = sequenceIndex,
            channels = channels,
            tensorDecoder = tensorDecoder
        )

    /** Model-output spectrum dimensions recovered from root fields +0x04 and +0x08. */
    val spectrumOutputShape: S5MegpoidSpectrumOutputShape
        get() = runtimeOptions.buildSpectrumOutputShape()

    /** Allocates a zeroed complex spectrum frame for the future neural forward pass. */
    fun newModelSpectrumFrame(): FloatArray = spectrumOutputShape.newInterleavedFrame()
    /** Creates a fresh stateful output executor for one render/utterance. */
    fun createSpectralOverlapAdd(): S5MegpoidSpectralOverlapAdd =
        outputTransformInputs.createSpectralOverlapAdd()

    /**
     * Creates a checked output session that accepts one model-produced
     * interleaved complex spectrum per frame and preserves overlap history
     * until reset or utterance completion.
     */
    fun createSpectralOutputSession(): PreparedMegpoidSpectralOutputSession {
        val transformInputs = outputTransformInputs
        return PreparedMegpoidSpectralOutputSession(
            spectrumShape = spectrumOutputShape,
            outputSamples = transformInputs.outputSamplesAtRoot0C,
            overlapAdd = transformInputs.createSpectralOverlapAdd()
        )
    }

    /**
     * Consumes the last two output-head feature planes using the encoding
     * selected by this loaded AI Megpoid model's VTB2 runtime options.
     */
    fun processModelOutputPlanes(
        outputSession: PreparedMegpoidSpectralOutputSession,
        firstFeaturePlane: FloatArray,
        secondFeaturePlane: FloatArray
    ): FloatArray = outputSession.processModelOutputPlanes(
        firstFeaturePlane = firstFeaturePlane,
        secondFeaturePlane = secondFeaturePlane,
        runtimeOptions = runtimeOptions
    )

    val scheduledNotes: List<VsmScheduledNote>
        get() = inputSchedules.flatMap { schedule ->
            schedule.chunks.flatMap { chunk -> chunk.noteStarts }
        }

    /** Lexical token view; it does not turn editor phoneme text into timed phones. */
    val lyricTokensByTrackAndNote: Map<String, List<String>>
        get() = scheduledNotes.associate { note ->
            "${note.trackId}:${note.noteId}" to note.lyricTokens
        }

    val scheduledNoteCount: Int
        get() = scheduledNotes.size

    /** Exact Dense input/output widths derived from the loaded Megpoid model tensors. */
    val denseProjectionDimensionsFromModel: S5MegpoidDenseProjectionDimensions
        get() = modelPackage.preResidualTensorIndex.resolveDenseProjectionDimensionsFromModel()

    /** Phone-feature row width required by this model's first Dense operator. */
    val requiredPhoneFeatureWidth: Int
        get() = denseProjectionDimensionsFromModel.inputFeatures

    /**
     * Preview Korean lyric-to-phone mapping for this schedule.
     *
     * The returned labels are diagnostic only because the upstream Korean
     * source-flag and intermediate-transformation stages remain unported.
     */
    fun previewKoreanG2p(
        schedule: VsmRenderSchedule,
        language: String
    ): List<KoreanG2pScheduledNotePreview> =
        KoreanG2pPreviewEngine.previewSchedule(schedule, language)

    /** Builds part modulation using the validated VTB2 mask and threshold flags. */
    fun prepareMultipartOutputModulation(
        positionCount: Int,
        fundamentalFrequencyHz: Float,
        thetaCenterRadians: Float,
        coordinateKernel: S5MegpoidMultipartCoordinateKernel =
            S5MegpoidMultipartCoordinateKernel.SIMD_SSE_TABLE_TRUNCATION
    ): S5MegpoidMultipartOutputModulation =
        runtimeOptions.buildMultipartOutputModulation(
            positionCount = positionCount,
            fundamentalFrequencyHz = fundamentalFrequencyHz,
            thetaCenterRadians = thetaCenterRadians,
            coordinateKernel = coordinateKernel
        )

    /**
     * Builds the active model-backed 32-to-8 multipart output head.
     * This is the path selected by AI Megpoid V7's D00=4, D01=true options.
     */
    fun prepareMultipartOutputHeadStage(
        tensorDecoder: S5TypedTensorDecoder = NativeS5TypedTensorDecoder
    ): S5MegpoidMultipartOutputHeadStage {
        check(runtimeOptions.usesMultiPartOutputHead) {
            "The selected voicebank does not use the multipart output head."
        }
        return modelPackage.prepareMultipartOutputHeadStage(tensorDecoder)
    }

    /**
     * Runs the model-backed output projection on the 32-channel sequence
     * result and splits the resulting 2*D00 feature rows into real/imaginary
     * pairs in the native per-part order.
     */
    fun projectMultipartOutputHead(
        sequenceOutput: S5MegpoidSequenceTransitionOutput,
        stage: S5MegpoidMultipartOutputHeadStage =
            prepareMultipartOutputHeadStage(),
        gemm: S5MegpoidGemmKernel = NativeS5MegpoidGemmKernel,
        statistics: S5MegpoidStatisticsKernel = NativeS5MegpoidStatisticsKernel
    ): S5MegpoidMultipartFeaturePlanes {
        check(runtimeOptions.usesMultiPartOutputHead) {
            "The selected voicebank does not use the multipart output head."
        }
        require(stage.partCount == runtimeOptions.paramD00) {
            "Multipart output-head stage has ${stage.partCount} parts; model options require ${runtimeOptions.paramD00}."
        }
        return stage.project(
            sequenceOutput = sequenceOutput,
            gemm = gemm,
            statistics = statistics
        )
    }

    /**
     * Executes the portable four-part complex accumulation after the
     * model-backed 32-to-8 projection. The returned interleaved complex pairs
     * are the S5API multipart accumulator before carrier-history addition
     * and inverse-FFT/overlap-add output.
     */
    fun accumulateMultipartComplexOutput(
        sequenceOutput: S5MegpoidSequenceTransitionOutput,
        modulation: S5MegpoidMultipartOutputModulation,
        stage: S5MegpoidMultipartOutputHeadStage =
            prepareMultipartOutputHeadStage(),
        gemm: S5MegpoidGemmKernel = NativeS5MegpoidGemmKernel,
        statistics: S5MegpoidStatisticsKernel = NativeS5MegpoidStatisticsKernel
    ): FloatArray {
        val planes = projectMultipartOutputHead(
            sequenceOutput = sequenceOutput,
            stage = stage,
            gemm = gemm,
            statistics = statistics
        )
        require(modulation.partCount == runtimeOptions.paramD00) {
            "Multipart modulation part count does not match the active model options."
        }
        val accumulator = planes.newInterleavedComplexAccumulator()
        planes.accumulateInto(accumulator, modulation)
        return accumulator
    }

    /** Actual terminal spectrum representation read from this singer's VTB2 options. */
    val terminalSpectrumEncoding: S5MegpoidSpectrumFrameEncoding
        get() = runtimeOptions.terminalSpectrumEncoding

    /** Whether the independent native carrier-fusion branch is active. */
    val carrierFusionEnabled: Boolean
        get() = runtimeOptions.carrierFusionEnabled

    /** True only when the native non-multi-part Dense fallback branch is selected. */
    val postResidualFallbackHeadActive: Boolean
        get() = !runtimeOptions.usesMultiPartOutputHead

    /** Builds the non-multi-part fallback output head only when its native branch is selected. */
    fun preparePostResidualFallbackStage(
        tensorDecoder: S5TypedTensorDecoder = NativeS5TypedTensorDecoder
    ): S5MegpoidPostResidualFallbackStage {
        check(postResidualFallbackHeadActive) {
            "The loaded Megpoid options select the multi-part output head, not the fallback Dense head."
        }
        return modelPackage.preparePostResidualFallbackStage(tensorDecoder)
    }

    /** Exact optional carrier tensor references; null when this voicebank disables carrier fusion. */
    val carrierFusionTensorIndex: S5MegpoidCarrierFusionTensorIndex?
        get() = modelPackage.carrierFusionTensorIndex

    /** Prepares the 32-input / 2-output carrier Dense layer when carrier fusion is enabled. */
    fun prepareCarrierFusionFallbackDenseProjection(
        tensorDecoder: S5TypedTensorDecoder = NativeS5TypedTensorDecoder
    ): S5MegpoidDenseProjection {
        check(carrierFusionEnabled) {
            "Carrier fusion is disabled by the selected voicebank runtime options."
        }
        return modelPackage.prepareCarrierFusionFallbackDenseProjection(tensorDecoder)
    }

    /** Builds the active carrier matrix and affine controls from this singer's VTB2 tensors. */
    fun prepareCarrierFusionActiveParameters(
        tensorDecoder: S5TypedTensorDecoder = NativeS5TypedTensorDecoder
    ): S5MegpoidCarrierFusionActiveParameters {
        check(carrierFusionEnabled) {
            "Carrier fusion is disabled by the selected voicebank runtime options."
        }
        return modelPackage.prepareCarrierFusionActiveParameters(tensorDecoder)
    }

    /** Runs the active model-backed carrier GEMM and native affine-control step. */
    fun projectCarrierConditionedFeatures(
        workspace: S5MegpoidCarrierFusionWorkspace,
        inputFeatureMajor: FloatArray,
        scale: Float,
        amplitude: Float,
        activeParameters: S5MegpoidCarrierFusionActiveParameters =
            prepareCarrierFusionActiveParameters(),
        gemm: S5MegpoidGemmKernel = NativeS5MegpoidGemmKernel
    ): FloatArray {
        check(carrierFusionEnabled) {
            "Carrier fusion is disabled by the selected voicebank runtime options."
        }
        return workspace.projectCarrierConditionedFeatures(
            inputFeatureMajor = inputFeatureMajor,
            activeParameters = activeParameters,
            scale = scale,
            amplitude = amplitude,
            gemm = gemm
        )
    }

    /** Builds the carrier Dense workspace using active render-context geometry. */
    fun createCarrierFusionWorkspace(
        carrierPositions: Int,
        historySlotsFromRoot181: Int,
        historySlotsFromRoot182: Int
    ): S5MegpoidCarrierFusionWorkspace {
        check(carrierFusionEnabled) {
            "Carrier fusion is disabled by the selected voicebank runtime options."
        }
        return modelPackage.createCarrierFusionWorkspace(
            carrierPositions = carrierPositions,
            historySlotsFromRoot181 = historySlotsFromRoot181,
            historySlotsFromRoot182 = historySlotsFromRoot182
        )
    }

    fun prepareAlignedPhoneFeatures(
        phoneEvents: List<VsmPhoneEvent>,
        minimumPhoneSeconds: Float,
        maximumPhoneSeconds: Float
    ): List<VsmAlignedPhoneFeature> =
        VsmAlignedPhoneFeaturePreparer.prepare(
            source = phoneEvents,
            minimumPhoneSeconds = minimumPhoneSeconds,
            maximumPhoneSeconds = maximumPhoneSeconds
        )

    /**
     * Builds the recovered mode-0 phone input matrix once actual aligned
     * phone events and the active render descriptor fields are available.
     */
    fun prepareDefaultPhoneFeatureMatrix(
        phoneEvents: List<VsmPhoneEvent>,
        layout: S5ApiDefaultPhoneFeatureLayout,
        minimumPhoneSeconds: Float,
        maximumPhoneSeconds: Float
    ): S5ApiDefaultPhoneFeatureMatrix {
        val aligned = prepareAlignedPhoneFeatures(
            phoneEvents = phoneEvents,
            minimumPhoneSeconds = minimumPhoneSeconds,
            maximumPhoneSeconds = maximumPhoneSeconds
        )
        return S5ApiDefaultPhoneFeatureMatrixBuilder.build(aligned, layout)
    }

    /**
     * Builds mode-0 phone rows and resolves the first model-backed Dense
     * operator from real tensor element counts. Phone timing and render
     * descriptor values must be supplied; none are inferred or fabricated.
     */
    fun preparePhoneInputForDense(
        phoneEvents: List<VsmPhoneEvent>,
        layout: S5ApiDefaultPhoneFeatureLayout,
        minimumPhoneSeconds: Float,
        maximumPhoneSeconds: Float
    ): PreparedMegpoidPhoneInput {
        val matrix = prepareDefaultPhoneFeatureMatrix(
            phoneEvents = phoneEvents,
            layout = layout,
            minimumPhoneSeconds = minimumPhoneSeconds,
            maximumPhoneSeconds = maximumPhoneSeconds
        )
        require(matrix.rowCount > 0) { "There are no aligned phone events to prepare." }
        val dimensions = denseProjectionDimensionsFromModel
        require(matrix.rowWidth == dimensions.inputFeatures) {
            "The active phone-feature layout produces ${matrix.rowWidth} values per phone, " +
                "but the loaded Megpoid Dense tensor requires ${dimensions.inputFeatures}. " +
                "Recover the active S5API phone-layout descriptor before inference."
        }
        val stage = modelPackage.preparePreResidualDenseNormalizationStageFromModel()
        return PreparedMegpoidPhoneInput(matrix, dimensions, stage)
    }

    /** Uses the exact mode-0 render-context descriptor as one validated value object. */
    fun preparePhoneInputForDense(
        phoneEvents: List<VsmPhoneEvent>,
        descriptor: S5ApiDefaultPhoneFeatureDescriptor
    ): PreparedMegpoidPhoneInput =
        preparePhoneInputForDense(
            phoneEvents = phoneEvents,
            layout = descriptor.requireDefaultMode0(),
            minimumPhoneSeconds = descriptor.minimumPhoneSecondsAt14,
            maximumPhoneSeconds = descriptor.maximumPhoneSecondsAt18
        )

    /**
     * Aligns explicit note phoneme strings to the scheduled sample timeline,
     * then builds the checked phone-feature matrix and model-backed Dense stage.
     *
     * This is deliberately not G2P: every note must already contain explicit
     * phone labels. The active feature layout and duration limits are supplied
     * by the caller because their native render-context descriptor fields are
     * not yet fully populated by the Android port.
     */
    fun prepareExplicitPhoneInputForDense(
        schedule: VsmRenderSchedule,
        layout: S5ApiDefaultPhoneFeatureLayout,
        minimumPhoneSeconds: Float,
        maximumPhoneSeconds: Float
    ): PreparedMegpoidExplicitPhoneInput {
        val alignment = VsmNotePhoneAligner.alignExplicitPhonemes(schedule)
        require(alignment.events.isNotEmpty()) {
            "There are no timed phone events to feed into the Megpoid model."
        }
        val input = preparePhoneInputForDense(
            phoneEvents = alignment.events,
            layout = layout,
            minimumPhoneSeconds = minimumPhoneSeconds,
            maximumPhoneSeconds = maximumPhoneSeconds
        )
        return PreparedMegpoidExplicitPhoneInput(
            alignment = alignment,
            input = input
        )
    }

    /** Aligns explicit note phones and prepares features from a validated mode-0 descriptor. */
    fun prepareExplicitPhoneInputForDense(
        schedule: VsmRenderSchedule,
        descriptor: S5ApiDefaultPhoneFeatureDescriptor
    ): PreparedMegpoidExplicitPhoneInput {
        val alignment = VsmNotePhoneAligner.alignExplicitPhonemes(schedule)
        require(alignment.events.isNotEmpty()) {
            "There are no timed phone events to feed into the Megpoid model."
        }
        val input = preparePhoneInputForDense(
            phoneEvents = alignment.events,
            descriptor = descriptor
        )
        return PreparedMegpoidExplicitPhoneInput(
            alignment = alignment,
            input = input
        )
    }

    /** Creates the native-selected Alternate model sequence path for AI Megpoid V7. */
    fun createAlternateSequenceTransitionPipeline(
        tensorDecoder: S5TypedTensorDecoder = NativeS5TypedTensorDecoder,
        firstInputWorkspace: S5MegpoidWorkspaceSlot = S5MegpoidWorkspaceSlot.FIRST
    ): S5MegpoidAlternateSequenceTransitionPipeline =
        modelPackage.createMegpoidV7AlternateSequenceTransitionPipeline(
            tensorDecoder = tensorDecoder,
            firstInputWorkspace = firstInputWorkspace
        )

    /**
     * Creates a stateful endpoint from scheduled notes to the loaded
     * Megpoid V7 Dense/Temporal/Alternate/Residual graph. Explicit phone labels
     * and the active mode-0 render descriptor are still required.
     */
    fun createExplicitPhoneSequenceSession(
        tensorDecoder: S5TypedTensorDecoder = NativeS5TypedTensorDecoder,
        firstInputWorkspace: S5MegpoidWorkspaceSlot = S5MegpoidWorkspaceSlot.FIRST
    ): PreparedMegpoidExplicitPhoneSequenceSession =
        PreparedMegpoidExplicitPhoneSequenceSession(
            pipeline = createAlternateSequenceTransitionPipeline(
                tensorDecoder = tensorDecoder,
                firstInputWorkspace = firstInputWorkspace
            )
        )

    fun createResidualBlockExecutor(
        firstInputWorkspace: S5MegpoidWorkspaceSlot = S5MegpoidWorkspaceSlot.FIRST
    ): S5MegpoidResidualBlockExecutor =
        S5MegpoidResidualBlockExecutor.fromCompiledBlocks(
            blocks = residualBlocks,
            firstInputWorkspace = firstInputWorkspace
        )
}
