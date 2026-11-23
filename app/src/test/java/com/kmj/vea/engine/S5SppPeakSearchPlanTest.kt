package com.kmj.vea.engine

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class S5SppPeakSearchPlanTest {
    @Test
    fun `plan separates stride one lazy and stride two normal peak inputs`() {
        val config = floatArrayOf(10f, 2f, 4f, 6f, 3f)
        val strides = mutableListOf<Int>()
        val plan = S5SppPeakSearchPlan(
            weightConfig = config,
            binSpacing = 0.5f,
            denominator = 32000f,
            evaluateWeight = { _, coordinate -> coordinate * -2f },
            findPeak = { values, stride, start, end, spacing, denominator, weights ->
                strides += stride
                assertEquals(32000f, denominator, 0f)
                assertEquals(0.5f, spacing, 0f)
                assertArrayEquals(floatArrayOf(10f, 2f, 4f, 6f, 3f), weights, 0f)
                assertEquals(0f, values[start * stride + 1].takeIf { stride == 2 } ?: 0f, 0f)
                end
            }
        )
        config[0] = -1f
        val snapshot = plan.snapshotWeightConfig()
        assertEquals(10f, snapshot[0], 0f)
        val snapshotCopy = plan.snapshotWeightConfig()
        snapshotCopy[0] = 100f
        assertEquals(10f, plan.snapshotWeightConfig()[0], 0f)

        assertEquals(2, plan.findMagnitudePeak(floatArrayOf(1f, 2f, 3f), 0, 2))
        assertEquals(1, plan.findInterleavedPeak(floatArrayOf(1f, 0f, 2f, 0f), 0, 1))
        assertEquals(listOf(1, 2), strides)
        assertEquals(-1.0f, plan.weightAt(0.5f), 0f)
    }

    @Test
    fun `plan rejects malformed configuration and ranges`() {
        assertThrows(IllegalArgumentException::class.java) {
            S5SppPeakSearchPlan(floatArrayOf(1f, 2f), 1f, 10f)
        }
        assertThrows(IllegalArgumentException::class.java) {
            S5SppPeakSearchPlan(floatArrayOf(0f, 2f, 4f, 6f, 3f), 1f, 10f)
        }
        assertThrows(IllegalArgumentException::class.java) {
            S5SppPeakSearchPlan(floatArrayOf(10f, 2f, 4f, 6f, 3f), 1f, 0f)
        }
        val plan = S5SppPeakSearchPlan(
            floatArrayOf(10f, 2f, 4f, 6f, 3f), 1f, 10f,
            findPeak = { _, _, _, _, _, _, _ -> 0 }
        )
        assertThrows(IllegalArgumentException::class.java) {
            plan.findMagnitudePeak(floatArrayOf(1f), 1, 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            plan.findInterleavedPeak(floatArrayOf(1f, 2f, 3f), 0, 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            plan.weightAt(Float.NaN)
        }
    }
}
