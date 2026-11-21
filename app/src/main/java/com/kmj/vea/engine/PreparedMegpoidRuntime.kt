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

data class PreparedMegpoidRuntime(
    val modelPackage: S5VoicebankModelPackage,
    val namedLayerParameters: List<S5MegpoidPackedNamedLayer>,
    val residualBlocks: List<S5MegpoidCompiledRuntimeBlock>,
    val runtimeOptions: S5MegpoidRuntimeOptionsDescriptor,
    val inputSchedules: List<VsmRenderSchedule>
) {
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
        val dimensions = modelPackage.preResidualTensorIndex.resolveDenseProjectionDimensions(
            inputFeatures = matrix.rowWidth
        )
        val stage = modelPackage.preparePreResidualDenseNormalizationStageForInputFeatures(
            inputFeatures = matrix.rowWidth
        )
        return PreparedMegpoidPhoneInput(matrix, dimensions, stage)
    }

    fun createResidualBlockExecutor(
        firstInputWorkspace: S5MegpoidWorkspaceSlot = S5MegpoidWorkspaceSlot.FIRST
    ): S5MegpoidResidualBlockExecutor =
        S5MegpoidResidualBlockExecutor.fromCompiledBlocks(
            blocks = residualBlocks,
            firstInputWorkspace = firstInputWorkspace
        )
}
