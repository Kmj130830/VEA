package com.kmj.vea.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class S5SppCubicSegmentPlanTest {
    @Test
    fun `cubic segment plan samples exact endpoints and midpoint`() {
        val controls = floatArrayOf(2f, 4f, 8f, 16f)
        val plan = S5SppCubicSegmentPlan(controls)
        val seen = mutableListOf<Float>()
        val atStart = plan.sample(0f) { position, values ->
            seen += position
            assertEquals(controls.toList(), values.toList())
            values[0]
        }
        val atMiddle = plan.sample(0.5f) { position, values ->
            seen += position
            val squared = position * position
            val basis0 = ((position + position) - 3f) * squared + 1f
            val basis1 = (position - 2f) * squared + position
            basis1 * values[1] + basis0 * values[0] +
                (1f - basis0) * values[2] + (position - 1f) * squared * values[3]
        }
        val atEnd = plan.sample(1f) { position, values ->
            seen += position
            val squared = position * position
            val basis0 = ((position + position) - 3f) * squared + 1f
            val basis1 = (position - 2f) * squared + position
            basis1 * values[1] + basis0 * values[0] +
                (1f - basis0) * values[2] + (position - 1f) * squared * values[3]
        }
        assertEquals(2f, atStart, 0f)
        assertEquals(3.5f, atMiddle, 0f)
        assertEquals(8f, atEnd, 0f)
        assertEquals(listOf(0f, 0.5f, 1f), seen)
    }

    @Test
    fun `cubic segment plan copies controls and rejects invalid values`() {
        val controls = floatArrayOf(1f, 2f, 3f, 4f)
        val plan = S5SppCubicSegmentPlan(controls)
        controls[0] = 99f
        plan.sample(0.25f) { _, received ->
            assertEquals(1f, received[0], 0f)
            received[0]
        }
        assertThrows(IllegalArgumentException::class.java) {
            S5SppCubicSegmentPlan(floatArrayOf(1f, 2f, 3f))
        }
        assertThrows(IllegalArgumentException::class.java) {
            S5SppCubicSegmentPlan(floatArrayOf(1f, 2f, Float.NaN, 4f))
        }
        assertThrows(IllegalArgumentException::class.java) {
            plan.sample(-0.1f) { _, _ -> 0f }
        }
        assertThrows(IllegalArgumentException::class.java) {
            plan.sample(1.1f) { _, _ -> 0f }
        }
    }
}
