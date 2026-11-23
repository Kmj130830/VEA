package com.kmj.vea.engine

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class S5SppPhaseNoiseStateTest {
    @Test
    fun `phase state persists across frames and snapshot is detached`() {
        val calls = mutableListOf<Int>()
        val seed = intArrayOf(1, 2, 3, 4)
        val stream = S5SppPhaseNoiseState(seed) { magnitudes, state, edgeMode ->
            calls += edgeMode
            state[0] += 10
            state[1] += magnitudes.size
            FloatArray(magnitudes.size * 2)
        }

        seed[0] = 900
        val first = stream.generate(floatArrayOf(1f, 2f), edgeMode = 0)
        assertEquals(4, first.size)
        val saved = stream.snapshot()
        assertArrayEquals(intArrayOf(11, 4, 3, 4), saved)

        saved[0] = -1
        stream.generate(floatArrayOf(3f), edgeMode = 1)
        assertArrayEquals(intArrayOf(21, 5, 3, 4), stream.snapshot())
        assertEquals(listOf(0, 1), calls)
    }

    @Test
    fun `phase state restores a prior deterministic checkpoint`() {
        val stream = S5SppPhaseNoiseState(intArrayOf(10, 20, 30, 40)) { _, state, _ ->
            state[3] = state[0] xor state[1]
            FloatArray(0)
        }

        stream.generate(floatArrayOf(1f))
        val checkpoint = stream.snapshot()
        stream.generate(floatArrayOf(2f))
        stream.restore(checkpoint)
        assertArrayEquals(checkpoint, stream.snapshot())
        stream.generate(floatArrayOf(3f))
        assertArrayEquals(checkpoint, stream.snapshot().also { it[3] = checkpoint[0] xor checkpoint[1] })
    }

    @Test
    fun `phase state validates seed restore and frame input`() {
        assertThrows(IllegalArgumentException::class.java) {
            S5SppPhaseNoiseState(intArrayOf(1, 2, 3))
        }
        val stream = S5SppPhaseNoiseState(intArrayOf(1, 2, 3, 4)) { _, _, _ -> FloatArray(0) }
        assertThrows(IllegalArgumentException::class.java) {
            stream.restore(intArrayOf(1, 2))
        }
        assertThrows(IllegalArgumentException::class.java) {
            stream.generate(floatArrayOf(Float.NaN))
        }
        assertArrayEquals(intArrayOf(1, 2, 3, 4), stream.snapshot())
    }
}
