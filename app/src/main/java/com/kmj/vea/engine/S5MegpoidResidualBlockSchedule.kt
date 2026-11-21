package com.kmj.vea.engine

/** Identity of one entry in the model's row-major 4x2 residual-block grid. */
data class S5MegpoidBlockPosition(
    val row: Int,
    val column: Int
) {
    init {
        require(row in 0 until S5MegpoidRuntimeBlockFactoryKeys.BLOCK_ROWS)
        require(column in 0 until S5MegpoidRuntimeBlockFactoryKeys.BLOCK_COLUMNS)
    }
}

/** The two alternating workspaces passed through FUN_1800c7ad0. */
enum class S5MegpoidWorkspaceSlot {
    FIRST,
    SECOND;

    fun other(): S5MegpoidWorkspaceSlot =
        if (this == FIRST) SECOND else FIRST
}

/**
 * One residual-block invocation in the native sequence-major dispatch loop.
 *
 * sequenceIndex is in [0, sequenceCount - 2], because the recovered outer
 * loop condition is currentSequence < sequenceCount - 1.
 */
data class S5MegpoidResidualBlockScheduleStep(
    val sequenceIndex: Int,
    val blockOrdinal: Int,
    val block: S5MegpoidBlockPosition,
    val inputWorkspace: S5MegpoidWorkspaceSlot,
    val outputWorkspace: S5MegpoidWorkspaceSlot
)

/**
 * Static execution plan for the repeated residual-block portion of
 * FUN_1800c7ad0.
 *
 * Observed native order:
 *   for sequenceIndex in 0 until sequenceCount - 1
 *     for blockOrdinal in 0 until blockCount
 *       invoke residual block with current input/output workspaces
 *       swap input and output workspaces
 *
 * This planner does not execute the neighboring preprocessing/transition
 * operators (FUN_1800c5260, FUN_1800c5440, FUN_1800c6390/FUN_1800c72d0), nor
 * does it claim that project notes have already been converted to model
 * features. It only makes the recovered residual loop deterministic.
 */
class S5MegpoidResidualBlockSchedule private constructor(
    val sequenceCount: Int,
    val blockPositions: List<S5MegpoidBlockPosition>,
    val steps: List<S5MegpoidResidualBlockScheduleStep>
) {
    val sequenceIterations: Int
        get() = (sequenceCount - 1).coerceAtLeast(0)

    companion object {
        fun build(
            sequenceCount: Int,
            blockPositions: List<S5MegpoidBlockPosition>,
            firstInputWorkspace: S5MegpoidWorkspaceSlot = S5MegpoidWorkspaceSlot.FIRST
        ): S5MegpoidResidualBlockSchedule {
            require(sequenceCount >= 1) {
                "Sequence count must be at least one."
            }
            require(blockPositions.isNotEmpty()) {
                "At least one residual block is required."
            }
            require(blockPositions.distinct().size == blockPositions.size) {
                "Residual block positions must be unique."
            }

            val stepCount = (sequenceCount - 1).toLong() * blockPositions.size.toLong()
            require(stepCount <= Int.MAX_VALUE) {
                "Residual schedule contains too many steps."
            }
            val plan = ArrayList<S5MegpoidResidualBlockScheduleStep>(stepCount.toInt())
            var input = firstInputWorkspace
            var output = input.other()

            for (sequenceIndex in 0 until (sequenceCount - 1)) {
                for ((blockOrdinal, block) in blockPositions.withIndex()) {
                    plan += S5MegpoidResidualBlockScheduleStep(
                        sequenceIndex = sequenceIndex,
                        blockOrdinal = blockOrdinal,
                        block = block,
                        inputWorkspace = input,
                        outputWorkspace = output
                    )
                    val previousInput = input
                    input = output
                    output = previousInput
                }
            }

            return S5MegpoidResidualBlockSchedule(
                sequenceCount = sequenceCount,
                blockPositions = blockPositions.toList(),
                steps = plan
            )
        }

        fun megpoidV7(
            sequenceCount: Int,
            firstInputWorkspace: S5MegpoidWorkspaceSlot = S5MegpoidWorkspaceSlot.FIRST
        ): S5MegpoidResidualBlockSchedule {
            val blocks = buildList {
                for (row in 0 until S5MegpoidRuntimeBlockFactoryKeys.BLOCK_ROWS) {
                    for (column in 0 until S5MegpoidRuntimeBlockFactoryKeys.BLOCK_COLUMNS) {
                        add(S5MegpoidBlockPosition(row, column))
                    }
                }
            }
            return build(sequenceCount, blocks, firstInputWorkspace)
        }
    }
}
