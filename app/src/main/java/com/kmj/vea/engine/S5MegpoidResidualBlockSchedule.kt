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

/** The two workspaces owned by one sequence row in FUN_1800c7ad0. */
enum class S5MegpoidWorkspaceSlot {
    FIRST,
    SECOND;

    fun other(): S5MegpoidWorkspaceSlot =
        if (this == FIRST) SECOND else FIRST
}

/** One native residual-block call for a particular sequence row and block column. */
data class S5MegpoidResidualBlockScheduleStep(
    /** Actual row index in the four-row sequence-feature array. */
    val sequenceIndex: Int,
    /** Zero-based block-column call within this sequence row. */
    val blockOrdinal: Int,
    val block: S5MegpoidBlockPosition,
    val inputWorkspace: S5MegpoidWorkspaceSlot,
    val outputWorkspace: S5MegpoidWorkspaceSlot
)

/**
 * Exact residual-loop ordering recovered from FUN_1800c7ad0.
 *
 * The native outer loop visits all sequence rows in descending order:
 *   row = sequenceCount - 1, sequenceCount - 2, ..., 0
 *
 * For each row it invokes only that row's block columns, in ascending order.
 * Each sequence row has its own FIRST/SECOND workspace pair; the pair is
 * swapped after every block call and is reset to its initial slot for the
 * next row. The eight VTB2 records are therefore called once each per
 * dispatcher invocation, not eight times per sequence row.
 *
 * Dense, normalization, temporal-transition and banked-linear operations
 * surrounding this loop remain separate stages.
 */
class S5MegpoidResidualBlockSchedule private constructor(
    val sequenceCount: Int,
    val blockPositions: List<S5MegpoidBlockPosition>,
    val steps: List<S5MegpoidResidualBlockScheduleStep>
) {
    /** Number of sequence rows visited by the native residual loop. */
    val sequenceIterations: Int
        get() = sequenceCount

    companion object {
        fun build(
            sequenceCount: Int,
            blockPositions: List<S5MegpoidBlockPosition>,
            firstInputWorkspace: S5MegpoidWorkspaceSlot = S5MegpoidWorkspaceSlot.FIRST
        ): S5MegpoidResidualBlockSchedule {
            require(sequenceCount > 0) {
                "Sequence-row count must be positive."
            }
            require(blockPositions.isNotEmpty()) {
                "At least one residual block is required."
            }
            require(blockPositions.distinct().size == blockPositions.size) {
                "Residual block positions must be unique."
            }

            val rows = blockPositions.map { it.row }.toSet()
            require(rows == (0 until sequenceCount).toSet()) {
                "Residual block rows must cover exactly 0 until sequenceCount."
            }
            val columns = blockPositions.map { it.column }.toSet()
            require(columns == (0..(columns.size - 1)).toSet()) {
                "Residual block columns must be contiguous and zero-based."
            }
            for (row in 0 until sequenceCount) {
                require(blockPositions.count { it.row == row } == columns.size) {
                    "Every sequence row must contain the same number of block columns."
                }
            }

            val stepCount = sequenceCount.toLong() * columns.size.toLong()
            require(stepCount <= Int.MAX_VALUE) {
                "Residual schedule contains too many steps."
            }
            val plan = ArrayList<S5MegpoidResidualBlockScheduleStep>(stepCount.toInt())

            for (sequenceIndex in sequenceCount - 1 downTo 0) {
                var input = firstInputWorkspace
                var output = input.other()
                val rowBlocks = blockPositions
                    .filter { it.row == sequenceIndex }
                    .sortedBy { it.column }

                for ((blockOrdinal, block) in rowBlocks.withIndex()) {
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
                blockPositions = blockPositions.sortedWith(
                    compareBy<S5MegpoidBlockPosition> { it.row }.thenBy { it.column }
                ),
                steps = plan
            )
        }

        fun megpoidV7(
            sequenceCount: Int = S5MegpoidRuntimeBlockFactoryKeys.BLOCK_ROWS,
            firstInputWorkspace: S5MegpoidWorkspaceSlot = S5MegpoidWorkspaceSlot.FIRST
        ): S5MegpoidResidualBlockSchedule {
            require(sequenceCount == S5MegpoidRuntimeBlockFactoryKeys.BLOCK_ROWS) {
                "AI Megpoid V7 supplies exactly four residual sequence rows."
            }
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
