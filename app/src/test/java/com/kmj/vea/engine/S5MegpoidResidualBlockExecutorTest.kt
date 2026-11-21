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

    private val canonicalOrder = (0 until 4).flatMap { row ->
        (0 until 2).map { column -> row to column }
    }

    @Test
    fun executesEightBlocksInRecoveredRowMajorOrderAndPassesWorkspaceOutputForward() {
        val events = mutableListOf<Pair<Int, Int>>()
        val nodes = fakeNodes(events)
        val executor = S5MegpoidResidualBlockExecutor(nodes)
        val input = FloatArray(7) { it.toFloat() }

        val output = executor.runChunk(input, sequenceCount = 2)

        assertEquals(canonicalOrder, events)
        assertEquals(input.indices.map { input[it] + 36.0f }, output.toList())
        assertEquals((0..6).map(Int::toFloat), input.toList())
        assertNotSame(input, output)
        nodes.forEach { assertEquals(1, it.invocationCount) }
    }

    @Test
    fun executesEveryBlockForEachSequenceIterationWithoutResettingBetweenIterations() {
        val events = mutableListOf<Pair<Int, Int>>()
        val nodes = fakeNodes(events)
        val executor = S5MegpoidResidualBlockExecutor(nodes)
        val input = FloatArray(8) { 1.0f }

        val output = executor.runChunk(input, sequenceCount = 3)

        assertEquals(canonicalOrder + canonicalOrder, events)
        assertEquals(List(8) { 73.0f }, output.toList())
        nodes.forEach { assertEquals(2, it.invocationCount) }
    }

    @Test
    fun supportsSecondWorkspaceAsTheInitialInput() {
        val events = mutableListOf<Pair<Int, Int>>()
        val executor = S5MegpoidResidualBlockExecutor(
            nodes = fakeNodes(events),
            firstInputWorkspace = S5MegpoidWorkspaceSlot.SECOND
        )

        val output = executor.runChunk(FloatArray(7) { 2.0f }, sequenceCount = 2)

        assertEquals(canonicalOrder, events)
        assertEquals(List(7) { 38.0f }, output.toList())
    }

    @Test
    fun sequenceCountOneReturnsAnOwnedCopyWithoutInvokingBlocks() {
        val events = mutableListOf<Pair<Int, Int>>()
        val executor = S5MegpoidResidualBlockExecutor(fakeNodes(events))
        val input = FloatArray(3) { it.toFloat() }

        val output = executor.runChunk(input, sequenceCount = 1)

        assertEquals(emptyList<Pair<Int, Int>>(), events)
        assertEquals(input.toList(), output.toList())
        assertNotSame(input, output)
    }

    @Test
    fun resetHistoryResetsEveryBlockAndOnlyWhenRequested() {
        val events = mutableListOf<Pair<Int, Int>>()
        val nodes = fakeNodes(events)
        val executor = S5MegpoidResidualBlockExecutor(nodes)

        executor.runChunk(FloatArray(7), sequenceCount = 2)
        nodes.forEach { assertEquals(0, it.resetCount) }

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
    fun rejectsInvalidChunkShapeAndTooShortChunkBeforeCallingAnyBlock() {
        val events = mutableListOf<Pair<Int, Int>>()
        val executor = S5MegpoidResidualBlockExecutor(fakeNodes(events))

        assertThrows(IllegalArgumentException::class.java) {
            executor.runChunk(FloatArray(6), sequenceCount = 2)
        }
        assertThrows(IllegalArgumentException::class.java) {
            executor.runChunk(FloatArray(6), sequenceCount = 2, epsilon = Float.NaN)
        }
        assertEquals(emptyList<Pair<Int, Int>>(), events)
    }

    @Test
    fun rejectsBlockOutputThatAliasesItsInput() {
        val events = mutableListOf<Pair<Int, Int>>()
        val aliasingNodes = fakeNodes(events).mapIndexed { index, node ->
            if (index == 0) object : S5MegpoidResidualBlockNode {
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
            executor.runChunk(FloatArray(7), sequenceCount = 2)
        }
    }
}
