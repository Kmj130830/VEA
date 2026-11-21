package com.kmj.vea.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class PreparedMegpoidPhoneInputTest {
    @Test
    fun transposesAndRunsTheDenseStageForThePreparedPhoneMatrix() {
        val matrix = S5ApiDefaultPhoneFeatureMatrix(
            rowCount = 2,
            rowWidth = 2,
            values = floatArrayOf(1f, 0f, 0f, 1f)
        )
        val stage = S5MegpoidDenseNormalizationStage(
            projection = S5MegpoidDenseProjection(
                inputFeatures = 2,
                outputFeatures = 2,
                weights = floatArrayOf(1f, 0f, 0f, 1f)
            ),
            normalization = S5MegpoidGlobalFeatureNormalization(
                features = 2,
                scale = floatArrayOf(1f, 1f),
                bias = floatArrayOf(0f, 0f),
                epsilon = 0.000001f
            )
        )
        val prepared = PreparedMegpoidPhoneInput(
            matrix = matrix,
            denseDimensions = S5MegpoidDenseProjectionDimensions(2, 2),
            denseNormalizationStage = stage
        )
        val output = prepared.runDense(
            gemm = ReferenceS5MegpoidGemmKernel,
            statistics = ReferenceS5MegpoidStatisticsKernel
        )
        assertEquals(4, output.size)
    }
}
