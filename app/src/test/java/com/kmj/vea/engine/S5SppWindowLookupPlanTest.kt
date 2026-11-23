package com.kmj.vea.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class S5SppWindowLookupPlanTest {
    @Test
    fun `window plan passes exact double table contract to lookup`() {
        val table = doubleArrayOf(0.0, 1.0, 4.0, 9.0, 16.0)
        val plan = S5SppWindowLookupPlan(
            table = table,
            segmentCount = 4,
            scale = 2.0,
            fallbackValue = -99.0
        )

        val actual = plan.coefficient(-0.75) { values, segments, scale, fallback, x ->
            assertEquals(table.toList(), values.toList())
            assertEquals(4, segments)
            assertEquals(2.0, scale, 0.0)
            assertEquals(-99.0, fallback, 0.0)
            assertEquals(-0.75, x, 0.0)
            // Test double-precision interpolation independently of the JNI library.
            val scaled = kotlin.math.abs(x) * scale
            if (scaled >= segments) fallback else {
                val index = kotlin.math.floor(scaled).toInt()
                values[index] + (values[index + 1] - values[index]) * (scaled - index)
            }
        }
        assertEquals(2.5, actual, 1.0e-12)
    }

    @Test
    fun `window plan rejects short tables and invalid segment values`() {
        assertThrows(IllegalArgumentException::class.java) {
            S5SppWindowLookupPlan(doubleArrayOf(0.0, 1.0), 2, 1.0, 0.0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            S5SppWindowLookupPlan(doubleArrayOf(0.0), -1, 1.0, 0.0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            S5SppWindowLookupPlan(doubleArrayOf(0.0), 0, -1.0, 0.0)
        }
    }

    @Test
    fun `window plan copies caller and returned table arrays`() {
        val source = doubleArrayOf(0.0, 1.0, 4.0)
        val plan = S5SppWindowLookupPlan(source, 2, 1.0, -1.0)
        source[1] = 99.0
        assertEquals(listOf(0.0, 1.0, 4.0), plan.table.toList())
        val exposed = plan.table
        exposed[2] = 99.0
        assertEquals(listOf(0.0, 1.0, 4.0), plan.table.toList())
        val sampled = plan.coefficient(0.5) { values, _, _, _, _ ->
            assertEquals(listOf(0.0, 1.0, 4.0), values.toList())
            values[0]
        }
        assertEquals(0.0, sampled, 0.0)
    }
    @Test
    fun `window plan rejects non finite table and coordinate`() {
        assertThrows(IllegalArgumentException::class.java) {
            S5SppWindowLookupPlan(doubleArrayOf(0.0, Double.NaN), 1, 1.0, 0.0)
        }
        val plan = S5SppWindowLookupPlan(doubleArrayOf(0.0), 0, 1.0, 0.0)
        assertThrows(IllegalArgumentException::class.java) {
            plan.coefficient(Double.POSITIVE_INFINITY) { _, _, _, _, _ -> 0.0 }
        }
    }
}
