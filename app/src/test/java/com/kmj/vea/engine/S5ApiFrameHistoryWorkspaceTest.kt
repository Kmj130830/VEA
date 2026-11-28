package com.kmj.vea.engine

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class S5ApiFrameHistoryWorkspaceTest {
    @Test
    fun keepsInputAndIntermediateRingsIndependent() {
        val workspace = S5ApiFrameHistoryWorkspace(
            listOf(
                S5ApiFrameHistoryRingSpec(elementCount = 2, slotStride = 4, historySlots = 4),
                S5ApiFrameHistoryRingSpec(elementCount = 1, slotStride = 1, historySlots = 2)
            )
        )

        workspace.append(0, floatArrayOf(1f, 2f))
        workspace.append(0, floatArrayOf(3f, 4f))
        workspace.append(1, floatArrayOf(9f))

        assertEquals(2L, workspace.cursor(0))
        assertEquals(1L, workspace.cursor(1))
        assertArrayEquals(floatArrayOf(3f, 4f), workspace.readDelayed(0, 0), 0f)
        assertArrayEquals(floatArrayOf(1f, 2f), workspace.readDelayed(0, 1), 0f)
        assertArrayEquals(floatArrayOf(9f), workspace.readDelayed(1, 0), 0f)
        assertArrayEquals(floatArrayOf(0f), workspace.readDelayed(1, 1), 0f)
    }

    @Test
    fun snapshotsSeveralOperatorInputsFromOneAtomicAppend() {
        val workspace = S5ApiFrameHistoryWorkspace(
            listOf(S5ApiFrameHistoryRingSpec(2, 2, 4))
        )
        workspace.append(0, floatArrayOf(1f, 10f))

        val snapshot = workspace.appendAndReadDelayed(
            ringIndex = 0,
            frame = floatArrayOf(2f, 20f),
            delayedFrameCount = 3
        )

        assertEquals(1L, snapshot.writeSequence)
        assertEquals(3, snapshot.frameCount)
        assertEquals(2, snapshot.featureWidth)
        assertArrayEquals(floatArrayOf(2f, 20f), snapshot.delayedFrame(0), 0f)
        assertArrayEquals(floatArrayOf(1f, 10f), snapshot.delayedFrame(1), 0f)
        assertArrayEquals(floatArrayOf(0f, 0f), snapshot.delayedFrame(2), 0f)

        // Operator-side mutation cannot alter a second read from the snapshot.
        val first = snapshot.delayedFrame(0)
        first[0] = -100f
        assertArrayEquals(floatArrayOf(2f, 20f), snapshot.delayedFrame(0), 0f)
    }

    @Test
    fun resetsAllRingsAndTheirCursorsTogether() {
        val workspace = S5ApiFrameHistoryWorkspace(
            listOf(
                S5ApiFrameHistoryRingSpec(1, 1, 2),
                S5ApiFrameHistoryRingSpec(2, 3, 4)
            )
        )
        workspace.append(0, floatArrayOf(7f))
        workspace.append(1, floatArrayOf(8f, 9f))
        workspace.reset()

        assertArrayEquals(longArrayOf(0L, 0L), workspace.copyCursors())
        assertArrayEquals(floatArrayOf(0f), workspace.readDelayed(0, 0), 0f)
        assertArrayEquals(floatArrayOf(0f, 0f), workspace.readDelayed(1, 0), 0f)
    }

    @Test
    fun validatesRingGeometryAndWorkspaceIndices() {
        assertThrows(IllegalArgumentException::class.java) {
            S5ApiFrameHistoryRingSpec(elementCount = 3, slotStride = 2, historySlots = 4)
        }
        assertThrows(IllegalArgumentException::class.java) {
            S5ApiFrameHistoryRingSpec(elementCount = 1, slotStride = 1, historySlots = 3)
        }
        assertThrows(IllegalArgumentException::class.java) {
            S5ApiFrameHistoryWorkspace(emptyList())
        }

        val workspace = S5ApiFrameHistoryWorkspace(
            listOf(S5ApiFrameHistoryRingSpec(1, 1, 2))
        )
        assertThrows(IllegalArgumentException::class.java) {
            workspace.append(1, floatArrayOf(1f))
        }
        assertThrows(IllegalArgumentException::class.java) {
            workspace.readDelayed(0, -1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            workspace.append(0, floatArrayOf(1f, 2f))
        }
    }
}
