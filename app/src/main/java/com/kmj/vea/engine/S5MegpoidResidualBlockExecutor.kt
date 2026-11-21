package com.kmj.vea.engine

/**
 * One stateful residual block from the 4x2 AI Megpoid V7 VTB2 grid.
 * The production adapter delegates to S5MegpoidCompiledRuntimeBlock.
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
 * Executes the residual section of FUN_1800c7ad0.
 *
 * Input is a list of four already-prepared sequence feature tensors, indexed
 * by model row. The native dispatcher processes row 3, 2, 1, then 0. Within
 * each row it calls only columns 0 and 1, swapping that row's private pair of
 * workspace descriptors after each block. The two workspace pairs belonging
 * to different sequence rows are independent.
 *
 * The returned list preserves the caller's original row order (0..3).
 * This executes the residual part only; upstream note/phoneme feature
 * generation and the dense/normalization/transition stages are not included.
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
     * Run four sequence rows in native reverse-row order.
     *
     * Each input is copied once into that row's initial workspace. Subsequent
     * block outputs are passed by reference to the other workspace; this
     * executor does not make an extra copy between adjacent blocks. Different
     * sequence rows have independent workspace pairs and may have different
     * position counts after the upstream stride-2 transition stage.
     *
     * Keep this executor alive across audio chunks so each compiled block's
     * private seven-slot history persists. Call resetHistory() only when a
     * new utterance starts.
     */
    @Synchronized
    fun runSequenceFeatures(
        sequenceFeatures: List<FloatArray>,
        epsilon: Float = S5MegpoidResidualBlockShape.DEFAULT_EPSILON
    ): List<FloatArray> {
        require(sequenceFeatures.size == S5MegpoidRuntimeBlockFactoryKeys.BLOCK_ROWS) {
            "Exactly four sequence feature tensors are required for AI Megpoid V7."
        }
        require(epsilon.isFinite() && epsilon >= 0.0f) {
            "Epsilon must be finite and non-negative."
        }
        sequenceFeatures.forEachIndexed { row, features ->
            require(features.isNotEmpty() && features.size % channels == 0) {
                "Sequence row $row must contain a non-empty, whole number of channel rows."
            }
            val positions = features.size / channels
            require(positions >= S5MegpoidResidualBlockShape.KERNEL_WIDTH) {
                "Sequence row $row requires at least " +
                    "${S5MegpoidResidualBlockShape.KERNEL_WIDTH} positions."
            }
        }

        val schedule = S5MegpoidResidualBlockSchedule.megpoidV7(
            sequenceCount = sequenceFeatures.size,
            firstInputWorkspace = firstInputWorkspace
        )
        val firstWorkspaces = arrayOfNulls<FloatArray>(sequenceFeatures.size)
        val secondWorkspaces = arrayOfNulls<FloatArray>(sequenceFeatures.size)
        val finalSlots = Array(sequenceFeatures.size) { firstInputWorkspace }

        for (row in sequenceFeatures.indices) {
            when (firstInputWorkspace) {
                S5MegpoidWorkspaceSlot.FIRST ->
                    firstWorkspaces[row] = sequenceFeatures[row].copyOf()
                S5MegpoidWorkspaceSlot.SECOND ->
                    secondWorkspaces[row] = sequenceFeatures[row].copyOf()
            }
        }

        fun workspace(row: Int, slot: S5MegpoidWorkspaceSlot): FloatArray =
            when (slot) {
                S5MegpoidWorkspaceSlot.FIRST -> firstWorkspaces[row]
                S5MegpoidWorkspaceSlot.SECOND -> secondWorkspaces[row]
            } ?: error("Sequence row $row has no initialized workspace in slot $slot.")

        for (step in schedule.steps) {
            val row = step.sequenceIndex
            check(step.inputWorkspace == finalSlots[row]) {
                "Residual workspace sequence is discontinuous at row $row, block ${step.blockOrdinal}."
            }

            val input = workspace(row, step.inputWorkspace)
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
                S5MegpoidWorkspaceSlot.FIRST -> firstWorkspaces[row] = output
                S5MegpoidWorkspaceSlot.SECOND -> secondWorkspaces[row] = output
            }
            finalSlots[row] = step.outputWorkspace
        }

        return sequenceFeatures.indices.map { row -> workspace(row, finalSlots[row]) }
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
