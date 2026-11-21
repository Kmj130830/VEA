package com.kmj.vea.engine

interface S5MegpoidDenseFeatureOperator {
    val inputFeatures: Int
    val outputFeatures: Int
    fun forward(input: FloatArray, positions: Int, gemm: S5MegpoidGemmKernel, statistics: S5MegpoidStatisticsKernel): FloatArray
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
    val features: FloatArray, val channels: Int, val positions: Int
)

/**
 * Recovered FUN_1800c7ad0 operator path:
 * Dense/norm -> Temporal[0] -> [1] -> [2] -> [3] -> sequence slot 4.
 * Then interleaved reverse feedback:
 * Banked[0] -> Residual row 3 -> Banked[1] -> Residual row 2
 *            -> Banked[2] -> Residual row 1 -> Banked[3] -> Residual row 0.
 *
 * Each residual result is the input to the next banked operator. Banked
 * outputs [feature][position][bank] are contiguous and feed residual blocks
 * as [channel][position*banks], with bank as the innermost position axis.
 *
 * Input is already-prepared features; note/phoneme front-end and the final
 * post-residual decoder are not part of this class.
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
        val transitionCount = S5MegpoidRuntimeBlockFactoryKeys.BLOCK_ROWS
        require(temporal.size == transitionCount && banked.size == transitionCount) {
            "AI Megpoid V7 requires four temporal and four banked transition operators."
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
        banked.forEachIndexed { index, stage ->
            val expectedInput = if (index == 0) temporal.last().outputFeatures
                else residualExecutor.channels
            require(stage.inputFeatures == expectedInput) {
                "Banked[" + index + "] expects " + stage.inputFeatures +
                    " features; native input is " + expectedInput + "."
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

        temporal.forEachIndexed { index, stage ->
            check(stage.inputFeatures == featureCount)
            val nextPositions = stage.outputPositions(positions)
            val nextFeatures = stage.project(features, positions, gemm)
            require(nextFeatures.size.toLong() == stage.outputFeatures.toLong() * nextPositions.toLong()) {
                "Temporal[" + index + "] returned an unexpected tensor shape."
            }
            features = nextFeatures
            featureCount = stage.outputFeatures
            positions = nextPositions
        }

        for (sequenceIndex in banked.indices) {
            val stage = banked[sequenceIndex]
            check(stage.inputFeatures == featureCount)
            val bankedOutput = stage.project(features, positions)
            val residualPositions = checkedProduct(positions, stage.banks)
            val expected = stage.outputFeatures.toLong() * residualPositions.toLong()
            require(bankedOutput.size.toLong() == expected) {
                "Banked[" + sequenceIndex + "] returned " + bankedOutput.size +
                    " values; expected " + expected + "."
            }

            val residualRow = S5MegpoidRuntimeBlockFactoryKeys.BLOCK_ROWS - 1 - sequenceIndex
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

private class DenseAdapter(private val stage: S5MegpoidDenseNormalizationStage) : S5MegpoidDenseFeatureOperator {
    override val inputFeatures: Int get() = stage.projection.inputFeatures
    override val outputFeatures: Int get() = stage.normalization.features
    override fun forward(
        input: FloatArray, positions: Int, gemm: S5MegpoidGemmKernel,
        statistics: S5MegpoidStatisticsKernel
    ): FloatArray = stage.forward(input, positions, gemm, statistics)
}
private class TemporalAdapter(private val stage: S5MegpoidTemporalStridedConvolution) : S5MegpoidTemporalProjectionOperator {
    override val inputFeatures: Int get() = stage.inputFeatures
    override val outputFeatures: Int get() = stage.outputFeatures
    override fun outputPositions(inputPositions: Int): Int = stage.outputPositions(inputPositions)
    override fun project(input: FloatArray, inputPositions: Int, gemm: S5MegpoidGemmKernel): FloatArray =
        stage.project(input, inputPositions, gemm)
}
private class BankedAdapter(private val stage: S5MegpoidBankedLinearProjection) : S5MegpoidBankedProjectionOperator {
    override val inputFeatures: Int get() = stage.inputFeatures
    override val outputFeatures: Int get() = stage.outputFeatures
    override val banks: Int get() = stage.banks
    override fun project(input: FloatArray, positions: Int): FloatArray = stage.project(input, positions)
}
