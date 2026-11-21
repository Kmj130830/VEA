package com.kmj.vea.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertThrows
import org.junit.Test

class S5MegpoidResidualBlockExecutorTest {
    private class FakeNode(
        override val row: Int,
        override val column: Int,
        override val channels: Int = 1,
        private val increment: Float = (row * 2 + column + 1).toFloat(),
        private val events: MutableList<Pair<Int, Int>>
    ) : S5MegpoidResidualBlockNode {
        var invocationCount: Int = 0
            private set
        var resetCount: Int = 0
            private set

        override fun runChunk(residualInput: FloatArray, epsilon: Float): FloatArray {
            require(epsilon.isFinite() && epsilon >= 0.0f)
            invocationCount++
            events += row to column
            return FloatArray(residualInput.size) { index -> residualInput[index] + increment }
        }

        override fun resetHistory() {
            resetCount++
        }
    }

    private fun fakeNodes(
        events: MutableList<Pair<Int, Int>>,
        channels: Int = 1
    ): List<FakeNode> = buildList {
        for (row in 0 until 4) {
            for (column in 0 until 2) {
                add(FakeNode(row, column, channels, events = events))
            }
        }
    }

    private val expectedCallOrder = listOf(
        3 to 0, 3 to 1,
        2 to 0, 2 to 1,
        1 to 0, 1 to 1,
        0 to 0, 0 to 1
    )

    private fun sequenceInputs(): List<FloatArray> =
        (0 until 4).map { row -> FloatArray(7) { (row * 10).toFloat() } }

    @Test
    fun executesEightRecordsOnceInReverseRowOrderAndKeepsRowsIndependent() {
        val events = mutableListOf<Pair<Int, Int>>()
        val nodes = fakeNodes(events)
        val executor = S5MegpoidResidualBlockExecutor(nodes)
        val inputs = sequenceInputs()

        val outputs = executor.runSequenceFeatures(inputs)

        assertEquals(expectedCallOrder, events)
        assertEquals(
            listOf(3f, 17f, 31f, 45f),
            outputs.map { it.first() }
        )
        assertEquals(listOf(0f, 10f, 20f, 30f), inputs.map { it.first() })
        assertEquals(4, outputs.size)
        outputs.indices.forEach { assertNotSame(inputs[it], outputs[it]) }
        nodes.forEach { assertEquals(1, it.invocationCount) }
    }

    @Test
    fun usesTheTwoStagePingPongPairIndependentlyForEverySequenceRow() {
        val events = mutableListOf<Pair<Int, Int>>()
        val nodes = fakeNodes(events)
        val executor = S5MegpoidResidualBlockExecutor(nodes)
        val inputs = sequenceInputs()

        val outputs = executor.runSequenceFeatures(inputs)

        // Each row gets only its own two block increments: (2r+1) + (2r+2).
        outputs.forEachIndexed { row, values ->
            assertEquals(7, values.size)
            values.forEach { assertEquals(row * 10.0f + row * 4.0f + 3.0f, it, 0.0001f) }
        }
    }

    @Test
    fun supportsSecondWorkspaceAsEachRowsInitialInput() {
        val events = mutableListOf<Pair<Int, Int>>()
        val executor = S5MegpoidResidualBlockExecutor(
            nodes = fakeNodes(events),
            firstInputWorkspace = S5MegpoidWorkspaceSlot.SECOND
        )

        val outputs = executor.runSequenceFeatures(sequenceInputs())

        assertEquals(expectedCallOrder, events)
        assertEquals(listOf(3f, 17f, 31f, 45f), outputs.map { it.first() })
    }

    @Test
    fun retainsEveryBlockHistoryAcrossCallsAndResetsOnlyOnRequest() {
        val events = mutableListOf<Pair<Int, Int>>()
        val nodes = fakeNodes(events)
        val executor = S5MegpoidResidualBlockExecutor(nodes)
        val inputs = sequenceInputs()

        executor.runSequenceFeatures(inputs)
        nodes.forEach { assertEquals(0, it.resetCount) }

        executor.runSequenceFeatures(inputs)
        nodes.forEach { assertEquals(2, it.invocationCount) }

        executor.resetHistory()
        nodes.forEach { assertEquals(1, it.resetCount) }
    }

    @Test
    fun rejectsIncompleteDuplicateAndMixedChannelBlockSets() {
        val events = mutableListOf<Pair<Int, Int>>()
        val complete = fakeNodes(events)

        assertThrows(IllegalArgumentException::class.java) {
            S5MegpoidResidualBlockExecutor(complete.dropLast(1))
        }
        assertThrows(IllegalArgumentException::class.java) {
            S5MegpoidResidualBlockExecutor(complete.dropLast(1) + complete.first())
        }
        assertThrows(IllegalArgumentException::class.java) {
            S5MegpoidResidualBlockExecutor(fakeNodes(events).mapIndexed { index, node ->
                if (index == 7) FakeNode(node.row, node.column, channels = 2, events = events)
                else node
            })
        }
    }

    @Test
    fun rejectsInvalidSequenceShapesAndEpsilonBeforeAnyBlockCall() {
        val events = mutableListOf<Pair<Int, Int>>()
        val executor = S5MegpoidResidualBlockExecutor(fakeNodes(events))
        val valid = sequenceInputs().toMutableList()

        assertThrows(IllegalArgumentException::class.java) {
            executor.runSequenceFeatures(valid.dropLast(1))
        }
        valid[2] = FloatArray(6)
        assertThrows(IllegalArgumentException::class.java) {
            executor.runSequenceFeatures(valid)
        }
        valid[2] = FloatArray(7)
        assertThrows(IllegalArgumentException::class.java) {
            executor.runSequenceFeatures(valid, epsilon = Float.NaN)
        }
        assertEquals(emptyList<Pair<Int, Int>>(), events)
    }

    @Test
    fun rejectsBlockOutputThatAliasesItsInput() {
        val events = mutableListOf<Pair<Int, Int>>()
        val aliasingNodes = fakeNodes(events).mapIndexed { index, node ->
            if (index == 7) object : S5MegpoidResidualBlockNode {
                override val row = node.row
                override val column = node.column
                override val channels = node.channels
                override fun runChunk(residualInput: FloatArray, epsilon: Float): FloatArray =
                    residualInput
                override fun resetHistory() = Unit
            } else node
        }

        val executor = S5MegpoidResidualBlockExecutor(aliasingNodes)
        assertThrows(IllegalArgumentException::class.java) {
            executor.runSequenceFeatures(sequenceInputs())
        }
    }
}
