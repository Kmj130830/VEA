package com.kmj.vea.engine

/**
 * Full Dense -> Temporal -> Alternate -> Residual sequence path selected by
 * AI Megpoid V7 render-root byte +0x59.
 *
 * Native order:
 *   Dense + global normalization
 *   Temporal[0], Temporal[1], Temporal[2]
 *   Residual row 3
 *   Alternate[0] -> Residual row 2
 *   Alternate[1] -> Residual row 1
 *   Alternate[2] -> Residual row 0
 *
 * Each Alternate stage doubles the current position count, unlike the
 * standard Banked branch whose output is interleaved by bank count.
 */
class S5MegpoidAlternateSequenceTransitionPipeline(
    private val denseStage: S5MegpoidDenseNormalizationStage,
    temporalStages: List<S5MegpoidTemporalStridedConvolution>,
    alternateStages: List<S5MegpoidAlternateTemporalProjection>,
    private val residualExecutor: S5MegpoidResidualBlockExecutor
) {
    private val temporal = temporalStages.toList()
    private val alternate = alternateStages.toList()

    init {
        val transitions = S5MegpoidRuntimeBlockFactoryKeys.BLOCK_ROWS - 1
        require(temporal.size == transitions) {
            "The Megpoid V7 graph requires exactly $transitions forward Temporal stages."
        }
        require(alternate.size == transitions) {
            "The Megpoid V7 Alternate graph requires exactly $transitions reverse stages."
        }
        require(denseStage.normalization.features == temporal.first().inputFeatures) {
            "Dense output width must match Temporal[0] input width."
        }

        var activeFeatures = denseStage.normalization.features
        temporal.forEachIndexed { index, stage ->
            require(stage.inputFeatures == activeFeatures) {
                "Temporal[$index] input width does not match the preceding feature stage."
            }
            activeFeatures = stage.outputFeatures
        }
        require(activeFeatures == residualExecutor.channels) {
            "The final Temporal output must match the residual block channel count."
        }
        alternate.forEachIndexed { index, stage ->
            require(stage.inputFeatures == residualExecutor.channels &&
                stage.outputFeatures == residualExecutor.channels) {
                "Alternate[$index] must preserve the active residual channel width."
            }
        }
    }

    val inputFeatures: Int get() = denseStage.projection.inputFeatures
    val outputFeatures: Int get() = residualExecutor.channels
    val reverseTransitionCount: Int get() = alternate.size

    /** Runs a phone matrix after validating its feature width against Dense. */
    @Synchronized
    fun runPhoneFeatureMatrix(
        phoneMatrix: S5ApiDefaultPhoneFeatureMatrix,
        epsilon: Float = S5MegpoidResidualBlockShape.DEFAULT_EPSILON,
        gemm: S5MegpoidGemmKernel = NativeS5MegpoidGemmKernel,
        statistics: S5MegpoidStatisticsKernel = NativeS5MegpoidStatisticsKernel
    ): S5MegpoidSequenceTransitionOutput {
        require(phoneMatrix.rowCount > 0) { "The phone feature matrix is empty." }
        require(phoneMatrix.rowWidth == inputFeatures) {
            "Phone feature width ${phoneMatrix.rowWidth} does not match Dense input width $inputFeatures."
        }
        require(phoneMatrix.rowCount >= minimumInputPositionsForResidualHistory()) {
            "The phone feature sequence is too short for the Temporal and residual stages."
        }
        return run(
            input = phoneMatrix.toFeatureMajor(),
            inputPositions = phoneMatrix.rowCount,
            epsilon = epsilon,
            gemm = gemm,
            statistics = statistics
        )
    }

    @Synchronized
    fun run(
        input: FloatArray,
        inputPositions: Int,
        epsilon: Float = S5MegpoidResidualBlockShape.DEFAULT_EPSILON,
        gemm: S5MegpoidGemmKernel = NativeS5MegpoidGemmKernel,
        statistics: S5MegpoidStatisticsKernel = NativeS5MegpoidStatisticsKernel
    ): S5MegpoidSequenceTransitionOutput {
        require(inputPositions > 0)
        require(input.size.toLong() == inputFeatures.toLong() * inputPositions.toLong()) {
            "Dense input must contain inputFeatures * inputPositions values."
        }
        require(epsilon.isFinite() && epsilon >= 0.0f)

        var features = denseStage.forward(input, inputPositions, gemm, statistics)
        var featureCount = denseStage.normalization.features
        var positions = inputPositions
        require(features.size.toLong() == featureCount.toLong() * positions.toLong()) {
            "Dense/normalization stage returned an unexpected tensor shape."
        }

        temporal.forEachIndexed { index, stage ->
            check(stage.inputFeatures == featureCount) {
                "Temporal[$index] input feature count changed after pipeline construction."
            }
            val nextPositions = stage.outputPositions(positions)
            val nextFeatures = stage.project(features, positions, gemm)
            val expected = stage.outputFeatures.toLong() * nextPositions.toLong()
            require(nextFeatures.size.toLong() == expected) {
                "Temporal[$index] returned ${nextFeatures.size} values; expected $expected."
            }
            features = nextFeatures
            featureCount = stage.outputFeatures
            positions = nextPositions
        }

        require(positions >= S5MegpoidResidualBlockShape.KERNEL_WIDTH) {
            "Temporal stages reduced the sequence to $positions positions; " +
                "the residual 7x7 history kernel requires at least " +
                S5MegpoidResidualBlockShape.KERNEL_WIDTH + "."
        }
        check(featureCount == residualExecutor.channels)

        // Native dispatcher executes Residual row 3 before any reverse operator.
        features = residualExecutor.runTransitionRow(
            transitionRow = S5MegpoidRuntimeBlockFactoryKeys.BLOCK_ROWS - 1,
            residualInput = features,
            epsilon = epsilon
        )

        // Each Alternate stage doubles positions and is immediately followed
        // by the next lower residual row.
        alternate.forEachIndexed { index, stage ->
            check(stage.inputFeatures == featureCount)
            val nextPositions = stage.outputPositions(positions)
            val alternateOutput = stage.project(features, positions, gemm)
            val expected = stage.outputFeatures.toLong() * nextPositions.toLong()
            require(alternateOutput.size.toLong() == expected) {
                "Alternate[$index] returned ${alternateOutput.size} values; expected $expected."
            }

            val residualRow = S5MegpoidRuntimeBlockFactoryKeys.BLOCK_ROWS - 2 - index
            features = residualExecutor.runTransitionRow(
                transitionRow = residualRow,
                residualInput = alternateOutput,
                epsilon = epsilon
            )
            featureCount = residualExecutor.channels
            positions = nextPositions
        }

        return S5MegpoidSequenceTransitionOutput(features, featureCount, positions)
    }

    /** Smallest input length whose three stride-2 Temporal stages leave 7 positions. */
    fun minimumInputPositionsForResidualHistory(): Int {
        val requiredOutput = S5MegpoidResidualBlockShape.KERNEL_WIDTH
        fun outputAfterTemporal(inputPositions: Int): Int =
            temporal.fold(inputPositions) { positions, stage ->
                stage.outputPositions(positions)
            }

        var high = requiredOutput
        while (outputAfterTemporal(high) < requiredOutput) {
            require(high <= Int.MAX_VALUE / 2) {
                "Configured Temporal stages cannot produce a valid residual history window."
            }
            high *= 2
        }
        var low = 1
        while (low < high) {
            val middle = low + (high - low) / 2
            if (outputAfterTemporal(middle) >= requiredOutput) high = middle
            else low = middle + 1
        }
        return low
    }

    @Synchronized
    fun resetHistory() = residualExecutor.resetHistory()

    companion object {
        fun fromModelStages(
            denseStage: S5MegpoidDenseNormalizationStage,
            temporalStages: List<S5MegpoidTemporalStridedConvolution>,
            alternateStages: List<S5MegpoidAlternateTemporalProjection>,
            residualExecutor: S5MegpoidResidualBlockExecutor
        ): S5MegpoidAlternateSequenceTransitionPipeline =
            S5MegpoidAlternateSequenceTransitionPipeline(
                denseStage = denseStage,
                temporalStages = temporalStages,
                alternateStages = alternateStages,
                residualExecutor = residualExecutor
            )
    }
}
