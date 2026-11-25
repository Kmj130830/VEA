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

    fun createResidualBlockExecutor(
        firstInputWorkspace: S5MegpoidWorkspaceSlot = S5MegpoidWorkspaceSlot.FIRST
    ): S5MegpoidResidualBlockExecutor =
        S5MegpoidResidualBlockExecutor.fromCompiledBlocks(
            blocks = residualBlocks,
            firstInputWorkspace = firstInputWorkspace
        )
}
