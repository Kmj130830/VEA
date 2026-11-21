package com.kmj.vea.engine

interface S5MegpoidDenseFeatureOperator {
    val inputFeatures: Int
    val outputFeatures: Int
    fun forward(
        input: FloatArray,
        positions: Int,
        gemm: S5MegpoidGemmKernel,
        statistics: S5MegpoidStatisticsKernel
    ): FloatArray
}

interface S5MegpoidTemporalProjectionOperator {
    val inputFeatures: Int
    val outputFeatures: Int
    fun outputPositions(inputPositions: Int): Int
    fun project(input: FloatArray, inputPositions: Int, gemm: S5MegpoidGemmKernel): FloatArray
}

interface S5MegpoidBankedProjectionOperator {
    val inputFeatures: Int
    val outputFeatures: Int
    val banks: Int
    fun project(input: FloatArray, positions: Int): FloatArray
}

data class S5MegpoidSequenceTransitionOutput(
    val features: FloatArray,
    val channels: Int,
    val positions: Int
)

/**
 * Exact FUN_1800c7ad0 order for the supplied AI Megpoid V7 context:
 * param[5] = four feature slots; param[6] = two residual columns.
 *
 * Dense/norm -> Temporal[0] -> slot 1 -> Temporal[1] -> slot 2
 *             -> Temporal[2] -> slot 3 -> Residual row 3
 *             -> Banked[0] -> Residual row 2
 *             -> Banked[1] -> Residual row 1
 *             -> Banked[2] -> Residual row 0.
 *
 * Temporal and Banked descriptor arrays contain param[5]-1 (three)
 * entries. The residual factory grid contains param[5]*param[6] (4x2)
 * records. Residual row 3 therefore runs before the reverse Banked sweep.
 *
 * Banked output [feature][position][bank] is consumed as
 * [channel][position*banks], with bank as the contiguous inner axis.
 * Input is already-prepared features; note/phoneme generation and final
 * post-residual audio decoding are not part of this class.
 */
