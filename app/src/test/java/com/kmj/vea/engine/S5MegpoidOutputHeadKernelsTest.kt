package com.kmj.vea.engine

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class S5MegpoidOutputHeadKernelsTest {
    @Test
    fun accumulatesComplexModulationIntoExistingInterleavedOutput() {
        val output = floatArrayOf(1f, -1f)
        val returned = S5MegpoidOutputHeadKernels.accumulateComplexModulatedOutput(
            outputInterleaved = output,
            firstFeaturePlane = floatArrayOf(3f),
            secondFeaturePlane = floatArrayOf(4f),
            gain = floatArrayOf(2f),
            modulationReal = floatArrayOf(0.5f),
            modulationImag = floatArrayOf(2f)
        )

        assertSame(output, returned)
        assertArrayEquals(floatArrayOf(-12f, 15f), output, 0.00001f)
    }

    @Test
    fun accumulatesIndependentComplexPositionsInInterleavedOrder() {
        val output = FloatArray(4)
        S5MegpoidOutputHeadKernels.accumulateComplexModulatedOutput(
            outputInterleaved = output,
            firstFeaturePlane = floatArrayOf(1f, 2f),
            secondFeaturePlane = floatArrayOf(0f, 0f),
            gain = floatArrayOf(1f, 2f),
            modulationReal = floatArrayOf(1f, 0f),
            modulationImag = floatArrayOf(0f, 1f)
        )

        assertArrayEquals(floatArrayOf(1f, 0f, 0f, 4f), output, 0.00001f)
    }

    @Test
    fun affineKernelAppliesScaleAndBiasInPlace() {
        val values = floatArrayOf(-2f, 0f, 1f, 3f)
        val returned = S5MegpoidOutputHeadKernels.applyAffineInPlace(
            values = values,
            scale = 0.5f,
            bias = 2f
        )

        assertSame(values, returned)
        assertArrayEquals(floatArrayOf(1f, 2f, 2.5f, 3.5f), values, 0.00001f)
    }

    @Test
    fun affineKernelHandlesEmptyInputWithoutAllocatingReplacement() {
        val values = FloatArray(0)
        val returned = S5MegpoidOutputHeadKernels.applyAffineInPlace(
            values = values,
            scale = -2f,
            bias = 4f
        )
        assertSame(values, returned)
        assertEquals(0, returned.size)
    }

    @Test
    fun complexModulationRejectsShapeMismatchAndNonFiniteInput() {
        assertThrows(IllegalArgumentException::class.java) {
            S5MegpoidOutputHeadKernels.accumulateComplexModulatedOutput(
                outputInterleaved = FloatArray(1),
                firstFeaturePlane = floatArrayOf(1f),
                secondFeaturePlane = floatArrayOf(1f),
                gain = floatArrayOf(1f),
                modulationReal = floatArrayOf(1f),
                modulationImag = floatArrayOf(1f)
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            S5MegpoidOutputHeadKernels.accumulateComplexModulatedOutput(
                outputInterleaved = FloatArray(2),
                firstFeaturePlane = floatArrayOf(Float.NaN),
                secondFeaturePlane = floatArrayOf(1f),
                gain = floatArrayOf(1f),
                modulationReal = floatArrayOf(1f),
                modulationImag = floatArrayOf(1f)
            )
        }
    }

    @Test
    fun affineKernelRejectsNonFiniteArgumentsAndValues() {
        assertThrows(IllegalArgumentException::class.java) {
            S5MegpoidOutputHeadKernels.applyAffineInPlace(
                floatArrayOf(1f), Float.NaN, 0f
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            S5MegpoidOutputHeadKernels.applyAffineInPlace(
                floatArrayOf(1f), 1f, Float.POSITIVE_INFINITY
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            S5MegpoidOutputHeadKernels.applyAffineInPlace(
                floatArrayOf(Float.NEGATIVE_INFINITY), 1f, 0f
            )
        }
    }

    @Test
    fun complexModulationRejectsNonFiniteAccumulatedOutput() {
        val output = floatArrayOf(Float.MAX_VALUE, 0f)
        assertThrows(IllegalArgumentException::class.java) {
            S5MegpoidOutputHeadKernels.accumulateComplexModulatedOutput(
                outputInterleaved = output,
                firstFeaturePlane = floatArrayOf(Float.MAX_VALUE),
                secondFeaturePlane = floatArrayOf(0f),
                gain = floatArrayOf(2f),
                modulationReal = floatArrayOf(1f),
                modulationImag = floatArrayOf(0f)
            )
        }
    }
}
