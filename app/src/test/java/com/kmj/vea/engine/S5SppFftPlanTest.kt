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
    fun `64 and 128 point plans dispatch the mixed radix stage`() {
        for (size in listOf(64, 128)) {
            val plan = S5SppFftPlanBuilder.build(size) { requested, split, coefficients ->
                assertEquals(size, requested)
                split[0] = size / 4
                split[1] = size / 4
                for (index in coefficients.indices) coefficients[index] = index * 0.001f
                true
            }
            val input = FloatArray(size) { index -> (index % 13 - 6) * 0.125f }
            var called = false
            val result = plan.transformRadixStage(input) { stageSize, values, coefficients ->
                called = true
                assertEquals(size, stageSize)
                assertArrayEquals(input, values, 0f)
                assertArrayEquals(plan.coefficients, coefficients, 0f)
                FloatArray(size) { it * 0.25f }
            }
            assertEquals(true, called)
            assertArrayEquals(FloatArray(size) { it * 0.25f }, result, 0f)
        }
    }
    @Test
    fun `256 and 512 point plans dispatch the large radix stage modes`() {
        for (size in listOf(256, 512)) {
            val plan = S5SppFftPlanBuilder.build(size) { requested, split, coefficients ->
                assertEquals(size, requested)
                split[0] = size / 4
                split[1] = size / 4
                for (index in coefficients.indices) coefficients[index] = index * 0.0005f
                true
            }
            val input = FloatArray(size) { index -> (index % 29 - 14) * 0.03125f }
            for (mode in 0..1) {
                var called = false
                val output = plan.transformLargeRadixStage(mode, input) { requestedSize, requestedMode, values, coefficients ->
                    called = true
                    assertEquals(size, requestedSize)
                    assertEquals(mode, requestedMode)
                    assertArrayEquals(input, values, 0f)
                    assertArrayEquals(plan.coefficients, coefficients, 0f)
                    assertEquals(size / 2, coefficients.size)
                    FloatArray(size) { it * (mode + 1).toFloat() }
                }
                assertEquals(true, called)
                assertArrayEquals(FloatArray(size) { it * (mode + 1).toFloat() }, output, 0f)
            }
        }
    }
    @Test
    fun `real FFT plan dispatches the packed forward and inverse modes`() {
        for (size in listOf(4, 8, 16, 32, 64, 128, 256, 512)) {
            val plan = S5SppFftPlanBuilder.build(size) { requested, split, coefficients ->
                assertEquals(size, requested)
                split[0] = size / 4
                split[1] = size / 4
                true
            }
            val input = FloatArray(size) { (it % 17 - 8) * 0.0625f }
            for (direction in listOf(-1, 1)) {
                var called = false
                val output = plan.transformReal(direction, input) { requestedSize, requestedDirection, frame ->
                    called = true
                    assertEquals(size, requestedSize)
                    assertEquals(direction, requestedDirection)
                    assertArrayEquals(input, frame, 0f)
                    FloatArray(size) { (it + 1) * direction.toFloat() }
                }
                assertEquals(true, called)
                assertArrayEquals(
                    FloatArray(size) { (it + 1) * direction.toFloat() },
                    output,
                    0f
                )
            }
        }
    }
    @Test
    fun `FFT plan exposes recovered inverse frame normalization and shift sequence`() {
        val plan = S5SppFftPlanBuilder.build(8) { size, split, coefficients ->
            split[0] = size / 4
            split[1] = size / 4
            true
        }
        val packed = FloatArray(8) { it.toFloat() }
        val calls = mutableListOf<String>()
        val output = plan.postProcessInverseFrame(
            offset = 2,
            packedSpectrum = packed,
            inverseTransform = { size, direction, values ->
                calls += "fft:$size:$direction"
                FloatArray(size) { (it + 1).toFloat() }
            },
            normalize = { values, scale ->
                calls += "scale:$scale"
                FloatArray(values.size) { values[it] * scale }
            },
            rotate = { values, shift ->
                calls += "rotate:$shift"
                values
            }
        )
        assertEquals(listOf("fft:8:1", "scale:0.125", "rotate:-2"), calls)
        assertEquals(8, output.size)
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
