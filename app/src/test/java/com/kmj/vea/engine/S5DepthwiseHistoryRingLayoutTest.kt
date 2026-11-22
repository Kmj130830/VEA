package com.kmj.vea.engine

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class S5DepthwiseHistoryRingLayoutTest {
    @Test
    fun `chronological ring snapshot transposes to channel major c5d50 order`() {
        // [row][channel][position]
        val chronological = floatArrayOf(
            1f, 2f, 3f,  4f, 5f, 6f,
            7f, 8f, 9f, 10f, 11f, 12f
        )

        val actual = S5DepthwiseHistoryRing.toChannelMajor(
            chronological = chronological,
            channels = 2,
            historyRows = 2,
            positions = 3
        )

        // [channel][row][position]
        assertArrayEquals(
            floatArrayOf(
                1f, 2f, 3f,  7f, 8f, 9f,
                4f, 5f, 6f, 10f, 11f, 12f
            ),
            actual,
            0f
        )
    }

    @Test
    fun `history layout conversion rejects a truncated snapshot`() {
        assertThrows(IllegalArgumentException::class.java) {
            S5DepthwiseHistoryRing.toChannelMajor(
                chronological = floatArrayOf(1f, 2f),
                channels = 2,
                historyRows = 2,
                positions = 3
            )
        }
    }

    @Test
    fun `history layout conversion rejects dimension overflow`() {
        assertThrows(ArithmeticException::class.java) {
            S5DepthwiseHistoryRing.toChannelMajor(
                chronological = floatArrayOf(),
                channels = Int.MAX_VALUE,
                historyRows = 2,
                positions = 3
            )
        }
    }
}
