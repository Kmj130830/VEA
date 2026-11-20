package com.kmj.vea.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class S5MegpoidResidualBlockShapeTest {
    @Test
    fun computesTheRecoveredModelBuilderTensorLengths() {
        val shape = S5MegpoidResidualBlockShape(
            channels = 2,
            expansionMultiplier = 3,
            positions = 7
        )

        assertEquals(6, shape.expandedFeatures)
        assertEquals(98, shape.historyElementCount)
        assertEquals(98, shape.depthwiseWeightElementCount)
        assertEquals(12, shape.expansionWeightElementCount)
        assertEquals(6, shape.expandedAffineElementCount)
        assertEquals(12, shape.projectionWeightElementCount)
        assertEquals(14, shape.residualElementCount)
        assertEquals(2, shape.residualVectorElementCount)
        assertEquals(1.0e-6f, S5MegpoidResidualBlockShape.DEFAULT_EPSILON)
    }

    @Test
    fun acceptsExactResidualKernelInputs() {
        val shape = S5MegpoidResidualBlockShape(
            channels = 2,
            expansionMultiplier = 3,
            positions = 7
        )
        shape.validateInputs(
            historyRows = FloatArray(shape.historyElementCount),
            depthwiseWeights = FloatArray(shape.depthwiseWeightElementCount),
            expandWeights = FloatArray(shape.expansionWeightElementCount),
            expandedBiasA = FloatArray(shape.expandedAffineElementCount),
            expandedBiasB = FloatArray(shape.expandedAffineElementCount),
            projectWeights = FloatArray(shape.projectionWeightElementCount),
            residualInput = FloatArray(shape.residualElementCount),
            residualScale = FloatArray(shape.residualVectorElementCount),
            residualBias = null,
            epsilon = S5MegpoidResidualBlockShape.DEFAULT_EPSILON
        )
    }

    @Test
    fun rejectsAffineArraysThatDoNotMatchExpandedFeatureCount() {
        val shape = S5MegpoidResidualBlockShape(
            channels = 2,
            expansionMultiplier = 3,
            positions = 7
        )

        assertThrows(IllegalArgumentException::class.java) {
            shape.validateInputs(
                historyRows = FloatArray(shape.historyElementCount),
                depthwiseWeights = FloatArray(shape.depthwiseWeightElementCount),
                expandWeights = FloatArray(shape.expansionWeightElementCount),
                expandedBiasA = FloatArray(shape.expandedAffineElementCount - 1),
                expandedBiasB = FloatArray(shape.expandedAffineElementCount),
                projectWeights = FloatArray(shape.projectionWeightElementCount),
                residualInput = FloatArray(shape.residualElementCount),
                residualScale = FloatArray(shape.residualVectorElementCount),
                residualBias = null,
                epsilon = 1.0e-6f
            )
        }
    }

    @Test
    fun rejectsShortHistoryAndInvalidEpsilon() {
        val shape = S5MegpoidResidualBlockShape(
            channels = 2,
            expansionMultiplier = 3,
            positions = 7
        )
        val validHistory = FloatArray(shape.historyElementCount)

        assertThrows(IllegalArgumentException::class.java) {
            shape.validateInputs(
                historyRows = validHistory.copyOf(validHistory.size - 1),
                depthwiseWeights = FloatArray(shape.depthwiseWeightElementCount),
                expandWeights = FloatArray(shape.expansionWeightElementCount),
                expandedBiasA = FloatArray(shape.expandedAffineElementCount),
                expandedBiasB = FloatArray(shape.expandedAffineElementCount),
                projectWeights = FloatArray(shape.projectionWeightElementCount),
                residualInput = FloatArray(shape.residualElementCount),
                residualScale = FloatArray(shape.residualVectorElementCount),
                residualBias = null,
                epsilon = 1.0e-6f
            )
        }

        assertThrows(IllegalArgumentException::class.java) {
            shape.validateInputs(
                historyRows = validHistory,
                depthwiseWeights = FloatArray(shape.depthwiseWeightElementCount),
                expandWeights = FloatArray(shape.expansionWeightElementCount),
                expandedBiasA = FloatArray(shape.expandedAffineElementCount),
                expandedBiasB = FloatArray(shape.expandedAffineElementCount),
                projectWeights = FloatArray(shape.projectionWeightElementCount),
                residualInput = FloatArray(shape.residualElementCount),
                residualScale = FloatArray(shape.residualVectorElementCount),
                residualBias = null,
                epsilon = Float.NaN
            )
        }
    }

    @Test
    fun rejectsDimensionAndArraySizeOverflow() {
        assertThrows(IllegalArgumentException::class.java) {
            S5MegpoidResidualBlockShape(channels = 2, expansionMultiplier = 3, positions = 6)
        }

        val huge = S5MegpoidResidualBlockShape(
            channels = Int.MAX_VALUE,
            expansionMultiplier = 2,
            positions = 7
        )
        assertThrows(IllegalArgumentException::class.java) {
            huge.expansionWeightElementCount
        }
    }
}
