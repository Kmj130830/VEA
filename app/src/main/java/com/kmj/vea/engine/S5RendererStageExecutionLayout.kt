package com.kmj.vea.engine

/** Projection operator selected by the raw renderer byte at byte offset 0x59. */
enum class S5RendererProjectionOperator {
    BANKED_LINEAR_C6390,
    UPSAMPLE_2X_C72D0
}

/** One paired projection/residual invocation in FUN_1800c7ad0. */
data class S5RendererStageInvocation(
    val reverseLoopIndex: Int,
    val projectionDescriptorIndex: Int,
    val residualParameterStageIndex: Int,
    val blockIndex: Int
) {
    val blockCoordinate: S5Vtb2C5d50BlockCoordinate
        get() = S5Vtb2C5d50BlockCoordinate(residualParameterStageIndex, blockIndex)

    /** Flattened index into the stage-major VTB2 block descriptor vector. */
    fun flattenedResidualDescriptorIndex(blocksPerStage: Int): Int {
        require(blocksPerStage > 0)
        return Math.addExact(
            Math.multiplyExact(residualParameterStageIndex, blocksPerStage),
            blockIndex
        )
    }
}

/**
 * Explicit descriptor and invocation shape recovered from FUN_1800c7ad0 and
 * its state-preparation helper FUN_1800d5a00.
 *
 * The model stores stageCount * blocksPerStage residual descriptors and the
 * same number of residual workspaces, but this render loop invokes only
 * (stageCount - 1) * blocksPerStage descriptors. For each reverse-loop step,
 * the projection descriptor index advances from zero, while the residual
 * parameter stage index descends from stageCount - 2 to zero.
 *
 * This layout deliberately contains no VTMG tensor-name mapping.
 */
data class S5RendererStageExecutionLayout private constructor(
    val stageCount: Int,
    val blocksPerStage: Int
) {
    val forwardStride2DescriptorCount: Int = stageCount - 1
    val reverseProjectionDescriptorCount: Int = stageCount - 1
    val loadedResidualDescriptorCount: Int = Math.multiplyExact(stageCount, blocksPerStage)
    val residualWorkspaceCount: Int = loadedResidualDescriptorCount

    val forwardStride2DescriptorIndices: List<Int> =
        (0 until forwardStride2DescriptorCount).toList()

    val projectionDescriptorIndices: List<Int> =
        (0 until reverseProjectionDescriptorCount).toList()

    val invocations: List<S5RendererStageInvocation> = buildList {
        // iVar29 = 1 .. stageCount-1 in FUN_1800c7ad0.
        for (reverseIndex in 1 until stageCount) {
            val projectionIndex = reverseIndex - 1
            val residualStageIndex = stageCount - reverseIndex - 1
            for (blockIndex in 0 until blocksPerStage) {
                add(
                    S5RendererStageInvocation(
                        reverseLoopIndex = reverseIndex,
                        projectionDescriptorIndex = projectionIndex,
                        residualParameterStageIndex = residualStageIndex,
                        blockIndex = blockIndex
                    )
                )
            }
        }
    }

    val activeResidualDescriptorCount: Int
        get() = invocations.size

    val uninvokedResidualCoordinates: List<S5Vtb2C5d50BlockCoordinate> = buildList {
        val uninvokedStage = stageCount - 1
        for (blockIndex in 0 until blocksPerStage) {
            add(S5Vtb2C5d50BlockCoordinate(uninvokedStage, blockIndex))
        }
    }

    fun projectionOperatorForRawFlag(rawFlag: Int): S5RendererProjectionOperator {
        require(rawFlag in 0..255) { "Projection mode flag must be an unsigned byte." }
        return if (rawFlag == 0) {
            S5RendererProjectionOperator.BANKED_LINEAR_C6390
        } else {
            S5RendererProjectionOperator.UPSAMPLE_2X_C72D0
        }
    }

    companion object {
        fun create(stageCount: Int, blocksPerStage: Int): S5RendererStageExecutionLayout {
            require(stageCount >= 1) { "Stage count must be positive." }
            require(blocksPerStage >= 1) { "Block count per stage must be positive." }
            val loadedCount = Math.multiplyExact(stageCount, blocksPerStage)
            val activeCount = Math.multiplyExact(stageCount - 1, blocksPerStage)
            require(loadedCount <= 1_000_000) {
                "Renderer descriptor count exceeds the supported safety limit."
            }
            require(activeCount <= 1_000_000) {
                "Renderer invocation count exceeds the supported safety limit."
            }
            return S5RendererStageExecutionLayout(stageCount, blocksPerStage)
        }
    }
}