class S5MegpoidSequenceTransitionPipeline(
    private val denseStage: S5MegpoidDenseFeatureOperator,
    temporalStages: List<S5MegpoidTemporalProjectionOperator>,
    bankedStages: List<S5MegpoidBankedProjectionOperator>,
    private val residualExecutor: S5MegpoidResidualBlockExecutor
) {
    private val temporal = temporalStages.toList()
    private val banked = bankedStages.toList()

    init {
        val sequenceSlotCount = S5MegpoidRuntimeBlockFactoryKeys.BLOCK_ROWS
        val transitionCount = sequenceSlotCount - 1
        require(temporal.size == transitionCount && banked.size == transitionCount) {
            "AI Megpoid V7 requires four feature slots, three Temporal transitions and three Banked transitions."
        }

        var expectedTemporalInput = denseStage.outputFeatures
        temporal.forEachIndexed { index, stage ->
            require(stage.inputFeatures == expectedTemporalInput) {
                "Temporal[" + index + "] expects " + stage.inputFeatures +
                    " features; preceding stage produces " + expectedTemporalInput + "."
            }
            require(stage.outputFeatures > 0)
            expectedTemporalInput = stage.outputFeatures
        }

        require(temporal.last().outputFeatures == residualExecutor.channels) {
            "The last feature slot must contain " + residualExecutor.channels +
                " channels before Residual row 3."
        }

        banked.forEachIndexed { index, stage ->
            require(stage.inputFeatures == residualExecutor.channels) {
                "Banked[" + index + "] expects " + stage.inputFeatures +
                    " features; preceding Residual row produces " + residualExecutor.channels + "."
            }
            require(stage.outputFeatures == residualExecutor.channels) {
                "Banked[" + index + "] must produce " + residualExecutor.channels + " residual channels."
            }
            require(stage.banks > 0)
        }
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
        require(input.size.toLong() == denseStage.inputFeatures.toLong() * inputPositions.toLong()) {
            "Dense input must contain inputFeatures * inputPositions values."
        }
        require(epsilon.isFinite() && epsilon >= 0.0f)

        var features = denseStage.forward(input, inputPositions, gemm, statistics)
        var featureCount = denseStage.outputFeatures
        var positions = inputPositions
        require(features.size.toLong() == featureCount.toLong() * positions.toLong()) {
            "Dense/normalization stage returned an unexpected tensor shape."
        }

        // Dense is slot 0; the three Temporal projections materialize slots 1..3.
        temporal.forEachIndexed { index, stage ->
            check(stage.inputFeatures == featureCount) {
                "Temporal[" + index + "] input feature count changed after pipeline construction."
            }
            val nextPositions = stage.outputPositions(positions)
            val nextFeatures = stage.project(features, positions, gemm)
            require(nextFeatures.size.toLong() == stage.outputFeatures.toLong() * nextPositions.toLong()) {
                "Temporal[" + index + "] returned an unexpected tensor shape."
            }
            features = nextFeatures
            featureCount = stage.outputFeatures
            positions = nextPositions
        }

        // The native reverse loop begins with Residual row 3, before Banked[0].
        check(featureCount == residualExecutor.channels)
        features = residualExecutor.runTransitionRow(
            transitionRow = S5MegpoidRuntimeBlockFactoryKeys.BLOCK_ROWS - 1,
            residualInput = features,
            epsilon = epsilon
        )

        // Three reverse Banked transitions, each followed by the next Residual row.
        for (bankedIndex in banked.indices) {
            val stage = banked[bankedIndex]
            check(stage.inputFeatures == featureCount)
            val bankedOutput = stage.project(features, positions)
            val residualPositions = checkedProduct(positions, stage.banks)
            val expectedCount = stage.outputFeatures.toLong() * residualPositions.toLong()
            require(bankedOutput.size.toLong() == expectedCount) {
                "Banked[" + bankedIndex + "] returned " + bankedOutput.size +
                    " values; expected " + expectedCount + "."
            }

            val residualRow =
                S5MegpoidRuntimeBlockFactoryKeys.BLOCK_ROWS - 2 - bankedIndex
            features = residualExecutor.runTransitionRow(
                transitionRow = residualRow,
                residualInput = bankedOutput,
                epsilon = epsilon
            )
            featureCount = residualExecutor.channels
            positions = residualPositions
        }

        return S5MegpoidSequenceTransitionOutput(features, featureCount, positions)
    }

    @Synchronized
    fun resetHistory() = residualExecutor.resetHistory()

    private fun checkedProduct(a: Int, b: Int): Int {
        val product = a.toLong() * b.toLong()
        require(a > 0 && b > 0 && product <= Int.MAX_VALUE) {
            "Sequence transition tensor size is invalid or exceeds the Kotlin array limit."
        }
        return product.toInt()
    }

    companion object {
        fun fromModelStages(
            denseStage: S5MegpoidDenseNormalizationStage,
            temporalStages: List<S5MegpoidTemporalStridedConvolution>,
            bankedStages: List<S5MegpoidBankedLinearProjection>,
            residualExecutor: S5MegpoidResidualBlockExecutor
        ): S5MegpoidSequenceTransitionPipeline =
            S5MegpoidSequenceTransitionPipeline(
                denseStage = DenseAdapter(denseStage),
                temporalStages = temporalStages.map(::TemporalAdapter),
                bankedStages = bankedStages.map(::BankedAdapter),
                residualExecutor = residualExecutor
            )
    }
}

private class DenseAdapter(private val stage: S5MegpoidDenseNormalizationStage) :
    S5MegpoidDenseFeatureOperator {
    override val inputFeatures: Int get() = stage.projection.inputFeatures
    override val outputFeatures: Int get() = stage.normalization.features

    override fun forward(
        input: FloatArray,
        positions: Int,
        gemm: S5MegpoidGemmKernel,
        statistics: S5MegpoidStatisticsKernel
    ): FloatArray = stage.forward(input, positions, gemm, statistics)
}

private class TemporalAdapter(private val stage: S5MegpoidTemporalStridedConvolution) :
    S5MegpoidTemporalProjectionOperator {
    override val inputFeatures: Int get() = stage.inputFeatures
    override val outputFeatures: Int get() = stage.outputFeatures
    override fun outputPositions(inputPositions: Int): Int = stage.outputPositions(inputPositions)
    override fun project(
        input: FloatArray,
        inputPositions: Int,
        gemm: S5MegpoidGemmKernel
    ): FloatArray = stage.project(input, inputPositions, gemm)
}

private class BankedAdapter(private val stage: S5MegpoidBankedLinearProjection) :
    S5MegpoidBankedProjectionOperator {
    override val inputFeatures: Int get() = stage.inputFeatures
    override val outputFeatures: Int get() = stage.outputFeatures
    override val banks: Int get() = stage.banks
    override fun project(input: FloatArray, positions: Int): FloatArray = stage.project(input, positions)
}
