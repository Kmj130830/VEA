package com.kmj.vea.engine

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class S5ApiFrameHistoryRingTest {
    @Test
    fun operatorIndexZeroReadsTheJustAppendedFrame() {
        val ring = S5ApiFrameHistoryRing(
            elementCount = 3,
            slotStride = 4,
            historySlots = 4
        )

        ring.append(floatArrayOf(1f, 2f, 3f))

        assertEquals(1L, ring.cursor)
        assertArrayEquals(floatArrayOf(1f, 2f, 3f), ring.readDelayed(0), 0f)
        assertArrayEquals(floatArrayOf(0f, 0f, 0f, 0f), ring.readSlot(1), 0f)
    }

    @Test
    fun operatorIndexSelectsPreviousFramesAndZeroFillsStartupHistory() {
        val ring = S5ApiFrameHistoryRing(
            elementCount = 2,
            slotStride = 2,
            historySlots = 4
        )
        ring.append(floatArrayOf(10f, 11f))
        ring.append(floatArrayOf(20f, 21f))

        assertArrayEquals(floatArrayOf(20f, 21f), ring.readDelayed(0), 0f)
        assertArrayEquals(floatArrayOf(10f, 11f), ring.readDelayed(1), 0f)
        assertArrayEquals(floatArrayOf(0f, 0f), ring.readDelayed(2), 0f)
        assertArrayEquals(floatArrayOf(0f, 0f), ring.readDelayed(3), 0f)
    }

    @Test
    fun wrapsUsingNativeCursorAndMaskSlotSelection() {
        val ring = S5ApiFrameHistoryRing(
            elementCount = 1,
            slotStride = 1,
            historySlots = 4
        )
        repeat(6) { value -> ring.append(floatArrayOf(value.toFloat() + 1f)) }

        assertEquals(6L, ring.cursor)
        assertArrayEquals(floatArrayOf(6f), ring.readDelayed(0), 0f)
        assertArrayEquals(floatArrayOf(5f), ring.readDelayed(1), 0f)
        assertArrayEquals(floatArrayOf(4f), ring.readDelayed(2), 0f)
        assertArrayEquals(floatArrayOf(3f), ring.readDelayed(3), 0f)
        assertArrayEquals(
            floatArrayOf(5f, 6f, 3f, 4f),
            ring.copyHistoryOldestToNewest(),
            0f
        )
    }

    @Test
    fun clearsPaddingAndHistoryOnAppendAndReset() {
        val ring = S5ApiFrameHistoryRing(
            elementCount = 2,
            slotStride = 4,
            historySlots = 2
        )
        ring.append(floatArrayOf(1f, 2f))
        ring.append(floatArrayOf(3f, 4f))
        ring.append(floatArrayOf(5f, 6f))

        assertArrayEquals(floatArrayOf(5f, 6f, 0f, 0f), ring.readSlot(0), 0f)
        assertArrayEquals(floatArrayOf(3f, 4f, 0f, 0f), ring.readSlot(1), 0f)

        ring.reset()
        assertEquals(0L, ring.cursor)
        assertArrayEquals(FloatArray(8), ring.copyHistoryOldestToNewest(), 0f)
        assertArrayEquals(floatArrayOf(0f, 0f), ring.readDelayed(0), 0f)
    }

    @Test
    fun rejectsInvalidGeometryFrameWidthsAndNonFiniteSamples() {
        assertThrows(IllegalArgumentException::class.java) {
            S5ApiFrameHistoryRing(elementCount = 0, slotStride = 1, historySlots = 2)
        }
        assertThrows(IllegalArgumentException::class.java) {
            S5ApiFrameHistoryRing(elementCount = 3, slotStride = 2, historySlots = 2)
        }
        assertThrows(IllegalArgumentException::class.java) {
            S5ApiFrameHistoryRing(elementCount = 1, slotStride = 1, historySlots = 3)
        }
        val ring = S5ApiFrameHistoryRing(2, 2, 2)
        assertThrows(IllegalArgumentException::class.java) {
            ring.append(floatArrayOf(1f))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ring.append(floatArrayOf(1f, Float.NaN))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ring.readDelayed(-1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ring.readSlot(2)
        }
    }
}
