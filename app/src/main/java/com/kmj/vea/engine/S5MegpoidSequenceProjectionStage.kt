package com.kmj.vea.engine

/**
 * The locally composed pre-residual sequence path recovered in FUN_1800c7ad0:
 *
 * dense projection -> global feature normalization -> stride-2 temporal projection.
 *
 * Descriptors/weights are explicit inputs and are not inferred from the nine
 * named VTMG layers. This class composes already-verified operator contracts;
 * it does not implement the dispatcher’s full sequence gather or later
 * banked-linear/residual scheduling.
 */
class S5MegpoidSequenceProjectionStage(
    val denseNormalization: S5MegpoidDenseNormalizationStage,
    val temporalProjection: S5MegpoidTemporalStridedConvolution
) {
    init {
        require(
            denseNormalization.normalization.features ==
                temporalProjection.inputFeatures
        ) {
            "Normalized dense features must match temporal projection input features."
        }
    }

    /**
     * Input: [denseInputFeature][sequencePosition].
     * Output: [temporalOutputFeature][downsampledPosition].
     */
    fun forward(
        input: FloatArray,
        inputPositions: Int,
        gemm: S5MegpoidGemmKernel = NativeS5MegpoidGemmKernel,
        statistics: S5MegpoidStatisticsKernel = NativeS5MegpoidStatisticsKernel
    ): FloatArray {
        val normalized = denseNormalization.forward(
            input = input,
            positions = inputPositions,
            gemm = gemm,
            statistics = statistics
        )
        return temporalProjection.project(
            input = normalized,
            inputPositions = inputPositions,
            gemm = gemm
        )
    }

    fun outputPositions(inputPositions: Int): Int =
        temporalProjection.outputPositions(inputPositions)
}
