package com.kmj.vea.engine

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class S5SppFftPlanTest {
    @Test
    fun `builder allocates recovered split and coefficient table lengths`() {
        val plan = S5SppFftPlanBuilder.build(256) { size, split, coefficients ->
            assertEquals(256, size)
            assertEquals(13, split.size)
            assertEquals(128, coefficients.size)
            split[0] = size / 4
            split[1] = size / 4
            split[2] = 0
            split[3] = 16
            split[4] = 0
            split[5] = 64
            split[6] = 32
            split[7] = 96
            coefficients[0] = 1.0f
            coefficients[1] = 0.70710678f
            coefficients[64] = 0.70710678f
            true
        }

        assertEquals(256, plan.fftSize)
        assertEquals(13, plan.splitTable.size)
        assertEquals(128, plan.coefficients.size)
        assertArrayEquals(
            intArrayOf(64, 64, 0, 16, 0, 64, 32, 96),
            plan.splitTable.copyOfRange(0, 8)
        )
        assertEquals(0.70710678f, plan.coefficients[1], 1.0e-6f)
    }

    @Test
    fun `plan exposes copies rather than mutable backing arrays`() {
        val plan = S5SppFftPlanBuilder.build(8) { size, split, coefficients ->
            split[0] = size / 4
            split[1] = size / 4
            coefficients[2] = 0.70710678f
            coefficients[3] = 0.35355339f
            true
        }
        val splitCopy = plan.splitTable
        val coefficientCopy = plan.coefficients
        splitCopy[0] = 999
        coefficientCopy[2] = 999f
        assertEquals(2, plan.splitTable[0])
        assertEquals(0.70710678f, plan.coefficients[2], 1.0e-6f)
    }

    @Test
    fun `small FFT plan dispatches its own direction and interleaved data`() {
        val plan = S5SppFftPlanBuilder.build(8) { size, split, coefficients ->
            split[0] = size / 4
            split[1] = size / 4
            true
        }
        val input = floatArrayOf(1f, 10f, 2f, 20f, 3f, 30f, 4f, 40f)
        var receivedDirection = 0
        val output = plan.transformSmallLeaf(-1, input) { size, direction, values ->
            assertEquals(8, size)
            receivedDirection = direction
            assertArrayEquals(input, values, 0f)
            floatArrayOf(10f, 100f, -22f, -18f, -2f, -20f, 18f, -22f)
        }
        assertEquals(-1, receivedDirection)
        assertArrayEquals(
            floatArrayOf(10f, 100f, -22f, -18f, -2f, -20f, 18f, -22f),
            output, 0f
        )
    }

    @Test
    fun `16 and 32 point plans dispatch their own twiddle tables`() {
        for (size in listOf(16, 32)) {
            val plan = S5SppFftPlanBuilder.build(size) { requested, split, coefficients ->
                assertEquals(size, requested)
                split[0] = size / 4
                split[1] = size / 4
                coefficients[0] = 1.0f
                coefficients[1] = 0.70710678f
                if (size == 32) {
                    coefficients[2] = 0.9238795f
                    coefficients[3] = 0.38268343f
                }
                true
            }
            val input = FloatArray(size) { (it - size / 2) * 0.125f }
            var called = false
            val result = plan.transformLeaf(-1, input) { receivedSize, direction, values, coefficients ->
                called = true
                assertEquals(size, receivedSize)
                assertEquals(-1, direction)
                assertArrayEquals(input, values, 0f)
                assertArrayEquals(plan.coefficients, coefficients, 0f)
                assertEquals(if (size == 16) 8 else 16, coefficients.size)
                FloatArray(size) { it.toFloat() }
            }
            assertEquals(true, called)
            assertArrayEquals(FloatArray(size) { it.toFloat() }, result, 0f)
        }
    }
    @Test
    fun `larger FFT plan refuses small leaf shortcut`() {
        val plan = S5SppFftPlanBuilder.build(16) { size, split, coefficients ->
            split[0] = size / 4
            split[1] = size / 4
            true
        }
        assertThrows(IllegalArgumentException::class.java) {
            plan.transformSmallLeaf(1, FloatArray(16)) { _, _, _ -> FloatArray(16) }
        }
    }

    @Test
    fun `builder rejects invalid fft sizes and a failed native backend`() {
        assertThrows(IllegalArgumentException::class.java) {
            S5SppFftPlanBuilder.build(12) { _, _, _ -> true }
        }
        assertThrows(IllegalStateException::class.java) {
            S5SppFftPlanBuilder.build(16) { _, _, _ -> false }
        }
    }

    @Test
    fun `record rejects malformed table dimensions and non finite coefficients`() {
        assertThrows(IllegalArgumentException::class.java) {
            S5SppFftPlan(16, IntArray(3), FloatArray(8))
        }
        assertThrows(IllegalArgumentException::class.java) {
            S5SppFftPlan(16, intArrayOf(4, 4, 0, 0), FloatArray(8) {
                if (it == 0) Float.NaN else 0f
            })
        }
    }
}
