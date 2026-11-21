package com.kmj.vea.engine

/**
 * Minimal contract for one stateful residual block in the 4x2 Megpoid stack.
 * The production adapter delegates to S5MegpoidCompiledRuntimeBlock; the
 * interface also permits deterministic orchestration tests without JNI.
 */
interface S5MegpoidResidualBlockNode {
    val row: Int
    val column: Int
    val channels: Int

    /** Returns a newly owned [channel][position] output tensor. */
    fun runChunk(residualInput: FloatArray, epsilon: Float): FloatArray

    /** Clears only this block's temporal history. */
    fun resetHistory()
}

/**
 * Executes the recovered sequence-major residual-block loop.
 *
 * One call processes one feature chunk through sequenceCount - 1 iterations,
 * with the eight model blocks invoked in row-major 4x2 order per iteration.
 * The block output becomes the next input workspace by reference assignment:
 * this layer does not copy an intermediate FloatArray between adjacent blocks.
 *
 * Each block owns its own seven-slot history ring. Keep this executor alive
 * across sequential chunks; call resetHistory() only when a new utterance
 * begins. This is an isolated residual-stack runner, not a complete
 * VOCALOID/Megpoid singing renderer.
 */
class S5MegpoidResidualBlockExecutor(
    nodes: List<S5MegpoidResidualBlockNode>,
    private val firstInputWorkspace: S5MegpoidWorkspaceSlot = S5MegpoidWorkspaceSlot.FIRST
) {
    private val orderedNodes: List<S5MegpoidResidualBlockNode> = nodes.toList()
    private val nodesByPosition: Map<S5MegpoidBlockPosition, S5MegpoidResidualBlockNode>
    val channels: Int

    init {
        val expectedPositions = buildList {
            for (row in 0 until S5MegpoidRuntimeBlockFactoryKeys.BLOCK_ROWS) {
                for (column in 0 until S5MegpoidRuntimeBlockFactoryKeys.BLOCK_COLUMNS) {
                    add(S5MegpoidBlockPosition(row, column))
                }
            }
        }
        require(orderedNodes.size == expectedPositions.size) {
            "Exactly eight residual blocks (the Megpoid V7 4x2 grid) are required."
        }

        val actualPositions = orderedNodes.map { S5MegpoidBlockPosition(it.row, it.column) }
        require(actualPositions.distinct().size == actualPositions.size) {
            "Residual block positions must be unique."
        }
        require(actualPositions.toSet() == expectedPositions.toSet()) {
            "Residual blocks must cover every position in the Megpoid V7 4x2 grid."
        }

        val channelCounts = orderedNodes.map { it.channels }.toSet()
        require(channelCounts.size == 1 && channelCounts.single() > 0) {
            "All residual blocks must use the same positive channel count."
        }
        channels = channelCounts.single()
        nodesByPosition = orderedNodes.indices.associate { index ->
            actualPositions[index] to orderedNodes[index]
        }
    }

    /**
     * Runs one sequential feature chunk through the residual stack.
     *
     * [sequenceCount] follows the native loop convention: the loop invokes
     * each of the eight blocks sequenceCount - 1 times. The input is copied
     * once into the initial workspace; subsequent block outputs are handed
     * directly to the alternate workspace without an intermediate copy.
     *
     * History is retained between calls as long as the chunk position count
     * stays constant. The block history implementation resets itself when
     * that count changes, matching its current recovered shape-change rule.
     */
    @Synchronized
    fun runChunk(
        initialFeatures: FloatArray,
        sequenceCount: Int,
        epsilon: Float = S5MegpoidResidualBlockShape.DEFAULT_EPSILON
    ): FloatArray {
        require(initialFeatures.isNotEmpty() && initialFeatures.size % channels == 0) {
            "Input features must contain a non-empty, whole number of channel rows."
        }
        require(epsilon.isFinite() && epsilon >= 0.0f) {
            "Epsilon must be finite and non-negative."
        }

        val positions = initialFeatures.size / channels
        require(sequenceCount == 1 || positions >= S5MegpoidResidualBlockShape.KERNEL_WIDTH) {
            "Residual execution requires at least " +
                "${S5MegpoidResidualBlockShape.KERNEL_WIDTH} positions per chunk."
        }

        var firstWorkspace: FloatArray? = null
        var secondWorkspace: FloatArray? = null
        when (firstInputWorkspace) {
            S5MegpoidWorkspaceSlot.FIRST -> firstWorkspace = initialFeatures.copyOf()
            S5MegpoidWorkspaceSlot.SECOND -> secondWorkspace = initialFeatures.copyOf()
        }

        fun workspace(slot: S5MegpoidWorkspaceSlot): FloatArray =
            when (slot) {
                S5MegpoidWorkspaceSlot.FIRST -> firstWorkspace
                S5MegpoidWorkspaceSlot.SECOND -> secondWorkspace
            } ?: error("Residual schedule attempted to read an uninitialized workspace: $slot")

        val schedule = S5MegpoidResidualBlockSchedule.megpoidV7(
            sequenceCount = sequenceCount,
            firstInputWorkspace = firstInputWorkspace
        )
        var activeInputWorkspace = firstInputWorkspace

        for (step in schedule.steps) {
            check(step.inputWorkspace == activeInputWorkspace) {
                "Residual schedule input workspace is discontinuous at step ${step.blockOrdinal}."
            }

            val input = workspace(step.inputWorkspace)
            val block = nodesByPosition.getValue(step.block)
            val output = block.runChunk(input, epsilon)

            require(output.size == input.size) {
                "Block [${step.block.row},${step.block.column}] returned ${output.size} " +
                    "values; expected ${input.size}."
            }
            require(output !== input) {
                "A residual block must return a separate output array; in-place aliasing is unsupported."
            }

            when (step.outputWorkspace) {
                S5MegpoidWorkspaceSlot.FIRST -> firstWorkspace = output
                S5MegpoidWorkspaceSlot.SECOND -> secondWorkspace = output
            }
            activeInputWorkspace = step.outputWorkspace
        }

        return workspace(activeInputWorkspace)
    }

    /** Reset all eight block histories at the beginning of a new utterance. */
    @Synchronized
    fun resetHistory() {
        orderedNodes.forEach(S5MegpoidResidualBlockNode::resetHistory)
    }

    companion object {
        /** Adapt the eight compiled tensors prepared from AI_Megpoid.vtb2. */
        fun fromCompiledBlocks(
            blocks: List<S5MegpoidCompiledRuntimeBlock>,
            firstInputWorkspace: S5MegpoidWorkspaceSlot = S5MegpoidWorkspaceSlot.FIRST
        ): S5MegpoidResidualBlockExecutor =
            S5MegpoidResidualBlockExecutor(
                nodes = blocks.map(::CompiledRuntimeBlockNode),
                firstInputWorkspace = firstInputWorkspace
            )
    }
}

private class CompiledRuntimeBlockNode(
    private val block: S5MegpoidCompiledRuntimeBlock
) : S5MegpoidResidualBlockNode {
    override val row: Int get() = block.row
    override val column: Int get() = block.column
    override val channels: Int get() = block.channels

    override fun runChunk(residualInput: FloatArray, epsilon: Float): FloatArray =
        block.runChunk(residualInput, epsilon)

    override fun resetHistory() = block.resetHistory()
}
