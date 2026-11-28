package com.kmj.vea.engine

/**
 * One explicitly phonemized note schedule after the AI Megpoid V7 sequence
 * graph. The output is still a feature tensor, not an acoustic spectrum or WAV.
 */
data class PreparedMegpoidExplicitPhoneSequenceResult(
    val alignment: VsmNotePhoneAlignmentResult,
    val phoneFeatureMatrix: S5ApiDefaultPhoneFeatureMatrix,
    val sequenceOutput: S5MegpoidSequenceTransitionOutput,
    /** Events after native break/rest preprocessing; indexes match phoneFeatureMatrix rows. */
    val featureEvents: List<VsmPhoneEvent> = emptyList(),
    /** Optional native +0x30 time/frame map; it is not yet the result of the VTMG stateful operators. */
    val frameAlignment: S5ApiTimedPhoneFrameAlignment? = null
) {
    val fallbackPhoneLabels: List<String>
        get() = alignment.fallbackPhoneLabels
}

/**
 * Stateful bridge from VEA's scheduled notes to the model-backed Megpoid
 * Dense -> Temporal -> Alternate -> Residual feature graph.
 *
 * The caller must supply explicit phone labels in each note and the actual
 * mode-0 phone-feature descriptor. This class intentionally does not invent
 * pronunciation, phone timing, or descriptor values. It also stops at the
 * event-level sequence feature tensor: an optional +0x30 timed-frame map is
 * returned as separate metadata when the caller supplies native param_8.
 * That map does not execute FUN_180076080/FUN_180077520 or create the later
 * frame-aligned VTMG features. Megpoid's multipart/carrier output head and
 * final spectrum-to-audio path remain separate stages.
 *
 * Keep one instance for sequential chunks of an utterance. Call resetHistory()
 * when starting a new utterance so the eight residual-block history rings do
 * not retain state from the previous singer phrase.
 */
class PreparedMegpoidExplicitPhoneSequenceSession(
    private val pipeline: S5MegpoidAlternateSequenceTransitionPipeline
) {
    val requiredPhoneFeatureWidth: Int
        get() = pipeline.inputFeatures

    @Synchronized
    fun process(
        schedule: VsmRenderSchedule,
        descriptor: S5ApiDefaultPhoneFeatureDescriptor,
        epsilon: Float = S5MegpoidResidualBlockShape.DEFAULT_EPSILON,
        gemm: S5MegpoidGemmKernel = NativeS5MegpoidGemmKernel,
        statistics: S5MegpoidStatisticsKernel = NativeS5MegpoidStatisticsKernel,
        eventControlForCategories2To13: Float? = null
    ): PreparedMegpoidExplicitPhoneSequenceResult {
        require(schedule.sampleRate == 44_100) {
            "The current AI Megpoid V7 sequence path expects the recovered 44.1 kHz VSM schedule."
        }

        val layout = descriptor.requireDefaultMode0()
        val alignment = VsmNotePhoneAligner.alignExplicitPhonemes(schedule)
        require(alignment.events.isNotEmpty()) {
            "The schedule contains no timed phone events."
        }

        val alignedFeatures = VsmAlignedPhoneFeaturePreparer.prepare(
            source = alignment.events,
            minimumPhoneSeconds = descriptor.minimumPhoneSecondsAt14,
            maximumPhoneSeconds = descriptor.maximumPhoneSecondsAt18
        )
        val featureEvents = alignedFeatures.map { it.event }
        val matrix = S5ApiDefaultPhoneFeatureMatrixBuilder.build(
            features = alignedFeatures,
            layout = layout
        )
        val frameAlignment = eventControlForCategories2To13?.let { eventControl ->
            S5ApiTimedPhoneFrameAligner.align(
                events = featureEvents,
                descriptor = descriptor,
                eventControlForCategories2To13 = eventControl
            )
        }

        require(matrix.rowCount > 0) {
            "Phone alignment produced an empty feature matrix."
        }
        require(matrix.rowWidth == pipeline.inputFeatures) {
            "The active phone descriptor produces ${matrix.rowWidth} features per phone, " +
                "but the loaded Megpoid Dense tensor requires ${pipeline.inputFeatures}."
        }

        val output = pipeline.runPhoneFeatureMatrix(
            phoneMatrix = matrix,
            epsilon = epsilon,
            gemm = gemm,
            statistics = statistics
        )
        return PreparedMegpoidExplicitPhoneSequenceResult(
            alignment = alignment,
            phoneFeatureMatrix = matrix,
            sequenceOutput = output,
            featureEvents = featureEvents,
            frameAlignment = frameAlignment
        )
    }

    @Synchronized
    fun resetHistory() {
        pipeline.resetHistory()
    }
}
