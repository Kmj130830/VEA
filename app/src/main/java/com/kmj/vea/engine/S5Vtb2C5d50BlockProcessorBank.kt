package com.kmj.vea.engine

/** One explicit stage/block coordinate in the VOCALOID S5 renderer. */
data class S5Vtb2C5d50BlockCoordinate(
    val stageIndex: Int,
    val blockIndex: Int
)

/**
 * c5d50 calls made by FUN_1800c7ad0 for the decoded Megpoid configuration.
 *
 * The native reverse-stage loop skips reverse index zero. For a four-stage,
 * two-block configuration, the flattened descriptor index is
 * `(stageCount - reverseIndex - 1) * blocksPerStage + blockIndex`, producing
 * stage order 2, 1, 0. Stage 3 remains loaded but is not called by this
 * particular render function.
 */
object S5Vtb2C5d50RendererExecutionOrder {
    fun activeBlocks(
        stageCount: Int,
        blocksPerStage: Int
    ): List<S5Vtb2C5d50BlockCoordinate> {
        require(stageCount >= 1) { "Stage count must be positive." }
        require(blocksPerStage >= 1) { "Block count per stage must be positive." }

        return S5RendererStageExecutionLayout.create(
            stageCount = stageCount,
            blocksPerStage = blocksPerStage
        ).invocations.map { it.blockCoordinate }
    }

    fun notCalledByC7ad0(
        stageCount: Int,
        blocksPerStage: Int
    ): List<S5Vtb2C5d50BlockCoordinate> {
        val active = activeBlocks(stageCount, blocksPerStage).toSet()
        return buildList {
            for (stageIndex in 0 until stageCount) {
                for (blockIndex in 0 until blocksPerStage) {
                    val coordinate = S5Vtb2C5d50BlockCoordinate(stageIndex, blockIndex)
                    if (coordinate !in active) add(coordinate)
                }
            }
        }
    }
}

/**
 * Keeps independent temporal history for every c5d50 descriptor used by
 * FUN_1800c7ad0. Processing is addressed by explicit coordinates because
 * the surrounding stage scheduler must supply each stage's activation tensor.
 */
class S5Vtb2C5d50BlockProcessorBank(
    private val parameterSet: S5Vtb2C5d50StageBlockParameterSet
) : AutoCloseable {
    private val activeCoordinates = S5Vtb2C5d50RendererExecutionOrder.activeBlocks(
        parameterSet.stageCount, parameterSet.blocksPerStage
    ).toSet()
    private val processors = mutableMapOf<S5Vtb2C5d50BlockCoordinate, S5Vtb2C5d50BlockProcessor>()
    private var closed = false

    init {
        require(parameterSet.hopSize > 0)
        require(parameterSet.blocks.size == parameterSet.stageCount * parameterSet.blocksPerStage)
    }

    /** Number of block-local history rings currently allocated. */
    @Synchronized
    fun allocatedProcessorCount(): Int {
        check(!closed) { "c5d50 processor bank is already closed." }
        return processors.size
    }

    /**
     * Runs one c5d50 descriptor and advances only that descriptor's history.
     * Coordinates outside the recovered c7ad0 schedule are rejected.
     */
    @Synchronized
    fun process(
        stageIndex: Int,
        blockIndex: Int,
        input: FloatArray
    ): FloatArray {
        check(!closed) { "c5d50 processor bank is already closed." }
        val coordinate = S5Vtb2C5d50BlockCoordinate(stageIndex, blockIndex)
        require(coordinate in activeCoordinates) {
            "Stage $stageIndex block $blockIndex is not called by the recovered c7ad0 c5d50 loop."
        }
        val parameters = parameterSet.requireBlock(stageIndex, blockIndex)
        val processor = processors.getOrPut(coordinate) {
            S5Vtb2C5d50BlockProcessor(parameters, positions = parameterSet.hopSize)
        }
        return processor.process(input)
    }

    /** Resets one active descriptor without disturbing other stage histories. */
    @Synchronized
    fun reset(stageIndex: Int, blockIndex: Int) {
        check(!closed) { "c5d50 processor bank is already closed." }
        val coordinate = S5Vtb2C5d50BlockCoordinate(stageIndex, blockIndex)
        require(coordinate in activeCoordinates) {
            "Stage $stageIndex block $blockIndex is not called by the recovered c7ad0 c5d50 loop."
        }
        processors[coordinate]?.reset()
    }

    /** Resets all allocated descriptor histories. */
    @Synchronized
    fun resetAll() {
        check(!closed) { "c5d50 processor bank is already closed." }
        processors.values.forEach { it.reset() }
    }

    @Synchronized
    override fun close() {
        if (!closed) {
            closed = true
            processors.values.forEach { it.close() }
            processors.clear()
        }
    }
}
