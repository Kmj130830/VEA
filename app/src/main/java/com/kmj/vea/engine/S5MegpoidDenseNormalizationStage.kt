package com.kmj.vea.engine

/**
 * The adjacent operator pair recovered in FUN_1800c7ad0:
 *
 *   FUN_1800c5260 dense projection -> FUN_1800c7920 global feature normalization
 *
 * Weight descriptors remain explicit constructor inputs. This stage does not
 * infer their source from the nine VTMG named layers.
 */
class S5MegpoidDenseNormalizationStage(
    val projection: S5MegpoidDenseProjection,
    val normalization: S5MegpoidGlobalFeatureNormalization
) {
    init {
        require(projection.outputFeatures == normalization.features) {
            "Dense projection output features must match normalization features."
        }
    }

    fun forward(
        input: FloatArray,
        positions: Int,
        gemm: S5MegpoidGemmKernel = NativeS5MegpoidGemmKernel,
        statistics: S5MegpoidStatisticsKernel = NativeS5MegpoidStatisticsKernel
    ): FloatArray {
        val projected = projection.project(
            input = input,
            positions = positions,
            gemm = gemm
        )
        return normalization.apply(
            input = projected,
            positions = positions,
            statistics = statistics
        )
    }
}
