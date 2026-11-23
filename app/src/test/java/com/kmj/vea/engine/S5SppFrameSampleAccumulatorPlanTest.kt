package com.kmj.vea.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class S5SppFrameSampleAccumulatorPlanTest {
    private val referenceAccumulator:
        (FloatArray, FloatArray, Int, Int, Int, DoubleArray, Int, Double, Double) -> FloatArray =
        { source, partial, baseIndex, offset, windowSpan, table, segments, scale, fallback ->
            require(windowSpan > 0)
            val frameStart = Math.subtractExact(baseIndex, offset)
            val half = windowSpan ushr 1
            FloatArray(source.size) { sample ->
                val frameIndex = Math.addExact(frameStart, sample)
                require(frameIndex in partial.indices)
                val x = Math.subtractExact(frameIndex, half).toFloat() / windowSpan.toFloat()
                val scaled = kotlin.math.abs(x.toDouble()) * scale
                val coefficient = if (segments == 0 || scaled >= segments.toDouble()) {
                    fallback
                } else {
                    val index = kotlin.math.floor(scaled).toInt()
                    val fraction = scaled - index
                    table[index] + (table[index + 1] - table[index]) * fraction
                }
                require(coefficient.isFinite() && coefficient != 0.0)
                (source[sample] / coefficient.toFloat()) * partial[frameIndex]
            }
        }

    @Test
    fun `sample accumulator applies recovered index window and amplitude equation`() {
        val window = S5SppWindowLookupPlan(
            table = doubleArrayOf(1.0, 2.0, 3.0, 4.0, 5.0),
            segmentCount = 4,
            scale = 1.0,
            fallbackValue = -1.0
        )
        val plan = S5SppFrameSampleAccumulatorPlan(
            baseIndex = 2,
            offset = 0,
            windowSpan = 4,
            sampleCount = 3
        )
        val output = plan.accumulate(
            sourceFrame = floatArrayOf(10f, 20f, 30f),
            partialWindowBuffer = floatArrayOf(0f, 1f, 4f, 5f, 6f, 7f),
            window = window,
            execute = referenceAccumulator
        )
        assertEquals(listOf(40f, 80f, 120f), output.toList())
    }

    @Test
    fun `sample accumulator honors base index offset and window center`() {
        val window = S5SppWindowLookupPlan(
            doubleArrayOf(1.0, 1.0, 1.0, 1.0, 1.0), 4, 1.0, 1.0
        )
        val plan = S5SppFrameSampleAccumulatorPlan(
            baseIndex = 5,
            offset = 2,
            windowSpan = 4,
            sampleCount = 2
        )
        val partial = FloatArray(8) { it.toFloat() }
        val output = plan.accumulate(floatArrayOf(2f, 3f), partial, window, referenceAccumulator)
        assertEquals(listOf(6f, 12f), output.toList())
    }

    @Test
    fun `sample accumulator rejects missing buffer span zero window and invalid samples`() {
        val window = S5SppWindowLookupPlan(
            doubleArrayOf(0.0, 0.0, 0.0), 2, 1.0, 0.0
        )
        assertThrows(IllegalArgumentException::class.java) {
            S5SppFrameSampleAccumulatorPlan(0, 0, 0, 1)
        }
        val outOfBounds = S5SppFrameSampleAccumulatorPlan(4, 0, 2, 2)
        assertThrows(IllegalArgumentException::class.java) {
            outOfBounds.accumulate(floatArrayOf(1f, 1f), FloatArray(5), window, referenceAccumulator)
        }
        val zeroCoefficient = S5SppFrameSampleAccumulatorPlan(0, 0, 2, 1)
        assertThrows(IllegalArgumentException::class.java) {
            zeroCoefficient.accumulate(floatArrayOf(1f), FloatArray(4), window, referenceAccumulator)
        }
        assertThrows(IllegalArgumentException::class.java) {
            zeroCoefficient.accumulate(floatArrayOf(Float.NaN), FloatArray(4), window, referenceAccumulator)
        }
    }
}
