package com.kmj.vea.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class S5SppInverseFramePlanTest {
    @Test
    fun `inverse frame post processing follows fft normalize rotate order`() {
        val plan = S5SppInverseFramePlan(fftSize = 8, offset = 3)
        val packed = FloatArray(8) { it.toFloat() }
        val calls = mutableListOf<String>()

        val result = plan.process(
            packedSpectrum = packed,
            inverseTransform = { size, direction, input ->
                calls += "fft:$size:$direction"
                assertEquals(packed.toList(), input.toList())
                FloatArray(size) { (it + 1).toFloat() }
            },
            normalize = { input, scale ->
                calls += "scale:$scale"
                assertEquals(listOf(1f, 2f, 3f, 4f, 5f, 6f, 7f, 8f), input.toList())
                FloatArray(input.size) { input[it] * scale }
            },
            rotate = { input, shift ->
                calls += "rotate:$shift"
                assertEquals(-3, shift)
                assertEquals(
                    listOf(0.125f, 0.25f, 0.375f, 0.5f, 0.625f, 0.75f, 0.875f, 1f),
                    input.toList()
                )
                floatArrayOf(0.75f, 0.875f, 1f, 0.125f, 0.25f, 0.375f, 0.5f, 0.625f)
            }
        )

        assertEquals(listOf("fft:8:1", "scale:0.125", "rotate:-3"), calls)
        assertEquals(
            listOf(0.75f, 0.875f, 1f, 0.125f, 0.25f, 0.375f, 0.5f, 0.625f),
            result.toList()
        )
    }

    @Test
    fun `inverse frame plan preserves input and validates each processing boundary`() {
        val packed = FloatArray(8) { it.toFloat() }
        val original = packed.copyOf()
        val plan = S5SppInverseFramePlan(8, -2)
        assertThrows(IllegalArgumentException::class.java) {
            plan.process(
                FloatArray(7),
                inverseTransform = { _, _, _ -> FloatArray(8) },
                normalize = { values, _ -> values },
                rotate = { values, _ -> values }
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            plan.process(
                packed,
                inverseTransform = { _, _, _ -> FloatArray(7) },
                normalize = { values, _ -> values },
                rotate = { values, _ -> values }
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            plan.process(
                packed,
                inverseTransform = { _, _, _ -> FloatArray(8) },
                normalize = { _, _ -> FloatArray(7) },
                rotate = { values, _ -> values }
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            plan.process(
                packed,
                inverseTransform = { _, _, _ -> FloatArray(8) },
                normalize = { values, _ -> values },
                rotate = { _, _ -> FloatArray(7) }
            )
        }
        assertEquals(original.toList(), packed.toList())
    }

    @Test
    fun `inverse frame plan rejects unnegatable offset and invalid fft length`() {
        assertThrows(IllegalArgumentException::class.java) {
            S5SppInverseFramePlan(8, Int.MIN_VALUE)
        }
        assertThrows(IllegalArgumentException::class.java) {
            S5SppInverseFramePlan(12, 0)
        }
    }
}
