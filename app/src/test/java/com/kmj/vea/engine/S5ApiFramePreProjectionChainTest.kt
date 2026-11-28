package com.kmj.vea.engine

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import kotlin.math.exp

class S5ApiFramePreProjectionChainTest {
    private fun dense(input: Int, output: Int, weights: FloatArray) =
        S5ApiFrameDenseVectorOperator(
            inputFeatures = input,
            outputFeatures = output,
            weights = weights,
            gemm = ReferenceS5MegpoidGemmKernel
        )

    private fun nativeTanh(value: Float): Float =
        2f / (exp(-2f * value) + 1f) - 1f

    private fun chain() = S5ApiFramePreProjectionChain(
        first = S5ApiFrameResidualProjection(
            operator = dense(2, 2, floatArrayOf(1f, 0f, 0f, 1f)),
            residual = floatArrayOf(0.5f, -0.5f)
        ),
        second = S5ApiFrameResidualProjection(
            operator = dense(2, 2, floatArrayOf(1f, 2f, -1f, 0.5f)),
            residual = floatArrayOf(0.2f, -0.1f)
        )
    )

    @Test
    fun appliesTwoResidualsAndTanhAfterEachProjection() {
        val input = floatArrayOf(0.25f, -0.75f)
        val first = floatArrayOf(
            nativeTanh(input[0] + 0.5f),
            nativeTanh(input[1] - 0.5f)
        )
        val second = floatArrayOf(
            nativeTanh(first[0] + 2f * first[1] + 0.2f),
            nativeTanh(-first[0] + 0.5f * first[1] - 0.1f)
        )

        assertArrayEquals(second, chain().projectBaseFrame(input), 1.0e-6f)
    }

    @Test
    fun appendsScaledTailAndOptionalUnscaledScaleSentinel() {
        val input = floatArrayOf(0.25f, -0.75f)
        val chain = chain()
        val base = chain.projectBaseFrame(input)

        val withSentinel = chain.buildFrameInput(
            input = input,
            frameTail = floatArrayOf(1f, -2f, 0.5f),
            tailScale = 0.25f,
            appendScaleSentinel = true
        )
        assertEquals(base.size + 4, withSentinel.size)
        assertArrayEquals(base, withSentinel.copyOfRange(0, base.size), 0f)
        assertArrayEquals(
            floatArrayOf(0.25f, -0.5f, 0.125f, 0.25f),
            withSentinel.copyOfRange(base.size, withSentinel.size),
            0f
        )

        val withoutSentinel = chain.buildFrameInput(
            input = input,
            frameTail = floatArrayOf(1f, -2f),
            tailScale = 0.5f,
            appendScaleSentinel = false
        )
        assertArrayEquals(
            floatArrayOf(0.5f, -1f),
            withoutSentinel.copyOfRange(base.size, withoutSentinel.size),
            0f
        )
    }

    @Test
    fun rejectsInvalidProjectionWidthsAndFrameInputs() {
        assertThrows(IllegalArgumentException::class.java) {
            S5ApiFramePreProjectionChain(
                first = S5ApiFrameResidualProjection(
                    dense(2, 3, FloatArray(6) { 1f }),
                    FloatArray(3)
                ),
                second = S5ApiFrameResidualProjection(
                    dense(2, 2, FloatArray(4) { 1f }),
                    FloatArray(2)
                )
            )
        }

        val chain = chain()
        assertThrows(IllegalArgumentException::class.java) {
            chain.projectBaseFrame(floatArrayOf(1f))
        }
        assertThrows(IllegalArgumentException::class.java) {
            chain.buildFrameInput(
                input = floatArrayOf(1f, 2f),
                frameTail = FloatArray(2),
                tailScale = Float.NaN,
                appendScaleSentinel = false
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            chain.buildFrameInput(
                input = floatArrayOf(1f, 2f),
                frameTail = floatArrayOf(0f, Float.POSITIVE_INFINITY),
                tailScale = 1f,
                appendScaleSentinel = false
            )
        }
    }
}
